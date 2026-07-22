"""Retención de datos operativos: purga filas efímeras antiguas para acotar el crecimiento de la BD.
Solo se purgan datos **operativos** (eventos procesados, outbox ya publicado, jobs terminados y sus
resultados); **nunca** el historial de respaldos (``backups``) ni las configuraciones versionadas en
Git, que son la memoria del servicio."""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Any, cast

from sqlalchemy import CursorResult, delete, select
from sqlalchemy.orm import Session
from sqlalchemy.sql import Delete

from app.db.models import Job, JobResult, OutboxEvent, ProcessedEvent


def _delete_count(session: Session, stmt: Delete) -> int:
    return cast("CursorResult[Any]", session.execute(stmt)).rowcount


def purge_old(session: Session, now: datetime, retention_days: int) -> dict[str, int]:
    """Elimina filas efímeras previas a ``now - retention_days``. Devuelve el conteo por tabla."""
    cutoff = now - timedelta(days=retention_days)

    old_jobs = list(
        session.scalars(select(Job.job_id).where(Job.created_at < cutoff)).all()
    )
    job_results = 0
    if old_jobs:
        job_results = _delete_count(
            session, delete(JobResult).where(JobResult.job_id.in_(old_jobs))
        )
        session.execute(delete(Job).where(Job.job_id.in_(old_jobs)))

    processed = _delete_count(
        session, delete(ProcessedEvent).where(ProcessedEvent.processed_at < cutoff)
    )
    outbox = _delete_count(
        session,
        delete(OutboxEvent).where(
            OutboxEvent.published_at.is_not(None), OutboxEvent.published_at < cutoff
        ),
    )
    session.commit()
    return {
        "jobs": len(old_jobs),
        "job_results": job_results,
        "processed_events": processed,
        "outbox_events": outbox,
    }
