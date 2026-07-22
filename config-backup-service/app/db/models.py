"""Modelos ORM. La **vista de dispositivos** es la proyección local que este servicio mantiene a
partir de los eventos ``asset.*`` (para saber a qué dispositivos conectarse); ``processed_events``
implementa la idempotencia del consumidor (dedupe por ``eventId``, RNF-E1)."""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import UUID, uuid4

from sqlalchemy import Boolean, DateTime, Integer, String, Text, Uuid
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


def _now() -> datetime:
    return datetime.now(UTC)


class Device(Base):
    """Proyección local de un dispositivo del inventario (mantenida por eventos ``asset.*``)."""

    __tablename__ = "devices"

    device_id: Mapped[UUID] = mapped_column(Uuid, primary_key=True)
    hostname: Mapped[str] = mapped_column(String(255))
    mgmt_ipv4: Mapped[str | None] = mapped_column(String(45), nullable=True)
    mgmt_ipv6: Mapped[str | None] = mapped_column(String(45), nullable=True)
    status: Mapped[str] = mapped_column(String(20))
    criticality: Mapped[str | None] = mapped_column(String(20), nullable=True)
    vendor: Mapped[str | None] = mapped_column(String(255), nullable=True)
    model: Mapped[str | None] = mapped_column(String(255), nullable=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now
    )


class ProcessedEvent(Base):
    """Evento ya procesado (idempotencia del consumidor: no reaplicar el mismo ``eventId``)."""

    __tablename__ = "processed_events"

    event_id: Mapped[str] = mapped_column(String(255), primary_key=True)
    processed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)


class Backup(Base):
    """Metadato de un respaldo (RF-06/10). El contenido versionado vive en el repo Git (RF-07)."""

    __tablename__ = "backups"

    backup_id: Mapped[UUID] = mapped_column(Uuid, primary_key=True, default=uuid4)
    device_id: Mapped[UUID] = mapped_column(Uuid, index=True)
    commit: Mapped[str | None] = mapped_column(String(64), nullable=True)
    captured_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    unsaved_changes: Mapped[bool] = mapped_column(Boolean, default=False)
    status: Mapped[str] = mapped_column(String(20))
    failure_reason: Mapped[str | None] = mapped_column(String(500), nullable=True)
    created_by: Mapped[str] = mapped_column(String(255))


class OutboxEvent(Base):
    """Evento ``config.*`` pendiente de publicar (transactional outbox, ADR-04/RNF-30)."""

    __tablename__ = "outbox_events"

    id: Mapped[UUID] = mapped_column(Uuid, primary_key=True, default=uuid4)
    event_id: Mapped[UUID] = mapped_column(Uuid, unique=True, default=uuid4)
    event_type: Mapped[str] = mapped_column(String(100))
    payload: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class Job(Base):
    """Job asíncrono de respaldo/drift por lotes (RF-08). ``targets_json`` guarda los ``deviceId``
    resueltos al crearse; el dispatcher los procesa y actualiza los contadores."""

    __tablename__ = "jobs"

    job_id: Mapped[UUID] = mapped_column(Uuid, primary_key=True, default=uuid4)
    type: Mapped[str] = mapped_column(String(20))  # backup | drift-check
    status: Mapped[str] = mapped_column(String(20), default="QUEUED")
    total: Mapped[int] = mapped_column(Integer, default=0)
    completed: Mapped[int] = mapped_column(Integer, default=0)
    failed: Mapped[int] = mapped_column(Integer, default=0)
    targets_json: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    created_by: Mapped[str] = mapped_column(String(255))


class JobResult(Base):
    """Resultado por dispositivo dentro de un job por lotes."""

    __tablename__ = "job_results"

    id: Mapped[UUID] = mapped_column(Uuid, primary_key=True, default=uuid4)
    job_id: Mapped[UUID] = mapped_column(Uuid, index=True)
    device_id: Mapped[UUID] = mapped_column(Uuid)
    outcome: Mapped[str] = mapped_column(String(20))
    detail: Mapped[str | None] = mapped_column(String(500), nullable=True)


class Schedule(Base):
    """Programación recurrente (cron) de respaldo/drift (RF-08). ``target_json`` guarda el
    ``BatchTargetRequest`` original; ``next_run_at`` es el próximo disparo calculado con cron."""

    __tablename__ = "schedules"

    id: Mapped[UUID] = mapped_column(Uuid, primary_key=True, default=uuid4)
    type: Mapped[str] = mapped_column(String(20))  # backup | drift-check
    cron: Mapped[str] = mapped_column(String(100))
    target_json: Mapped[str] = mapped_column(Text)
    state: Mapped[str] = mapped_column(String(20), default="activa")
    next_run_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    created_by: Mapped[str] = mapped_column(String(255))
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now
    )
    updated_by: Mapped[str] = mapped_column(String(255))
