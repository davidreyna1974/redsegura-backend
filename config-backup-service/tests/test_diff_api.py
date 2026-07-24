"""CRUD-04 / RN-CB3b / VAL-02: endpoint ``GET /devices/{id}/backups/diff`` (RF-07). Compara dos
versiones (commits) del repo Git; soporta running (defecto) y startup; refs inválidas → 400."""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
from uuid import uuid4

from app.git_store import GitStore
from fastapi.testclient import TestClient

ClientFactory = Callable[..., TestClient]


def test_diff_running_between_two_versions(
    make_api_client: ClientFactory, tmp_path: Path
) -> None:
    device_id = uuid4()
    # El endpoint usa GitStore(tmp_path/"repo") (override del fixture); sembramos ahí 2 commits.
    store = GitStore(str(tmp_path / "repo"))
    c1 = store.save_config(str(device_id), "hostname R1\n", "hostname R1\n", "b1")
    c2 = store.save_config(str(device_id), "hostname R2\n", "hostname R1\n", "b2")

    resp = make_api_client(roles=("AUD",)).get(
        f"/api/v1/devices/{device_id}/backups/diff?from={c1}&to={c2}"
    )

    assert resp.status_code == 200
    body = resp.json()
    assert body["configType"] == "running"
    assert "R2" in body["diff"]  # el cambio aparece en el diff unificado


def test_diff_startup_config_type(
    make_api_client: ClientFactory, tmp_path: Path
) -> None:
    device_id = uuid4()
    store = GitStore(str(tmp_path / "repo"))
    c1 = store.save_config(str(device_id), "run\n", "startup A\n", "b1")
    c2 = store.save_config(str(device_id), "run\n", "startup B\n", "b2")

    resp = make_api_client(roles=("AUD",)).get(
        f"/api/v1/devices/{device_id}/backups/diff?from={c1}&to={c2}&configType=startup"
    )

    assert resp.status_code == 200
    body = resp.json()
    assert body["configType"] == "startup"
    assert "startup B" in body["diff"]


def test_diff_invalid_refs_is_400(make_api_client: ClientFactory) -> None:
    device_id = uuid4()
    resp = make_api_client(roles=("AUD",)).get(
        f"/api/v1/devices/{device_id}/backups/diff?from=NOPE~1&to=ALSONOPE"
    )
    assert resp.status_code == 400
    assert resp.headers["content-type"].startswith("application/problem+json")
