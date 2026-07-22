"""Dependencias de infraestructura para los endpoints (conector SSH y store Git). En tests se
sobreescriben con dobles."""

from __future__ import annotations

from collections.abc import Callable
from typing import Annotated
from uuid import UUID

from fastapi import Depends
from sqlalchemy.orm import Session

from app.config import Settings, get_settings
from app.connectors.base import DeviceConnector
from app.connectors.netmiko_connector import NetmikoConnector
from app.db.session import get_engine
from app.git_store import GitStore
from app.services.jobs import run_job


def get_connector(settings: Annotated[Settings, Depends(get_settings)]) -> DeviceConnector:
    return NetmikoConnector(
        username=settings.ssh_username,
        password=settings.ssh_password,
        device_type=settings.ssh_device_type,
    )


def get_git_store(settings: Annotated[Settings, Depends(get_settings)]) -> GitStore:
    return GitStore(settings.git_repo_path)


def get_job_dispatcher(
    settings: Annotated[Settings, Depends(get_settings)],
) -> Callable[[UUID], None]:
    """Devuelve el ejecutor de jobs por lotes. En producción abre su propia sesión y construye la
    infraestructura real (SSH/Git); se sobreescribe en tests para ejecutar con dobles. El endpoint
    lo agenda como tarea de fondo tras responder 202 (RF-08)."""

    def dispatch(job_id: UUID) -> None:  # pragma: no cover
        connector = NetmikoConnector(
            username=settings.ssh_username,
            password=settings.ssh_password,
            device_type=settings.ssh_device_type,
        )
        git_store = GitStore(settings.git_repo_path)
        with Session(get_engine()) as session:
            run_job(session, job_id, connector, git_store, settings.allowed_scan_cidrs)

    return dispatch
