"""CRUD-01/SEC-01/02/CRUD-02/03/ERR-01/02/RNF07-01: endpoints de respaldo con RBAC (RNF-04)."""

from __future__ import annotations

from collections.abc import Callable
from uuid import UUID, uuid4

from app.db.models import Device
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session

from tests.fakes import FakeConnector

ClientFactory = Callable[..., TestClient]


def _seed(session: Session, device_id: UUID, ipv4: str = "10.0.0.5") -> None:
    session.add(Device(device_id=device_id, hostname="SW1", mgmt_ipv4=ipv4, status="ACTIVO"))
    session.commit()


def test_post_backup_as_admin_returns_201(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    device_id = uuid4()
    _seed(db_session, device_id)
    client = make_api_client(roles=("ADM",), connector=FakeConnector(running="c\n", startup="c\n"))

    resp = client.post(f"/api/v1/devices/{device_id}/backups")

    assert resp.status_code == 201
    body = resp.json()
    assert body["status"] == "SUCCESS"
    assert body["deviceId"] == str(device_id)
    assert body["unsavedChanges"] is False
    assert body["createdBy"] == "tester"


def test_post_backup_without_token_is_401(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    device_id = uuid4()
    _seed(db_session, device_id)
    client = make_api_client(roles=None)  # sin override → dependencia real

    resp = client.post(f"/api/v1/devices/{device_id}/backups")

    assert resp.status_code == 401
    assert resp.headers["content-type"].startswith("application/problem+json")


def test_post_backup_as_auditor_is_403(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    device_id = uuid4()
    _seed(db_session, device_id)
    client = make_api_client(roles=("AUD",))

    resp = client.post(f"/api/v1/devices/{device_id}/backups")

    assert resp.status_code == 403


def test_post_backup_out_of_scope_is_422(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    device_id = uuid4()
    _seed(db_session, device_id, ipv4="8.8.8.8")  # fuera de los CIDRs
    client = make_api_client(roles=("ADM",))

    resp = client.post(f"/api/v1/devices/{device_id}/backups")

    assert resp.status_code == 422


def test_post_backup_unknown_device_is_404(make_api_client: ClientFactory) -> None:
    client = make_api_client(roles=("OPE",))

    resp = client.post(f"/api/v1/devices/{uuid4()}/backups")

    assert resp.status_code == 404


def test_list_backups_as_auditor(db_session: Session, make_api_client: ClientFactory) -> None:
    device_id = uuid4()
    _seed(db_session, device_id)
    admin = make_api_client(roles=("ADM",), connector=FakeConnector(running="c\n", startup="c\n"))
    admin.post(f"/api/v1/devices/{device_id}/backups")

    resp = make_api_client(roles=("AUD",)).get("/api/v1/backups")

    assert resp.status_code == 200
    body = resp.json()
    assert body["totalElements"] >= 1
    assert body["content"][0]["deviceId"] == str(device_id)


def test_get_backup_not_found(make_api_client: ClientFactory) -> None:
    resp = make_api_client(roles=("AUD",)).get(f"/api/v1/backups/{uuid4()}")

    assert resp.status_code == 404
