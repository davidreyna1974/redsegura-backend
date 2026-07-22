"""FLOW/RN/SEC/VAL: programaciones cron (RF-08) — servicio (crear, vencer, disparar) y endpoints."""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import UUID, uuid4

import pytest
from app.db.models import Device, Job, Schedule
from app.git_store import GitStore
from app.schemas import BatchTarget, ScheduleCreateRequest, ScheduleType
from app.services.schedules import (
    CronInvalidError,
    create_schedule,
    due_schedules,
    tick_schedules,
)
from fastapi.testclient import TestClient
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from tests.fakes import FakeConnector

CIDRS = "10.0.0.0/8,192.168.0.0/16"
ClientFactory = Callable[..., TestClient]


def _seed(session: Session, device_id: UUID, ipv4: str = "10.0.0.5") -> None:
    session.add(Device(device_id=device_id, hostname="SW1", mgmt_ipv4=ipv4, status="ACTIVO"))
    session.commit()


def _store(tmp_path: Path) -> GitStore:
    return GitStore(str(tmp_path / "repo"))


def _req(cron: str = "0 2 * * *") -> ScheduleCreateRequest:
    return ScheduleCreateRequest(
        type=ScheduleType.BACKUP, cron=cron, target=BatchTarget(scope="all")
    )


# --- Servicio (RN/VAL/FLOW) ---


def test_create_schedule_computes_next_run(db_session: Session) -> None:
    now = datetime(2026, 7, 22, 0, 0, tzinfo=UTC)
    schedule = create_schedule(db_session, _req("0 2 * * *"), "admin", now)

    assert schedule.state == "activa"
    assert schedule.next_run_at == datetime(2026, 7, 22, 2, 0, tzinfo=UTC)
    assert schedule.created_by == "admin"


def test_create_schedule_invalid_cron_raises(db_session: Session) -> None:
    with pytest.raises(CronInvalidError):
        create_schedule(db_session, _req("no-es-cron"), "admin", datetime.now(UTC))


def test_due_schedules_only_active_and_overdue(db_session: Session) -> None:
    now = datetime(2026, 7, 22, 12, 0, tzinfo=UTC)
    # Vencida y activa → aparece.
    create_schedule(db_session, _req("0 0 * * *"), "admin", now - timedelta(days=1))
    # Futura → no aparece.
    future = create_schedule(db_session, _req("0 0 * * *"), "admin", now)
    future.next_run_at = now + timedelta(hours=1)
    # Vencida pero pausada → no aparece.
    paused = create_schedule(db_session, _req("0 0 * * *"), "admin", now - timedelta(days=1))
    paused.state = "pausada"
    db_session.commit()

    due = due_schedules(db_session, now)

    assert len(due) == 1


def test_tick_fires_due_schedule_and_advances(db_session: Session, tmp_path: Path) -> None:
    device_id = uuid4()
    _seed(db_session, device_id)
    now = datetime(2026, 7, 22, 3, 0, tzinfo=UTC)
    schedule = create_schedule(db_session, _req("0 2 * * *"), "admin", now - timedelta(days=1))
    prev_next = schedule.next_run_at

    fired = tick_schedules(db_session, FakeConnector(), _store(tmp_path), CIDRS, now)

    assert fired == 1
    # Se creó y completó un job de respaldo.
    jobs = list(db_session.scalars(select(Job)).all())
    assert len(jobs) == 1
    assert jobs[0].status == "COMPLETED"
    assert jobs[0].created_by == f"scheduler:{schedule.id}"
    # Avanzó el próximo disparo.
    db_session.refresh(schedule)
    assert schedule.next_run_at > prev_next


# --- API (SEC/VAL) ---


def test_post_schedule_as_admin_returns_201(make_api_client: ClientFactory) -> None:
    resp = make_api_client(roles=("ADM",)).post(
        "/api/v1/schedules",
        json={"type": "backup", "cron": "0 2 * * *", "target": {"scope": "all"}},
    )

    assert resp.status_code == 201
    body = resp.json()
    assert body["state"] == "activa"
    assert body["cron"] == "0 2 * * *"
    assert body["target"]["scope"] == "all"
    assert body["createdBy"] == "tester"


def test_post_schedule_as_operator_is_403(make_api_client: ClientFactory) -> None:
    resp = make_api_client(roles=("OPE",)).post(
        "/api/v1/schedules",
        json={"type": "backup", "cron": "0 2 * * *", "target": {"scope": "all"}},
    )
    assert resp.status_code == 403


def test_post_schedule_invalid_cron_is_400(make_api_client: ClientFactory) -> None:
    resp = make_api_client(roles=("ADM",)).post(
        "/api/v1/schedules",
        json={"type": "backup", "cron": "malo", "target": {"scope": "all"}},
    )
    assert resp.status_code == 400
    assert resp.headers["content-type"].startswith("application/problem+json")


def test_list_schedules_as_auditor_with_filter(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    create_schedule(db_session, _req("0 2 * * *"), "admin", datetime.now(UTC))

    resp = make_api_client(roles=("AUD",)).get("/api/v1/schedules?type=backup&estado=activa")

    assert resp.status_code == 200
    body = resp.json()
    assert body["totalElements"] >= 1
    assert body["content"][0]["type"] == "backup"


def test_schedule_persisted_once(db_session: Session, make_api_client: ClientFactory) -> None:
    make_api_client(roles=("ADM",)).post(
        "/api/v1/schedules",
        json={"type": "drift-check", "cron": "*/15 * * * *", "target": {"scope": "all"}},
    )
    count = db_session.scalar(select(func.count()).select_from(Schedule))
    assert count == 1
