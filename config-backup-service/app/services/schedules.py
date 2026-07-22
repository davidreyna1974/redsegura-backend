"""Programaciones recurrentes (RF-08): valida la expresión cron, persiste la programación y calcula
su próximo disparo. El scheduler (bucle de fondo) toma las programaciones vencidas, encola el job de
respaldo/drift correspondiente y avanza ``next_run_at`` con la expresión cron."""

from __future__ import annotations

import json
from datetime import datetime

from croniter import croniter
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.connectors.base import DeviceConnector
from app.db.models import Job, Schedule
from app.git_store import GitStore
from app.schemas import BatchTarget, ScheduleCreateRequest, ScheduleOut
from app.services.jobs import create_job, resolve_targets, run_job


class CronInvalidError(Exception):
    """La expresión cron no es válida."""


def _next_run(cron: str, base: datetime) -> datetime:
    result: datetime = croniter(cron, base).get_next(datetime)
    return result


def create_schedule(
    session: Session, req: ScheduleCreateRequest, actor: str, now: datetime
) -> Schedule:
    """Crea una programación ``activa`` con su próximo disparo calculado desde ``now``."""
    if not croniter.is_valid(req.cron):
        raise CronInvalidError(req.cron)
    schedule = Schedule(
        type=req.type.value,
        cron=req.cron,
        target_json=req.target.model_dump_json(by_alias=True, exclude_none=True),
        state="activa",
        next_run_at=_next_run(req.cron, now),
        created_by=actor,
        updated_by=actor,
    )
    session.add(schedule)
    session.commit()
    session.refresh(schedule)
    return schedule


def _target_of(schedule: Schedule) -> BatchTarget:
    return BatchTarget.model_validate(json.loads(schedule.target_json))


def to_schedule_out(schedule: Schedule) -> ScheduleOut:
    """Serializa una programación al DTO del contrato (rehidrata el objetivo desde JSON)."""
    return ScheduleOut(
        id=schedule.id,
        type=schedule.type,
        cron=schedule.cron,
        target=_target_of(schedule),
        state=schedule.state,
        created_at=schedule.created_at,
        created_by=schedule.created_by,
        updated_at=schedule.updated_at,
        updated_by=schedule.updated_by,
    )


def due_schedules(session: Session, now: datetime) -> list[Schedule]:
    """Programaciones activas cuyo próximo disparo ya venció."""
    stmt = (
        select(Schedule)
        .where(Schedule.state == "activa", Schedule.next_run_at <= now)
        .order_by(Schedule.next_run_at)
        .with_for_update(skip_locked=True)
    )
    return list(session.scalars(stmt).all())


def fire_schedule(
    session: Session,
    schedule: Schedule,
    connector: DeviceConnector,
    git_store: GitStore,
    allowed_cidrs: str,
    now: datetime,
) -> Job:
    """Encola y ejecuta el job de la programación, luego avanza ``next_run_at``."""
    device_ids = resolve_targets(session, _target_of(schedule))
    actor = f"scheduler:{schedule.id}"
    job = create_job(session, schedule.type, device_ids, actor)
    run_job(session, job.job_id, connector, git_store, allowed_cidrs)
    schedule.next_run_at = _next_run(schedule.cron, now)
    session.add(schedule)
    session.commit()
    return job


def tick_schedules(
    session: Session,
    connector: DeviceConnector,
    git_store: GitStore,
    allowed_cidrs: str,
    now: datetime,
) -> int:
    """Dispara todas las programaciones vencidas. Devuelve cuántas disparó."""
    fired = 0
    for schedule in due_schedules(session, now):
        fire_schedule(session, schedule, connector, git_store, allowed_cidrs, now)
        fired += 1
    return fired
