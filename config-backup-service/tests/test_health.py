"""HLTH-01/02/03: probes de salud sin autenticación; readiness refleja el estado de dependencias."""

from __future__ import annotations

from app.api.health import ReadinessChecks, get_readiness_checks
from app.main import create_app
from fastapi.testclient import TestClient


def test_liveness_is_up(client: TestClient) -> None:
    resp = client.get("/api/v1/health/liveness")
    assert resp.status_code == 200
    assert resp.json() == {"status": "UP"}


def test_readiness_is_up_when_no_failing_checks(client: TestClient) -> None:
    resp = client.get("/api/v1/health/readiness")
    assert resp.status_code == 200
    assert resp.json() == {"status": "UP"}


def test_readiness_is_down_when_a_check_fails() -> None:
    app = create_app()

    def failing_checks() -> ReadinessChecks:
        return [lambda: False]

    app.dependency_overrides[get_readiness_checks] = failing_checks
    with TestClient(app) as test_client:
        resp = test_client.get("/api/v1/health/readiness")
    assert resp.status_code == 503
    assert resp.json() == {"status": "DOWN"}
