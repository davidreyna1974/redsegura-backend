"""Consumo de ``asset.*``: despacha el cuerpo del mensaje al handler idempotente que mantiene la
vista local (RNF-E1). El transporte (pika, hilos) vive en ``runner.py``."""

from __future__ import annotations

import json
from typing import Any

from sqlalchemy.orm import Session

from app.messaging.asset_events import process_asset_event


def dispatch(session: Session, body: bytes) -> str:
    payload: dict[str, Any] = json.loads(body)
    return process_asset_event(session, payload)
