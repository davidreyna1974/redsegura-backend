"""Jobs por lotes (RF-08): resuelve el objetivo (``BatchTargetRequest``) contra la vista de
dispositivos, crea el job (QUEUED) y lo ejecuta dispositivo por dispositivo reutilizando la
orquestación de respaldo/drift. Cada dispositivo es su propia transacción; los contadores del job se
persisten incrementalmente para poder consultar el avance en tiempo real."""

from __future__ import annotations

import json
from uuid import UUID

from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from app.connectors.base import DeviceConnector
from app.connectors.scope import OutOfScopeError
from app.db.models import Device, Job, JobResult
from app.git_store import GitStore
from app.schemas import BatchTarget, JobOut, JobResultOut
from app.services.backup import (
    DeviceNotFoundError,
    NoManagementAddressError,
    backup_device,
)
from app.services.drift import drift_check_device


class TargetError(Exception):
    """El objetivo del lote no resuelve a ningún selector válido."""


class JobNotFoundError(Exception):
    """No existe el job indicado."""


def resolve_targets(session: Session, target: BatchTarget) -> list[UUID]:
    """Traduce un ``BatchTarget`` a la lista de ``deviceId`` de la vista local (excluye dados de
    baja cuando el selector es ``all``)."""
    if target.scope == "all":
        rows = session.scalars(select(Device.device_id).where(Device.status != "BAJA")).all()
        return list(rows)
    if target.device_ids:
        return list(target.device_ids)
    if target.filter is not None:
        stmt = select(Device.device_id)
        if target.filter.hostname:
            stmt = stmt.where(Device.hostname == target.filter.hostname)
        if target.filter.mgmt_ip:
            stmt = stmt.where(
                or_(
                    Device.mgmt_ipv4 == target.filter.mgmt_ip,
                    Device.mgmt_ipv6 == target.filter.mgmt_ip,
                )
            )
        return list(session.scalars(stmt).all())
    raise TargetError("Objetivo de lote vacío")  # pragma: no cover  (el DTO ya lo valida)


def create_job(session: Session, job_type: str, device_ids: list[UUID], actor: str) -> Job:
    """Persiste un job QUEUED con el total de dispositivos resueltos."""
    job = Job(
        type=job_type,
        status="QUEUED",
        total=len(device_ids),
        completed=0,
        failed=0,
        targets_json=json.dumps([str(d) for d in device_ids]),
        created_by=actor,
    )
    session.add(job)
    session.commit()
    session.refresh(job)
    return job


def _process_one(
    session: Session,
    job_type: str,
    connector: DeviceConnector,
    git_store: GitStore,
    device_id: UUID,
    allowed_cidrs: str,
    actor: str,
) -> tuple[str, str | None]:
    try:
        if job_type == "backup":
            backup = backup_device(
                session, connector, git_store, device_id, allowed_cidrs, actor=actor
            )
            return backup.status, backup.failure_reason
        result = drift_check_device(
            session, connector, git_store, device_id, allowed_cidrs, actor=actor
        )
        return ("DRIFT" if result.drift else "OK"), result.drift_ref
    except (DeviceNotFoundError, NoManagementAddressError, OutOfScopeError) as error:
        return "FAILED", str(error)


def run_job(
    session: Session,
    job_id: UUID,
    connector: DeviceConnector,
    git_store: GitStore,
    allowed_cidrs: str,
) -> Job:
    """Ejecuta el job completo: procesa cada dispositivo, registra su resultado y actualiza los
    contadores. Un fallo por dispositivo se contabiliza pero no aborta el lote."""
    job = session.get(Job, job_id)
    if job is None:
        raise JobNotFoundError(str(job_id))
    job_type = job.type
    actor = job.created_by
    device_ids = [UUID(x) for x in json.loads(job.targets_json)]
    job.status = "IN_PROGRESS"
    session.commit()

    for device_id in device_ids:
        outcome, detail = _process_one(
            session, job_type, connector, git_store, device_id, allowed_cidrs, actor
        )
        session.add(
            JobResult(job_id=job_id, device_id=device_id, outcome=outcome, detail=detail)
        )
        job = session.get(Job, job_id)
        assert job is not None
        if outcome == "FAILED":
            job.failed += 1
        else:
            job.completed += 1
        session.commit()

    job = session.get(Job, job_id)
    assert job is not None
    job.status = "COMPLETED"
    session.commit()
    session.refresh(job)
    return job


def load_job(session: Session, job_id: UUID, expected_type: str) -> tuple[Job, list[JobResult]]:
    """Carga un job (validando su tipo) junto con sus resultados. Lanza ``JobNotFoundError`` si no
    existe o si el tipo no coincide (un jobId de drift no es válido en la ruta de backups)."""
    job = session.get(Job, job_id)
    if job is None or job.type != expected_type:
        raise JobNotFoundError(str(job_id))
    results = list(
        session.scalars(select(JobResult).where(JobResult.job_id == job_id)).all()
    )
    return job, results


def to_job_out(job: Job, results: list[JobResult]) -> JobOut:
    """Serializa un job (+ resultados) al DTO del contrato."""
    return JobOut(
        job_id=job.job_id,
        type=job.type,
        status=job.status,
        total=job.total,
        completed=job.completed,
        failed=job.failed,
        results=[JobResultOut.model_validate(r) for r in results],
    )
