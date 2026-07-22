"""Modelos Pydantic del contrato (``openapi.yaml``). Aquí solo los transversales/health; los DTOs de
respaldo/drift/schedule se añaden al implementar cada unidad (contract-first, ADR-05)."""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, model_validator
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


class ScheduleType(StrEnum):
    """Tipo de tarea programada (RF-08)."""

    BACKUP = "backup"
    DRIFT_CHECK = "drift-check"


class ScheduleState(StrEnum):
    """Estado de una programación."""

    ACTIVA = "activa"
    PAUSADA = "pausada"


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


# --- Lotes (jobs) y programaciones (schedules) ---


class BatchFilter(CamelModel):
    """Filtro de selección de dispositivos para un lote."""

    hostname: str | None = None
    mgmt_ip: str | None = None


class BatchTarget(CamelModel):
    """Objetivo de un lote (``BatchTargetRequest``). Debe indicarse **exactamente uno** de
    ``scope`` / ``deviceIds`` / ``filter`` (VAL)."""

    scope: Literal["all"] | None = None
    device_ids: list[UUID] | None = None
    filter: BatchFilter | None = None

    @model_validator(mode="after")
    def _exactly_one(self) -> BatchTarget:
        selectors = [
            self.scope is not None,
            bool(self.device_ids),
            self.filter is not None and self.filter.model_dump(exclude_none=True) != {},
        ]
        if sum(selectors) != 1:
            raise ValueError("Indicar exactamente uno: scope, deviceIds o filter")
        return self


class JobResultOut(CamelModel):
    """Resultado por dispositivo dentro de un job."""

    device_id: UUID
    outcome: str
    detail: str | None = None


class JobOut(CamelModel):
    """Respuesta ``Job`` del contrato."""

    job_id: UUID
    type: str
    status: JobStatus
    total: int
    completed: int
    failed: int
    results: list[JobResultOut] = []


class ScheduleCreateRequest(CamelModel):
    """Cuerpo de ``POST /schedules``."""

    type: ScheduleType
    cron: str
    target: BatchTarget


class ScheduleOut(CamelModel):
    """Respuesta ``Schedule`` del contrato."""

    id: UUID
    type: ScheduleType
    cron: str
    target: BatchTarget
    state: ScheduleState
    created_at: datetime
    created_by: str
    updated_at: datetime
    updated_by: str


class PageSchedule(CamelModel):
    """Página de programaciones (sobre de paginación estándar)."""

    content: list[ScheduleOut]
    page: int
    size: int
    total_elements: int
    total_pages: int
    first: bool
    last: bool
