"""El conector Netmiko real, con la librería mockeada (nunca conecta a un dispositivo, RNF-07)."""

from __future__ import annotations

import pytest
from app.connectors.base import BackupConnectionError
from app.connectors.netmiko_connector import NetmikoConnector


class _FakeHandler:
    def __init__(self, **kwargs: object) -> None:
        self.kwargs = kwargs

    def send_command(self, command: str) -> str:
        return "RUN" if "running" in command else "START"

    def disconnect(self) -> None:
        return None


def test_fetch_config_captures_running_and_startup(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.connectors.netmiko_connector.ConnectHandler", _FakeHandler)

    config = NetmikoConnector("user", "pass").fetch_config("10.0.0.1")

    assert config.running == "RUN"
    assert config.startup == "START"


def test_ssh_failures_are_wrapped(monkeypatch: pytest.MonkeyPatch) -> None:
    def boom(**kwargs: object) -> None:
        raise OSError("no route to host")

    monkeypatch.setattr("app.connectors.netmiko_connector.ConnectHandler", boom)

    with pytest.raises(BackupConnectionError):
        NetmikoConnector("user", "pass").fetch_config("10.0.0.1")
