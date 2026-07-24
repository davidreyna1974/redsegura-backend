"""Transactional outbox (ADR-04/RNF-30): los eventos ``config.*`` se escriben en la misma tx que el
cambio; un relay los publica luego al broker. ``record`` guarda el **payload**; al publicar se
envuelve en el **sobre común** del catálogo (§3.3) con ``to_envelope`` — así el ``eventId`` del
sobre es el de la fila de outbox (dedup del consumidor) y ``occurredAt`` su ``created_at``."""

from __future__ import annotations

import json
from typing import Any

from sqlalchemy.orm import Session

from app.db.models import OutboxEvent

# Sobre común del catálogo de eventos (§3.3).
SOURCE = "config-backup-service"
EVENT_VERSION = "1.0.0"


class OutboxWriter:
    def record(self, session: Session, event_type: str, payload: dict[str, Any]) -> None:
        session.add(OutboxEvent(event_type=event_type, payload=json.dumps(payload)))


def to_envelope(event: OutboxEvent) -> dict[str, Any]:
    """Construye el sobre común publicable a partir de una fila de outbox (§3.3 del catálogo)."""
    return {
        "eventId": str(event.event_id),
        "eventType": event.event_type,
        "version": EVENT_VERSION,
        "occurredAt": event.created_at.isoformat(),
        "source": SOURCE,
        "payload": json.loads(event.payload),
    }
