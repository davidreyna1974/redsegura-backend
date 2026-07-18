"""PACT-01 (INT-CONS): el consumidor procesa exactamente el esquema que emite el productor
(asset-inventory). Se verifica contra el esquema compartido
``contracts/events/asset-event.schema.json`` —el mismo que valida el productor en
``AssetEventContractIT``—, cerrando el par consumidor↔productor."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any
from uuid import uuid4

import jsonschema
import pytest
from app.messaging.asset_events import process_asset_event
from sqlalchemy.orm import Session

from tests.factories import asset_created, asset_decommissioned, asset_updated

SCHEMA_PATH = (
    Path(__file__).resolve().parent.parent.parent
    / "contracts"
    / "events"
    / "asset-event.schema.json"
)


def _schema() -> dict[str, Any]:
    data: dict[str, Any] = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    return data


def test_example_events_conform_to_producer_schema() -> None:
    schema = _schema()
    for event in (asset_created(uuid4()), asset_updated(uuid4()), asset_decommissioned(uuid4())):
        jsonschema.validate(event, schema)  # sin excepción = conforme al contrato del productor


def test_consumer_processes_producer_conformant_event(db_session: Session) -> None:
    schema = _schema()
    event = asset_created(uuid4())
    jsonschema.validate(event, schema)
    assert process_asset_event(db_session, event) == "applied"


def test_schema_has_teeth_rejects_malformed_event() -> None:
    schema = _schema()
    malformed = asset_created(uuid4())
    del malformed["payload"]["hostname"]  # quita un campo requerido
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(malformed, schema)
