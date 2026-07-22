"""Endpoint de drift-check individual (RF-09). Escritura: ADM/OPE."""

from __future__ import annotations

from collections.abc import Callable
from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.config import Settings, get_settings
from app.connectors.base import DeviceConnector
from app.connectors.scope import OutOfScopeError
from app.db.session import get_session
from app.deps import get_connector, get_git_store, get_job_dispatcher
from app.git_store import GitStore
from app.schemas import BatchTarget, JobOut
from app.security import Principal, require_roles
from app.services.backup import DeviceNotFoundError, NoManagementAddressError
from app.services.drift import drift_check_device
from app.services.jobs import (
    JobNotFoundError,
    create_job,
    load_job,
    resolve_targets,
    to_job_out,
)

router = APIRouter(tags=["drift"])

WriteRoles = Annotated[Principal, Depends(require_roles("ADM", "OPE"))]
ReadRoles = Annotated[Principal, Depends(require_roles("ADM", "OPE", "AUD"))]
Db = Annotated[Session, Depends(get_session)]


@router.post("/devices/{device_id}/drift-check")
def drift_check(
    device_id: UUID,
    principal: WriteRoles,
    session: Annotated[Session, Depends(get_session)],
    connector: Annotated[DeviceConnector, Depends(get_connector)],
    git_store: Annotated[GitStore, Depends(get_git_store)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> dict[str, Any]:
    try:
        result = drift_check_device(
            session,
            connector,
            git_store,
            device_id,
            settings.allowed_scan_cidrs,
            actor=principal.subject,
        )
    except DeviceNotFoundError as error:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Dispositivo no encontrado") from error
    except (NoManagementAddressError, OutOfScopeError) as error:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(error)) from error
    return {
        "deviceId": str(result.device_id),
        "drift": result.drift,
        "driftRef": result.drift_ref,
        "checkedAt": result.checked_at.isoformat(),
    }


@router.post("/drift-checks", status_code=status.HTTP_202_ACCEPTED, response_model=JobOut)
def drift_check_batch(
    target: BatchTarget,
    principal: WriteRoles,
    session: Db,
    background: BackgroundTasks,
    dispatcher: Annotated[Callable[[UUID], None], Depends(get_job_dispatcher)],
) -> JobOut:
    device_ids = resolve_targets(session, target)
    job = create_job(session, "drift-check", device_ids, principal.subject)
    background.add_task(dispatcher, job.job_id)
    return to_job_out(job, [])


@router.get("/drift-checks/jobs/{job_id}", response_model=JobOut)
def get_drift_job(job_id: UUID, principal: ReadRoles, session: Db) -> JobOut:
    try:
        job, results = load_job(session, job_id, "drift-check")
    except JobNotFoundError as error:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Job no encontrado") from error
    return to_job_out(job, results)
