"""Dependencias de infraestructura para los endpoints (conector SSH y store Git). En tests se
sobreescriben con dobles."""

from __future__ import annotations

from typing import Annotated

from fastapi import Depends

from app.config import Settings, get_settings
from app.connectors.base import DeviceConnector
from app.connectors.netmiko_connector import NetmikoConnector
from app.git_store import GitStore


def get_connector(settings: Annotated[Settings, Depends(get_settings)]) -> DeviceConnector:
    return NetmikoConnector(
        username=settings.ssh_username,
        password=settings.ssh_password,
        device_type=settings.ssh_device_type,
    )


def get_git_store(settings: Annotated[Settings, Depends(get_settings)]) -> GitStore:
    return GitStore(settings.git_repo_path)
