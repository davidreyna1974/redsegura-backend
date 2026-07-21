"""Modelos ORM. La **vista de dispositivos** es la proyección local que este servicio mantiene a
partir de los eventos ``asset.*`` (para saber a qué dispositivos conectarse); ``processed_events``
implementa la idempotencia del consumidor (dedupe por ``eventId``, RNF-E1)."""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import UUID, uuid4

from sqlalchemy import Boolean, DateTime, String, Text, Uuid
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
