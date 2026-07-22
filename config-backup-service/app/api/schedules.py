"""Endpoints de programaciones recurrentes (RF-08). Crear es exclusivo de ADM; listar es de
lectura (ADM/OPE/AUD)."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.db.models import Schedule
from app.db.session import get_session
from app.schemas import (
    PageSchedule,
    ScheduleCreateRequest,
    ScheduleOut,
    ScheduleState,
    ScheduleType,
)
from app.security import Principal, require_roles
from app.services.schedules import CronInvalidError, create_schedule, to_schedule_out

router = APIRouter(tags=["schedules"])

AdminOnly = Annotated[Principal, Depends(require_roles("ADM"))]
ReadRoles = Annotated[Principal, Depends(require_roles("ADM", "OPE", "AUD"))]
Db = Annotated[Session, Depends(get_session)]


@router.post("/schedules", status_code=status.HTTP_201_CREATED, response_model=ScheduleOut)
def create_schedule_endpoint(
    body: ScheduleCreateRequest, principal: AdminOnly, session: Db
) -> ScheduleOut:
    try:
        schedule = create_schedule(session, body, principal.subject, datetime.now(UTC))
    except CronInvalidError as error:
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST, f"Expresión cron inválida: {body.cron}"
        ) from error
    return to_schedule_out(schedule)


@router.get("/schedules", response_model=PageSchedule)
def list_schedules(
    principal: ReadRoles,
    session: Db,
    page: Annotated[int, Query(ge=0)] = 0,
    size: Annotated[int, Query(ge=1, le=200)] = 20,
    type_: Annotated[ScheduleType | None, Query(alias="type")] = None,
    estado: ScheduleState | None = None,
    frecuencia: str | None = None,
    device_id: Annotated[str | None, Query(alias="deviceId")] = None,
    hostname: str | None = None,
    mgmt_ip: Annotated[str | None, Query(alias="mgmtIp")] = None,
) -> PageSchedule:
    stmt = select(Schedule)
    if type_ is not None:
        stmt = stmt.where(Schedule.type == type_.value)
    if estado is not None:
        stmt = stmt.where(Schedule.state == estado.value)
    if frecuencia:
        stmt = stmt.where(Schedule.cron.like(f"%{frecuencia}%"))
    # deviceId/hostname/mgmtIp viven dentro del objetivo serializado (target_json).
    for needle in (device_id, hostname, mgmt_ip):
        if needle:
            stmt = stmt.where(Schedule.target_json.like(f"%{needle}%"))
    total = session.scalar(select(func.count()).select_from(stmt.subquery())) or 0
    rows = (
        session.execute(stmt.order_by(Schedule.created_at.desc()).offset(page * size).limit(size))
        .scalars()
        .all()
    )
    total_pages = (total + size - 1) // size if size else 0
    return PageSchedule(
        content=[to_schedule_out(row) for row in rows],
        page=page,
        size=size,
        total_elements=total,
        total_pages=total_pages,
        first=page == 0,
        last=page >= total_pages - 1,
    )
