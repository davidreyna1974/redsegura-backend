"""RNF (retención): la purga elimina datos operativos antiguos (eventos procesados, outbox
publicado, jobs terminados + resultados) y **conserva** el historial de respaldos."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

from app.db.models import Backup, Job, JobResult, OutboxEvent, ProcessedEvent
from app.services.retention import purge_old
from sqlalchemy import func, select
from sqlalchemy.orm import Session

NOW = datetime(2026, 7, 22, 12, 0, tzinfo=UTC)
OLD = NOW - timedelta(days=120)
RECENT = NOW - timedelta(days=1)


def test_purge_removes_old_operational_data_keeps_recent_and_backups(
    db_session: Session,
) -> None:
    old_job = Job(
        type="backup", status="COMPLETED", total=1, completed=1, failed=0,
        targets_json="[]", created_at=OLD, created_by="tester",
    )
    db_session.add(old_job)
    db_session.flush()
    db_session.add(JobResult(job_id=old_job.job_id, device_id=uuid4(), outcome="SUCCESS"))
    # Recientes / a conservar.
    db_session.add(
        Job(type="backup", status="COMPLETED", total=0, completed=0, failed=0,
            targets_json="[]", created_at=RECENT, created_by="tester")
    )
    db_session.add(ProcessedEvent(event_id="old-evt", processed_at=OLD))
    db_session.add(ProcessedEvent(event_id="new-evt", processed_at=RECENT))
    db_session.add(
        OutboxEvent(event_type="config.backup_completed", payload="{}",
                    created_at=OLD, published_at=OLD)
    )
    # Outbox antiguo pero NO publicado → no se purga (aún pendiente).
    db_session.add(
        OutboxEvent(event_type="config.backup_completed", payload="{}",
                    created_at=OLD, published_at=None)
    )
    # Respaldo antiguo → SIEMPRE se conserva (historial).
    db_session.add(
        Backup(device_id=uuid4(), status="SUCCESS", unsaved_changes=False,
               captured_at=OLD, created_by="tester")
    )
    db_session.commit()

    counts = purge_old(db_session, NOW, retention_days=90)

    assert counts == {
        "jobs": 1, "job_results": 1, "processed_events": 1, "outbox_events": 1
    }
    assert db_session.scalar(select(func.count()).select_from(Job)) == 1
    assert db_session.scalar(select(func.count()).select_from(ProcessedEvent)) == 1
    assert db_session.scalar(select(func.count()).select_from(OutboxEvent)) == 1  # el no publicado
    assert db_session.scalar(select(func.count()).select_from(Backup)) == 1  # conservado


def test_purge_noop_when_all_recent(db_session: Session) -> None:
    db_session.add(ProcessedEvent(event_id="e", processed_at=RECENT))
    db_session.commit()

    counts = purge_old(db_session, NOW, retention_days=90)

    assert counts["processed_events"] == 0
