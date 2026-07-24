"""Orquestación del respaldo de un dispositivo (RF-06/07/10). Conecta por SSH (dentro del alcance
autorizado, RNF-07; con reintentos, RNF-10), captura running/startup, calcula ``unsavedChanges``,
versiona en Git (RF-07), persiste el metadato y produce eventos ``config.*`` vía outbox."""

from __future__ import annotations

import time
from collections.abc import Callable
from datetime import UTC, datetime
from uuid import UUID, uuid4

from sqlalchemy.orm import Session

from app.connectors.base import BackupConnectionError, DeviceConnector
from app.connectors.resilience import with_retries
from app.connectors.scope import assert_in_scope
from app.db.models import Backup, Device
from app.git_store import GitStore
from app.messaging.outbox import OutboxWriter
from app.observability import BACKUPS_TOTAL


class DeviceNotFoundError(Exception):
    """El dispositivo no existe en la vista local."""


class NoManagementAddressError(Exception):
    """El dispositivo no tiene dirección de gestión para conectarse."""


def backup_device(
    session: Session,
    connector: DeviceConnector,
    git_store: GitStore,
    device_id: UUID,
    allowed_cidrs: str,
    *,
    actor: str = "scheduler",
    outbox: OutboxWriter | None = None,
    sleep: Callable[[float], None] = time.sleep,
) -> Backup:
    writer = outbox or OutboxWriter()
    device = session.get(Device, device_id)
    if device is None:
        raise DeviceNotFoundError(str(device_id))
    host = device.mgmt_ipv4 or device.mgmt_ipv6
    if host is None:
        raise NoManagementAddressError(str(device_id))

    # RNF-07: rechaza fuera de alcance ANTES de intentar conectar.
    assert_in_scope(host, allowed_cidrs)

    backup_id = uuid4()
    captured_at = datetime.now(UTC)
    try:
        config = with_retries(
            lambda: connector.fetch_config(host), retry_on=BackupConnectionError, sleep=sleep
        )
    except BackupConnectionError as error:  # RF-10: se registra el fallo
        backup = Backup(
            backup_id=backup_id,
            device_id=device_id,
            captured_at=captured_at,
            status="FAILED",
            failure_reason=str(error),
            unsaved_changes=False,
            created_by=actor,
        )
        session.add(backup)
        # Payload conforme al catálogo §4.3 (config-event.schema.json).
        writer.record(
            session,
            "config.backup_failed",
            {
                "deviceId": str(device_id),
                "attemptedAt": captured_at.isoformat(),
                "reason": str(error),
            },
        )
        session.commit()
        BACKUPS_TOTAL.labels("FAILED").inc()
        return backup

    unsaved = config.running.strip() != config.startup.strip()
    commit = git_store.save_config(
        str(device_id), config.running, config.startup, f"backup {device_id}"
    )
    backup = Backup(
        backup_id=backup_id,
        device_id=device_id,
        commit=commit,
        captured_at=captured_at,
        unsaved_changes=unsaved,
        status="SUCCESS",
        created_by=actor,
    )
    session.add(backup)
    # Payload conforme al catálogo §4.2 (config-event.schema.json).
    writer.record(
        session,
        "config.backup_completed",
        {
            "deviceId": str(device_id),
            "backupId": str(backup_id),
            "commit": commit,
            "capturedAt": captured_at.isoformat(),
            "unsavedChanges": unsaved,
            "status": "SUCCESS",
        },
    )
    if unsaved:  # ADR-02: running != startup — payload conforme al catálogo §4.5
        writer.record(
            session,
            "config.unsaved_changes_detected",
            {
                "deviceId": str(device_id),
                "backupId": str(backup_id),
                "runningVsStartupDiffRef": f"diff:running-vs-startup@{commit}",
                "detectedAt": captured_at.isoformat(),
            },
        )
    session.commit()
    BACKUPS_TOTAL.labels("SUCCESS").inc()
    return backup
