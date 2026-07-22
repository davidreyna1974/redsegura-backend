"""RN-CB5/DRIFT-02/EVT-OUT-03: detección de drift (RF-09) — servicio y endpoint."""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
from uuid import UUID, uuid4

from app.db.models import Device, OutboxEvent
from app.git_store import GitStore
from app.services.backup import backup_device
from app.services.drift import drift_check_device
from fastapi.testclient import TestClient
from sqlalchemy import select
from sqlalchemy.orm import Session

from tests.fakes import FakeConnector

CIDRS = "10.0.0.0/8,192.168.0.0/16"
ClientFactory = Callable[..., TestClient]


def _seed(session: Session, device_id: UUID, ipv4: str = "10.0.0.5") -> None:
    session.add(Device(device_id=device_id, hostname="SW1", mgmt_ipv4=ipv4, status="ACTIVO"))
    session.commit()


def _event_types(session: Session) -> list[str]:
    return [e.event_type for e in session.execute(select(OutboxEvent)).scalars().all()]


def _store(tmp_path: Path) -> GitStore:
    return GitStore(str(tmp_path / "repo"))


def test_drift_detected_emits_event(db_session: Session, tmp_path: Path) -> None:
    device_id = uuid4()
    _seed(db_session, device_id)
    store = _store(tmp_path)
    baseline = FakeConnector(running="old\n", startup="old\n")
    backup_device(db_session, baseline, store, device_id, CIDRS)

    result = drift_check_device(
        db_session,
        FakeConnector(running="NEW\n", startup="NEW\n"),
        store,
        device_id,
        CIDRS,
        sleep=lambda _: None,
    )

    assert result.drift is True
    assert result.drift_ref
    assert "config.drift_detected" in _event_types(db_session)


def test_no_drift_when_config_matches(db_session: Session, tmp_path: Path) -> None:
    device_id = uuid4()
    _seed(db_session, device_id)
    store = _store(tmp_path)
    same = FakeConnector(running="same\n", startup="same\n")
    backup_device(db_session, same, store, device_id, CIDRS)

    result = drift_check_device(
        db_session, FakeConnector(running="same\n", startup="same\n"), store, device_id, CIDRS
    )

    assert result.drift is False
    assert "config.drift_detected" not in _event_types(db_session)


def test_no_baseline_reports_no_drift(db_session: Session, tmp_path: Path) -> None:
    device_id = uuid4()
    _seed(db_session, device_id)

    result = drift_check_device(db_session, FakeConnector(), _store(tmp_path), device_id, CIDRS)

    assert result.drift is False
    assert result.drift_ref is None


def test_drift_check_endpoint_detects_change(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    device_id = uuid4()
    _seed(db_session, device_id)
    baseline = FakeConnector(running="old\n", startup="old\n")
    admin = make_api_client(roles=("ADM",), connector=baseline)
    admin.post(f"/api/v1/devices/{device_id}/backups")

    ope = make_api_client(roles=("OPE",), connector=FakeConnector(running="NEW\n", startup="NEW\n"))
    resp = ope.post(f"/api/v1/devices/{device_id}/drift-check")

    assert resp.status_code == 200
    assert resp.json()["drift"] is True


def test_drift_check_forbidden_for_auditor(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    device_id = uuid4()
    _seed(db_session, device_id)

    resp = make_api_client(roles=("AUD",)).post(f"/api/v1/devices/{device_id}/drift-check")

    assert resp.status_code == 403
