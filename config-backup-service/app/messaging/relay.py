"""Relay del transactional outbox (RNF-30/ADR-15): publica los eventos ``config.*`` pendientes al
broker. Toma el lote con ``FOR UPDATE SKIP LOCKED`` (seguro con varias réplicas) y marca publicado
solo tras el ACK del broker (el canal debe tener ``confirm_delivery`` activo)."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from typing import Any

import pika
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import OutboxEvent
from app.messaging.outbox import to_envelope
from app.messaging.topology import EXCHANGE
from app.observability import EVENTS_PUBLISHED_TOTAL


def publish_pending(session: Session, channel: Any, batch_size: int = 100) -> int:
    """Publica un lote de eventos pendientes y los marca publicados. Devuelve cuántos publicó."""
    stmt = (
        select(OutboxEvent)
        .where(OutboxEvent.published_at.is_(None))
        .order_by(OutboxEvent.created_at)
        .limit(batch_size)
        .with_for_update(skip_locked=True)
    )
    pending = list(session.scalars(stmt).all())
    for event in pending:
        properties = pika.BasicProperties(
            content_type="application/json",
            delivery_mode=2,  # persistente
            message_id=str(event.event_id),
        )
        # Se publica el **sobre común** (§3.3), no el payload desnudo, para que el consumidor reciba
        # eventId/eventType/version/occurredAt/source.
        body = json.dumps(to_envelope(event)).encode("utf-8")
        # Con confirm_delivery activo, basic_publish bloquea hasta el ACK; si hay nack lanza y la
        # transacción no se commitea (el evento sigue pendiente y se reintenta).
        channel.basic_publish(
            exchange=EXCHANGE,
            routing_key=event.event_type,
            body=body,
            properties=properties,
        )
        event.published_at = datetime.now(UTC)
        EVENTS_PUBLISHED_TOTAL.labels(event.event_type).inc()
    session.commit()
    return len(pending)
