"""Interfaz de conexión a dispositivos y tipos asociados (RF-06)."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


class BackupConnectionError(Exception):
    """Fallo al conectar o capturar la configuración de un dispositivo (RF-10)."""


@dataclass(frozen=True)
class DeviceConfig:
    """Configuraciones capturadas del dispositivo."""

    running: str
    startup: str


class DeviceConnector(Protocol):
    """Captura la configuración de un dispositivo por SSH. Implementaciones: Netmiko (real, red
    simulada) y un doble para tests."""

    def fetch_config(self, host: str) -> DeviceConfig: ...
