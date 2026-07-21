"""Dobles de test. El conector falso evita conectar a dispositivos reales (RNF-07)."""

from __future__ import annotations

from app.connectors.base import BackupConnectionError, DeviceConfig


class FakeConnector:
    """``DeviceConnector`` de prueba. Puede simular fallos transitorios (``fail_times``)."""

    def __init__(
        self,
        running: str = "hostname R1\n!\n",
        startup: str | None = None,
        fail_times: int = 0,
        error: str = "ssh timeout",
    ) -> None:
        self.running = running
        self.startup = running if startup is None else startup
        self._fail_times = fail_times
        self.error = error
        self.calls = 0

    def fetch_config(self, host: str) -> DeviceConfig:
        self.calls += 1
        if self.calls <= self._fail_times:
            raise BackupConnectionError(self.error)
        return DeviceConfig(running=self.running, startup=self.startup)
