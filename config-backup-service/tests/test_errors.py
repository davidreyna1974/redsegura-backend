"""ERR: los errores salen en application/problem+json (RFC 7807/ADR-08), sin filtrar internos."""

from __future__ import annotations

import asyncio

from app.errors import PROBLEM_MEDIA_TYPE, _validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.testclient import TestClient
from starlette.requests import Request


def test_unknown_route_returns_problem_json(client: TestClient) -> None:
    resp = client.get("/api/v1/no-existe")
    assert resp.status_code == 404
    assert resp.headers["content-type"].startswith(PROBLEM_MEDIA_TYPE)
    body = resp.json()
    assert body["status"] == 404
    assert body["title"] == "No encontrado"
    assert body["instance"] == "/api/v1/no-existe"


def test_validation_handler_produces_problem_json() -> None:
    request = Request({"type": "http", "method": "POST", "path": "/api/v1/x", "headers": []})
    resp = asyncio.run(_validation_exception_handler(request, RequestValidationError(errors=[])))
    assert resp.status_code == 422
    assert resp.media_type == PROBLEM_MEDIA_TYPE
