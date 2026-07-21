"""Conector SSH real (Netmiko). Se usa contra la **red simulada** (RNF-07); en tests se sustituye
por un doble (nunca se conecta a dispositivos reales)."""

from __future__ import annotations

from netmiko import ConnectHandler

from app.connectors.base import BackupConnectionError, DeviceConfig


class NetmikoConnector:
    def __init__(
        self, username: str, password: str, device_type: str = "cisco_ios", timeout: int = 30
    ) -> None:
        self._username = username
        self._password = password
        self._device_type = device_type
        self._timeout = timeout

    def fetch_config(self, host: str) -> DeviceConfig:
        connection = None
        try:
            connection = ConnectHandler(
                device_type=self._device_type,
                host=host,
                username=self._username,
                password=self._password,
                conn_timeout=self._timeout,
            )
            running = str(connection.send_command("show running-config"))
            startup = str(connection.send_command("show startup-config"))
            return DeviceConfig(running=running, startup=startup)
        except Exception as error:  # noqa: BLE001  (cualquier fallo SSH se normaliza)
            raise BackupConnectionError(f"fallo SSH con {host}: {error}") from error
        finally:
            if connection is not None:
                connection.disconnect()
