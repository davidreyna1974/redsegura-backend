"""Modelos Pydantic del contrato (``openapi.yaml``). Aquí solo los transversales/health; los DTOs de
respaldo/drift/schedule se añaden al implementar cada unidad (contract-first, ADR-05)."""

from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel


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
