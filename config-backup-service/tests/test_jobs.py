"""FLOW/RN/SEC/VAL/ERR: jobs por lotes de respaldo y drift (RF-08) — servicio y endpoints."""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
from uuid import UUID, uuid4

from app.db.models import Device
from app.git_store import GitStore
from app.schemas import BatchFilter, BatchTarget
from app.services.jobs import create_job, resolve_targets, run_job
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session

from tests.fakes import FakeConnector

CIDRS = "10.0.0.0/8,192.168.0.0/16"
ClientFactory = Callable[..., TestClient]


def _seed(
    session: Session, device_id: UUID, ipv4: str = "10.0.0.5", status: str = "ACTIVO"
) -> None:
    session.add(Device(device_id=device_id, hostname="SW1", mgmt_ipv4=ipv4, status=status))
    session.commit()


def _store(tmp_path: Path) -> GitStore:
    return GitStore(str(tmp_path / "repo"))


# --- resolve_targets (RN) ---


def test_resolve_scope_all_excludes_decommissioned(db_session: Session) -> None:
    active = uuid4()
    dado_de_baja = uuid4()
    _seed(db_session, active)
    _seed(db_session, dado_de_baja, ipv4="10.0.0.6", status="BAJA")

    resolved = resolve_targets(db_session, BatchTarget(scope="all"))

    assert resolved == [active]


def test_resolve_by_device_ids(db_session: Session) -> None:
    ids = [uuid4(), uuid4()]
    resolved = resolve_targets(db_session, BatchTarget(device_ids=ids))
    assert resolved == ids


def test_resolve_by_filter_hostname(db_session: Session) -> None:
    device_id = uuid4()
    db_session.add(
        Device(device_id=device_id, hostname="CORE-1", mgmt_ipv4="10.0.0.9", status="ACTIVO")
    )
    db_session.commit()

    resolved = resolve_targets(db_session, BatchTarget(filter=BatchFilter(hostname="CORE-1")))

    assert resolved == [device_id]


# --- run_job (FLOW) ---


def test_run_backup_job_mixed_results(db_session: Session, tmp_path: Path) -> None:
    ok = uuid4()
    out_of_scope = uuid4()
    missing = uuid4()
    _seed(db_session, ok)
    _seed(db_session, out_of_scope, ipv4="8.8.8.8")  # fuera de alcance → FAILED
    job = create_job(db_session, "backup", [ok, out_of_scope, missing], "tester")

    result = run_job(db_session, job.job_id, FakeConnector(), _store(tmp_path), CIDRS)

    assert result.status == "COMPLETED"
    assert result.total == 3
    assert result.completed == 1
    assert result.failed == 2


def test_run_drift_job(db_session: Session, tmp_path: Path) -> None:
    device_id = uuid4()
    _seed(db_session, device_id)
    job = create_job(db_session, "drift-check", [device_id], "tester")

    result = run_job(db_session, job.job_id, FakeConnector(), _store(tmp_path), CIDRS)

    assert result.status == "COMPLETED"
    assert result.completed == 1  # sin línea base → OK (no drift), pero procesado


# --- API (SEC/FLOW/VAL/ERR) ---


def test_backup_batch_end_to_end(db_session: Session, make_api_client: ClientFactory) -> None:
    device_id = uuid4()
    _seed(db_session, device_id)
    client = make_api_client(roles=("ADM",), connector=FakeConnector(running="c\n", startup="c\n"))

    resp = client.post("/api/v1/backups", json={"deviceIds": [str(device_id)]})

    assert resp.status_code == 202
    job_id = resp.json()["jobId"]
    assert resp.json()["status"] in {"QUEUED", "IN_PROGRESS", "COMPLETED"}

    # La BackgroundTask ya corrió (síncrona en TestClient): el job está COMPLETED.
    job = client.get(f"/api/v1/backups/jobs/{job_id}").json()
    assert job["status"] == "COMPLETED"
    assert job["total"] == 1
    assert job["completed"] == 1
    assert job["results"][0]["deviceId"] == str(device_id)


def test_backup_batch_as_auditor_is_403(make_api_client: ClientFactory) -> None:
    resp = make_api_client(roles=("AUD",)).post("/api/v1/backups", json={"scope": "all"})
    assert resp.status_code == 403


def test_batch_invalid_target_is_422(make_api_client: ClientFactory) -> None:
    # Ni un selector → viola "exactamente uno" (validación del DTO).
    resp = make_api_client(roles=("ADM",)).post("/api/v1/backups", json={})
    assert resp.status_code == 422


def test_batch_two_selectors_is_422(make_api_client: ClientFactory) -> None:
    resp = make_api_client(roles=("ADM",)).post(
        "/api/v1/backups", json={"scope": "all", "deviceIds": [str(uuid4())]}
    )
    assert resp.status_code == 422


def test_get_backup_job_not_found(make_api_client: ClientFactory) -> None:
    resp = make_api_client(roles=("AUD",)).get(f"/api/v1/backups/jobs/{uuid4()}")
    assert resp.status_code == 404


def test_drift_job_id_not_valid_on_backup_route(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    # Un jobId de tipo drift no debe resolver en la ruta de backups (aislamiento de tipo).
    job = create_job(db_session, "drift-check", [], "tester")
    resp = make_api_client(roles=("AUD",)).get(f"/api/v1/backups/jobs/{job.job_id}")
    assert resp.status_code == 404


def test_drift_batch_end_to_end(db_session: Session, make_api_client: ClientFactory) -> None:
    device_id = uuid4()
    _seed(db_session, device_id)
    client = make_api_client(roles=("OPE",))

    resp = client.post("/api/v1/drift-checks", json={"scope": "all"})

    assert resp.status_code == 202
    job_id = resp.json()["jobId"]
    job = client.get(f"/api/v1/drift-checks/jobs/{job_id}").json()
    assert job["type"] == "drift-check"
    assert job["status"] == "COMPLETED"
    assert job["total"] == 1
