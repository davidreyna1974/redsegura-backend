"""Validación de JWT y extracción de roles (RNF-29/04). El happy path evita JWKS mockeando
``_decode``; el flujo sin credenciales no lo necesita."""

from __future__ import annotations

from typing import Any

import pytest
from app.config import get_settings
from app.security import get_principal
from fastapi import HTTPException
from fastapi.security import HTTPAuthorizationCredentials


def test_get_principal_extracts_subject_and_roles(monkeypatch: pytest.MonkeyPatch) -> None:
    def fake_decode(token: str, settings: Any) -> dict[str, Any]:
        return {"sub": "u1", "realm_access": {"roles": ["ADM", "OPE"]}}

    monkeypatch.setattr("app.security._decode", fake_decode)
    credentials = HTTPAuthorizationCredentials(scheme="Bearer", credentials="tok")

    principal = get_principal(credentials, get_settings())

    assert principal.subject == "u1"
    assert "ADM" in principal.roles


def test_get_principal_without_credentials_is_401() -> None:
    with pytest.raises(HTTPException) as exc_info:
        get_principal(None, get_settings())

    assert exc_info.value.status_code == 401
