"""BSRCH-01 / EMPTY-01: filtros y orden de ``GET /backups`` declarados en el contrato
(hostname, mgmtIp, deviceId, unsavedChanges, status, from, to, sort). Cubre HALLAZGO-QA-CBS-01."""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

from app.db.models import Backup, Device
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session

ClientFactory = Callable[..., TestClient]


def _device(session: Session, device_id: UUID, hostname: str, ipv4: str) -> None:
    session.add(Device(device_id=device_id, hostname=hostname, mgmt_ipv4=ipv4, status="ACTIVO"))
    session.commit()


def _backup(
    session: Session, device_id: UUID, status: str, when: datetime, unsaved: bool = False
) -> None:
    session.add(
        Backup(
            device_id=device_id,
            status=status,
            unsaved_changes=unsaved,
            captured_at=when,
            created_by="tester",
        )
    )
    session.commit()


def test_empty_history_returns_empty_page(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    # db_session garantiza el limpiado de tablas (aislamiento) aunque no se use directamente.
    resp = make_api_client(roles=("AUD",)).get("/api/v1/backups")
    assert resp.status_code == 200
    body = resp.json()
    assert body["totalElements"] == 0
    assert body["content"] == []


def test_filter_by_status(db_session: Session, make_api_client: ClientFactory) -> None:
    dev = uuid4()
    now = datetime.now(UTC)
    _backup(db_session, dev, "SUCCESS", now)
    _backup(db_session, dev, "FAILED", now)

    resp = make_api_client(roles=("AUD",)).get("/api/v1/backups?status=FAILED")

    assert resp.status_code == 200
    body = resp.json()
    assert body["totalElements"] == 1
    assert body["content"][0]["status"] == "FAILED"


def test_filter_by_date_range(db_session: Session, make_api_client: ClientFactory) -> None:
    dev = uuid4()
    now = datetime.now(UTC)
    _backup(db_session, dev, "SUCCESS", now - timedelta(days=10))  # viejo
    _backup(db_session, dev, "SUCCESS", now)  # reciente

    frm = (now - timedelta(days=2)).isoformat()
    # params= asegura el URL-encoding correcto del '+' de la zona horaria.
    resp = make_api_client(roles=("AUD",)).get("/api/v1/backups", params={"from": frm})

    assert resp.status_code == 200
    assert resp.json()["totalElements"] == 1


def test_filter_by_hostname_via_device_view(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    dev_a, dev_b = uuid4(), uuid4()
    _device(db_session, dev_a, "CORE-1", "10.0.0.1")
    _device(db_session, dev_b, "EDGE-2", "10.0.0.2")
    now = datetime.now(UTC)
    _backup(db_session, dev_a, "SUCCESS", now)
    _backup(db_session, dev_b, "SUCCESS", now)

    resp = make_api_client(roles=("AUD",)).get("/api/v1/backups?hostname=CORE-1")

    assert resp.status_code == 200
    body = resp.json()
    assert body["totalElements"] == 1
    assert body["content"][0]["deviceId"] == str(dev_a)


def test_filter_by_mgmt_ip(db_session: Session, make_api_client: ClientFactory) -> None:
    dev_a, dev_b = uuid4(), uuid4()
    _device(db_session, dev_a, "A", "10.0.0.1")
    _device(db_session, dev_b, "B", "10.0.0.2")
    now = datetime.now(UTC)
    _backup(db_session, dev_a, "SUCCESS", now)
    _backup(db_session, dev_b, "SUCCESS", now)

    resp = make_api_client(roles=("AUD",)).get("/api/v1/backups?mgmtIp=10.0.0.2")

    assert resp.json()["totalElements"] == 1
    assert resp.json()["content"][0]["deviceId"] == str(dev_b)


def test_sort_ascending(db_session: Session, make_api_client: ClientFactory) -> None:
    dev = uuid4()
    now = datetime.now(UTC)
    _backup(db_session, dev, "SUCCESS", now - timedelta(hours=1))
    _backup(db_session, dev, "FAILED", now)

    # Por defecto capturedAt,desc; con asc el más antiguo va primero.
    resp = make_api_client(roles=("AUD",)).get("/api/v1/backups?sort=capturedAt,asc")

    content = resp.json()["content"]
    assert content[0]["status"] == "SUCCESS"  # el más antiguo primero
    assert content[1]["status"] == "FAILED"


def test_unknown_sort_field_falls_back_to_default(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    dev = uuid4()
    _backup(db_session, dev, "SUCCESS", datetime.now(UTC))
    resp = make_api_client(roles=("AUD",)).get("/api/v1/backups?sort=campoInvalido,desc")
    assert resp.status_code == 200  # no falla; orden por defecto
