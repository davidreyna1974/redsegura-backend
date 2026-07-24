"""Endpoints de respaldo (RF-06/07/10). RBAC por operación (escritura ADM/OPE; lectura +AUD)."""

from __future__ import annotations

from collections.abc import Callable
from datetime import datetime
from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, BackgroundTasks, Depends, Header, HTTPException, Query, status
from fastapi.responses import JSONResponse
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from app.config import Settings, get_settings
from app.connectors.base import DeviceConnector
from app.connectors.scope import OutOfScopeError
from app.db.models import Backup, Device
from app.db.session import get_session
from app.deps import get_connector, get_git_store, get_job_dispatcher
from app.git_store import GitStore
from app.idempotency import Idempotency, IdempotencyConflictError, IdempotencyInProgressError
from app.schemas import (
    BackupOut,
    BackupStatus,
    BatchTarget,
    ConfigType,
    JobOut,
    PageBackup,
)
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
IdemKey = Annotated[str | None, Header(alias="Idempotency-Key")]


def begin_idempotent(idem: Idempotency) -> JSONResponse | None:
    """Inicia la ventana de idempotencia; traduce sus errores a 409 (RN-CB7)."""
    try:
        return idem.replay()
    except IdempotencyConflictError as error:
        raise HTTPException(
            status.HTTP_409_CONFLICT, "Idempotency-Key ya usada con un cuerpo distinto"
        ) from error
    except IdempotencyInProgressError as error:
        raise HTTPException(
            status.HTTP_409_CONFLICT, "Petición con esa Idempotency-Key en curso"
        ) from error


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
    idem_key: IdemKey = None,
) -> Any:
    idem = Idempotency(session, idem_key, principal.subject, f"device:{device_id}")
    replay = begin_idempotent(idem)
    if replay is not None:
        return replay
    try:
        backup = backup_device(
            session,
            connector,
            git_store,
            device_id,
            settings.allowed_scan_cidrs,
            actor=principal.subject,
        )
    except DeviceNotFoundError as error:
        idem.release()
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Dispositivo no encontrado") from error
    except NoManagementAddressError as error:
        idem.release()
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY, "El dispositivo no tiene dirección de gestión"
        ) from error
    except OutOfScopeError as error:
        idem.release()
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(error)) from error
    out = BackupOut.model_validate(backup)
    idem.store(status.HTTP_201_CREATED, out)
    return out


@router.post("/backups", status_code=status.HTTP_202_ACCEPTED, response_model=JobOut)
def backup_batch(
    target: BatchTarget,
    principal: WriteRoles,
    session: Db,
    background: BackgroundTasks,
    dispatcher: Annotated[Callable[[UUID], None], Depends(get_job_dispatcher)],
    idem_key: IdemKey = None,
) -> Any:
    idem = Idempotency(
        session, idem_key, principal.subject, target.model_dump_json(by_alias=True)
    )
    replay = begin_idempotent(idem)
    if replay is not None:
        return replay
    device_ids = resolve_targets(session, target)
    job = create_job(session, "backup", device_ids, principal.subject)
    background.add_task(dispatcher, job.job_id)
    out = to_job_out(job, [])
    idem.store(status.HTTP_202_ACCEPTED, out)
    return out


@router.get("/backups/jobs/{job_id}", response_model=JobOut)
def get_backup_job(job_id: UUID, principal: ReadRoles, session: Db) -> JobOut:
    try:
        job, results = load_job(session, job_id, "backup")
    except JobNotFoundError as error:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Job no encontrado") from error
    return to_job_out(job, results)


_SORT_FIELDS = {"capturedAt": Backup.captured_at, "status": Backup.status}


def _order_by(sort: str | None) -> Any:
    """Traduce ``sort`` del contrato (``campo,dir``) a la cláusula ORDER BY. Por defecto
    ``capturedAt,desc``. Campos desconocidos → orden por defecto (no falla)."""
    default = Backup.captured_at.desc()
    if not sort:
        return default
    field_name, _, direction = sort.partition(",")
    column = _SORT_FIELDS.get(field_name.strip())
    if column is None:
        return default
    return column.asc() if direction.strip().lower() == "asc" else column.desc()


@router.get("/backups", response_model=PageBackup)
def list_backups(
    principal: ReadRoles,
    session: Db,
    page: Annotated[int, Query(ge=0)] = 0,
    size: Annotated[int, Query(ge=1, le=200)] = 20,
    device_id: Annotated[UUID | None, Query(alias="deviceId")] = None,
    unsaved_changes: Annotated[bool | None, Query(alias="unsavedChanges")] = None,
    hostname: Annotated[str | None, Query()] = None,
    mgmt_ip: Annotated[str | None, Query(alias="mgmtIp")] = None,
    backup_status: Annotated[BackupStatus | None, Query(alias="status")] = None,
    from_: Annotated[datetime | None, Query(alias="from")] = None,
    to: Annotated[datetime | None, Query(alias="to")] = None,
    sort: Annotated[str | None, Query()] = None,
) -> PageBackup:
    stmt = select(Backup)
    if device_id is not None:
        stmt = stmt.where(Backup.device_id == device_id)
    if unsaved_changes is not None:
        stmt = stmt.where(Backup.unsaved_changes == unsaved_changes)
    if backup_status is not None:
        stmt = stmt.where(Backup.status == backup_status.value)
    if from_ is not None:
        stmt = stmt.where(Backup.captured_at >= from_)
    if to is not None:
        stmt = stmt.where(Backup.captured_at <= to)
    # hostname/mgmtIp viven en la vista de dispositivos → se resuelven a device_ids (subconsulta).
    if hostname is not None or mgmt_ip is not None:
        dev = select(Device.device_id)
        if hostname is not None:
            dev = dev.where(Device.hostname == hostname)
        if mgmt_ip is not None:
            dev = dev.where(
                or_(Device.mgmt_ipv4 == mgmt_ip, Device.mgmt_ipv6 == mgmt_ip)
            )
        stmt = stmt.where(Backup.device_id.in_(dev))
    total = session.scalar(select(func.count()).select_from(stmt.subquery())) or 0
    rows = (
        session.execute(stmt.order_by(_order_by(sort)).offset(page * size).limit(size))
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
