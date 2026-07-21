"""Modelos Pydantic del contrato (``openapi.yaml``). Aquí solo los transversales/health; los DTOs de
respaldo/drift/schedule se añaden al implementar cada unidad (contract-first, ADR-05)."""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from uuid import UUID

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class HealthStatus(StrEnum):
    """Estado de salud del servicio."""

    UP = "UP"
    DOWN = "DOWN"


class Health(BaseModel):
    """Respuesta de los probes de salud (RNF-12)."""

    status: HealthStatus


class Problem(BaseModel):
    """Error en formato RFC 7807/9457 (``application/problem+json``, ADR-08)."""

    type: str = "about:blank"
    title: str
    status: int
    detail: str | None = None
    instance: str | None = None
    traceId: str | None = None  # noqa: N815  (nombre del contrato)


# --- Enumeraciones del dominio (del contrato) ---


class ConfigType(StrEnum):
    """Tipo de configuración capturada."""

    RUNNING = "running"
    STARTUP = "startup"


class BackupStatus(StrEnum):
    """Resultado de un respaldo (RF-10)."""

    SUCCESS = "SUCCESS"
    FAILED = "FAILED"


class JobStatus(StrEnum):
    """Estado de un job asíncrono (respaldo/drift por lotes)."""

    QUEUED = "QUEUED"
    IN_PROGRESS = "IN_PROGRESS"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


# --- DTOs de respuesta (serialización camelCase; leen desde el ORM) ---


class CamelModel(BaseModel):
    """Base de los DTOs: nombres Python en snake_case, JSON en camelCase; lee atributos del ORM."""

    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, from_attributes=True
    )


class BackupOut(CamelModel):
    """Respuesta ``Backup`` del contrato."""

    backup_id: UUID
    device_id: UUID
    commit: str | None = None
    captured_at: datetime
    unsaved_changes: bool
    status: BackupStatus
    failure_reason: str | None = None
    created_by: str


class PageBackup(CamelModel):
    """Página de respaldos (sobre de paginación estándar)."""

    content: list[BackupOut]
    page: int
    size: int
    total_elements: int
    total_pages: int
    first: bool
    last: bool
