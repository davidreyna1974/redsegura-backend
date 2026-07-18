"""Modelos ORM. La **vista de dispositivos** es la proyección local que este servicio mantiene a
partir de los eventos ``asset.*`` (para saber a qué dispositivos conectarse); ``processed_events``
implementa la idempotencia del consumidor (dedupe por ``eventId``, RNF-E1)."""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import UUID

from sqlalchemy import DateTime, String, Uuid
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
