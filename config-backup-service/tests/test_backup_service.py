"""CRUD-01/RN-CB2/RN-CB4/RNF07-01/RES-02: respaldo de un dispositivo end-to-end (conector doble),
con producción de eventos ``config.*`` en el outbox."""

from __future__ import annotations

from pathlib import Path
from uuid import UUID, uuid4

import pytest
from app.connectors.scope import OutOfScopeError
from app.db.models import Backup, Device, OutboxEvent
from app.git_store import GitStore
from app.services.backup import backup_device
from sqlalchemy import select
from sqlalchemy.orm import Session

from tests.fakes import FakeConnector

CIDRS = "10.0.0.0/8,192.168.0.0/16"


def _seed_device(session: Session, device_id: UUID, ipv4: str = "10.0.0.5") -> None:
    session.add(Device(device_id=device_id, hostname="SW1", mgmt_ipv4=ipv4, status="ACTIVO"))
    session.commit()


def _event_types(session: Session) -> list[str]:
    return [e.event_type for e in session.execute(select(OutboxEvent)).scalars().all()]


def _store(tmp_path: Path) -> GitStore:
    return GitStore(str(tmp_path / "repo"))


def test_backup_success_records_completed_event(db_session: Session, tmp_path: Path) -> None:
    device_id = uuid4()
    _seed_device(db_session, device_id)

    backup = backup_device(
        db_session, FakeConnector(running="cfg\n", startup="cfg\n"), _store(tmp_path),
        device_id, CIDRS, actor="ada",
    )

    assert backup.status == "SUCCESS"
    assert backup.unsaved_changes is False
    assert backup.commit
    assert backup.created_by == "ada"
    assert _event_types(db_session) == ["config.backup_completed"]


def test_unsaved_changes_emits_extra_event(db_session: Session, tmp_path: Path) -> None:
    device_id = uuid4()
    _seed_device(db_session, device_id)

    backup = backup_device(
        db_session, FakeConnector(running="new\n", startup="old\n"), _store(tmp_path),
        device_id, CIDRS,
    )

    assert backup.unsaved_changes is True
    types = _event_types(db_session)
    assert "config.backup_completed" in types
    assert "config.unsaved_changes_detected" in types


def test_backup_failure_is_recorded(db_session: Session, tmp_path: Path) -> None:
    device_id = uuid4()
    _seed_device(db_session, device_id)

    backup = backup_device(
        db_session, FakeConnector(fail_times=99), _store(tmp_path),
        device_id, CIDRS, sleep=lambda _: None,
    )

    assert backup.status == "FAILED"
    assert backup.failure_reason
    assert _event_types(db_session) == ["config.backup_failed"]


def test_out_of_scope_is_rejected_without_connecting(db_session: Session, tmp_path: Path) -> None:
    device_id = uuid4()
    _seed_device(db_session, device_id, ipv4="8.8.8.8")  # fuera de los CIDRs
    connector = FakeConnector()

    with pytest.raises(OutOfScopeError):
        backup_device(db_session, connector, _store(tmp_path), device_id, CIDRS)

    assert connector.calls == 0  # nunca intentó conectar (RNF-07)
    assert db_session.execute(select(Backup)).scalars().all() == []


def test_transient_failure_is_retried(db_session: Session, tmp_path: Path) -> None:
    device_id = uuid4()
    _seed_device(db_session, device_id)
    connector = FakeConnector(fail_times=2)  # falla 2 veces, éxito al 3.er intento (RNF-10)

    backup = backup_device(
        db_session, connector, _store(tmp_path), device_id, CIDRS, sleep=lambda _: None,
    )

    assert backup.status == "SUCCESS"
    assert connector.calls == 3
