"""Transactional outbox (ADR-04/RNF-30): los eventos ``config.*`` se escriben en la misma tx
que el cambio; un relay los publica luego al broker. Aquí se registra (write); el relay se añade con
el wiring del broker."""

from __future__ import annotations

import json
from typing import Any

from sqlalchemy.orm import Session

from app.db.models import OutboxEvent


class OutboxWriter:
    def record(self, session: Session, event_type: str, payload: dict[str, Any]) -> None:
        session.add(OutboxEvent(event_type=event_type, payload=json.dumps(payload)))
