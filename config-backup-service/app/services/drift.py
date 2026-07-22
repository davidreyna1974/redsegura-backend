"""Detección de drift (RF-09): compara la ``running-config`` en vivo con el contenido del último
respaldo conocido; si difieren, emite ``config.drift_detected`` vía outbox."""

from __future__ import annotations

import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.connectors.base import BackupConnectionError, DeviceConnector
from app.connectors.resilience import with_retries
from app.connectors.scope import assert_in_scope
from app.db.models import Backup, Device
from app.git_store import GitStore
from app.messaging.outbox import OutboxWriter
from app.observability import DRIFT_CHECKS_TOTAL
from app.services.backup import DeviceNotFoundError, NoManagementAddressError


@dataclass(frozen=True)
class DriftResult:
    device_id: UUID
    drift: bool
    drift_ref: str | None
    checked_at: datetime


def _last_successful_backup(session: Session, device_id: UUID) -> Backup | None:
    stmt = (
        select(Backup)
        .where(
            Backup.device_id == device_id,
            Backup.status == "SUCCESS",
            Backup.commit.is_not(None),
        )
        .order_by(Backup.captured_at.desc())
        .limit(1)
    )
    return session.scalars(stmt).first()


def drift_check_device(
    session: Session,
    connector: DeviceConnector,
    git_store: GitStore,
    device_id: UUID,
    allowed_cidrs: str,
    *,
    actor: str = "scheduler",
    outbox: OutboxWriter | None = None,
    sleep: Callable[[float], None] = time.sleep,
) -> DriftResult:
    writer = outbox or OutboxWriter()
    device = session.get(Device, device_id)
    if device is None:
        raise DeviceNotFoundError(str(device_id))
    host = device.mgmt_ipv4 or device.mgmt_ipv6
    if host is None:
        raise NoManagementAddressError(str(device_id))
    assert_in_scope(host, allowed_cidrs)  # RNF-07

    checked_at = datetime.now(UTC)
    baseline = _last_successful_backup(session, device_id)
    if baseline is None or baseline.commit is None:
        # Sin línea base no hay con qué comparar → no se declara drift.
        DRIFT_CHECKS_TOTAL.labels("no_baseline").inc()
        return DriftResult(device_id, drift=False, drift_ref=None, checked_at=checked_at)

    live = with_retries(
        lambda: connector.fetch_config(host), retry_on=BackupConnectionError, sleep=sleep
    )
    stored = git_store.read_config(str(device_id), baseline.commit, "running")
    drift = live.running.strip() != stored.strip()
    if drift:
        writer.record(
            session,
            "config.drift_detected",
            {
                "deviceId": str(device_id),
                "driftRef": baseline.commit,
                "checkedAt": checked_at.isoformat(),
            },
        )
        session.commit()
    DRIFT_CHECKS_TOTAL.labels("drift" if drift else "no_drift").inc()
    return DriftResult(
        device_id, drift=drift, drift_ref=baseline.commit if drift else None, checked_at=checked_at
    )
