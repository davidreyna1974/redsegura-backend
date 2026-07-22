"""Endpoints de respaldo (RF-06/07/10). RBAC por operación (escritura ADM/OPE; lectura +AUD)."""

from __future__ import annotations

from collections.abc import Callable
from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.config import Settings, get_settings
from app.connectors.base import DeviceConnector
from app.connectors.scope import OutOfScopeError
from app.db.models import Backup
from app.db.session import get_session
from app.deps import get_connector, get_git_store, get_job_dispatcher
from app.git_store import GitStore
from app.schemas import BackupOut, BatchTarget, ConfigType, JobOut, PageBackup
from app.security import Principal, require_roles
from app.services.backup import (
    DeviceNotFoundError,
    NoManagementAddressError,
    backup_device,
)
from app.services.jobs import (
    JobNotFoundError,
    create_job,
    load_job,
    resolve_targets,
    to_job_out,
)

router = APIRouter(tags=["backups"])

WriteRoles = Annotated[Principal, Depends(require_roles("ADM", "OPE"))]
ReadRoles = Annotated[Principal, Depends(require_roles("ADM", "OPE", "AUD"))]
Db = Annotated[Session, Depends(get_session)]


@router.post(
    "/devices/{device_id}/backups", status_code=status.HTTP_201_CREATED, response_model=BackupOut
)
def create_backup(
    device_id: UUID,
    principal: WriteRoles,
    session: Db,
    connector: Annotated[DeviceConnector, Depends(get_connector)],
    git_store: Annotated[GitStore, Depends(get_git_store)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> Backup:
    try:
        return backup_device(
            session,
            connector,
            git_store,
            device_id,
            settings.allowed_scan_cidrs,
            actor=principal.subject,
        )
    except DeviceNotFoundError as error:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Dispositivo no encontrado") from error
    except NoManagementAddressError as error:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY, "El dispositivo no tiene dirección de gestión"
        ) from error
    except OutOfScopeError as error:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(error)) from error


@router.post("/backups", status_code=status.HTTP_202_ACCEPTED, response_model=JobOut)
def backup_batch(
    target: BatchTarget,
    principal: WriteRoles,
    session: Db,
    background: BackgroundTasks,
    dispatcher: Annotated[Callable[[UUID], None], Depends(get_job_dispatcher)],
) -> JobOut:
    device_ids = resolve_targets(session, target)
    job = create_job(session, "backup", device_ids, principal.subject)
    background.add_task(dispatcher, job.job_id)
    return to_job_out(job, [])


@router.get("/backups/jobs/{job_id}", response_model=JobOut)
def get_backup_job(job_id: UUID, principal: ReadRoles, session: Db) -> JobOut:
    try:
        job, results = load_job(session, job_id, "backup")
    except JobNotFoundError as error:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Job no encontrado") from error
    return to_job_out(job, results)


@router.get("/backups", response_model=PageBackup)
def list_backups(
    principal: ReadRoles,
    session: Db,
    page: Annotated[int, Query(ge=0)] = 0,
    size: Annotated[int, Query(ge=1, le=200)] = 20,
    device_id: Annotated[UUID | None, Query(alias="deviceId")] = None,
    unsaved_changes: Annotated[bool | None, Query(alias="unsavedChanges")] = None,
) -> PageBackup:
    stmt = select(Backup)
    if device_id is not None:
        stmt = stmt.where(Backup.device_id == device_id)
    if unsaved_changes is not None:
        stmt = stmt.where(Backup.unsaved_changes == unsaved_changes)
    total = session.scalar(select(func.count()).select_from(stmt.subquery())) or 0
    rows = (
        session.execute(stmt.order_by(Backup.captured_at.desc()).offset(page * size).limit(size))
        .scalars()
        .all()
    )
    total_pages = (total + size - 1) // size if size else 0
    return PageBackup(
        content=[BackupOut.model_validate(row) for row in rows],
        page=page,
        size=size,
        total_elements=total,
        total_pages=total_pages,
        first=page == 0,
        last=page >= total_pages - 1,
    )


@router.get("/backups/{backup_id}", response_model=BackupOut)
def get_backup(backup_id: UUID, principal: ReadRoles, session: Db) -> Backup:
    backup = session.get(Backup, backup_id)
    if backup is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Respaldo no encontrado")
    return backup


@router.get("/devices/{device_id}/backups/diff")
def diff_backups(
    device_id: UUID,
    principal: ReadRoles,
    git_store: Annotated[GitStore, Depends(get_git_store)],
    from_ref: Annotated[str, Query(alias="from")],
    to_ref: Annotated[str, Query(alias="to")],
    config_type: Annotated[ConfigType, Query(alias="configType")] = ConfigType.RUNNING,
) -> dict[str, Any]:
    try:
        diff = git_store.diff(str(device_id), from_ref, to_ref, config_type.value)
    except Exception as error:  # noqa: BLE001  (referencia de versión inválida)
        message = "Referencias de versión inválidas"
        raise HTTPException(status.HTTP_400_BAD_REQUEST, message) from error
    return {
        "deviceId": str(device_id),
        "from": from_ref,
        "to": to_ref,
        "configType": config_type.value,
        "diff": diff,
    }
