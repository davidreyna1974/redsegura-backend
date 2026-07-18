"""La configuración se externaliza por entorno (12-factor); los overrides se aplican."""

from __future__ import annotations

import pytest
from app.config import Settings, get_settings


def test_defaults_load() -> None:
    settings = get_settings()
    assert settings.keycloak_audience == "redsegura-backend"
    assert "10.0.0.0/8" in settings.allowed_scan_cidrs


def test_env_override(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CBS_KEYCLOAK_AUDIENCE", "otra-audiencia")
    settings = Settings()
    assert settings.keycloak_audience == "otra-audiencia"
