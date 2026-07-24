"""Conformidad de contrato de eventos — productor-side ("mini-Pact", RNF-21). Valida que los eventos
``config.*`` que **realmente emite** el servicio cumplen el `config-event.schema.json` del catálogo
(sobre §3.3 + payloads §4.2–4.5). Espejo de `AssetEventContractIT` de asset-inventory. Cierra la
divergencia detectada al auditar: los eventos se publicaban sin sobre y con payloads que no
coincidían con el catálogo."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any
from uuid import uuid4

import pytest
from app.db.models import Device, OutboxEvent
from app.git_store import GitStore
from app.messaging.outbox import to_envelope
from app.services.backup import backup_device
from app.services.drift import drift_check_device
from jsonschema import Draft202012Validator
from sqlalchemy import select
from sqlalchemy.orm import Session

from tests.fakes import FakeConnector

CIDRS = "10.0.0.0/8,192.168.0.0/16"
_SCHEMA_PATH = (
    Path(__file__).resolve().parents[2] / "contracts" / "events" / "config-event.schema.json"
)


@pytest.fixture(scope="module")
def validator() -> Draft202012Validator:
    schema = json.loads(_SCHEMA_PATH.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


def _seed(session: Session, device_id: Any, ipv4: str = "10.0.0.5") -> None:
    session.add(Device(device_id=device_id, hostname="SW1", mgmt_ipv4=ipv4, status="ACTIVO"))
    session.commit()


def _emitted_envelopes(session: Session) -> list[dict[str, Any]]:
    rows = session.scalars(select(OutboxEvent).order_by(OutboxEvent.created_at)).all()
    return [to_envelope(row) for row in rows]


# --- Happy: cada evento emitido cumple el esquema del catálogo ---


def test_backup_completed_and_unsaved_conform(
    db_session: Session, tmp_path: Path, validator: Draft202012Validator
) -> None:
    device_id = uuid4()
    _seed(db_session, device_id)
    store = GitStore(str(tmp_path / "repo"))
    # running != startup → emite backup_completed + unsaved_changes_detected.
    connector = FakeConnector(running="run\n", startup="start\n")

    backup_device(db_session, connector, store, device_id, CIDRS)

    envelopes = _emitted_envelopes(db_session)
    types = {e["eventType"] for e in envelopes}
    assert types == {"config.backup_completed", "config.unsaved_changes_detected"}
    for env in envelopes:
        validator.validate(env)  # lanza si no conforma
        assert env["source"] == "config-backup-service"
        assert env["payload"]["deviceId"] == str(device_id)


def test_backup_failed_conforms(
    db_session: Session, tmp_path: Path, validator: Draft202012Validator
) -> None:
    device_id = uuid4()
    _seed(db_session, device_id)
    store = GitStore(str(tmp_path / "repo"))
    connector = FakeConnector(fail_times=99, error="SSH timeout")

    backup_device(db_session, connector, store, device_id, CIDRS, sleep=lambda _: None)

    (env,) = _emitted_envelopes(db_session)
    validator.validate(env)
    assert env["eventType"] == "config.backup_failed"
    assert env["payload"]["reason"] == "SSH timeout"
    assert env["payload"]["attemptedAt"]


def test_drift_detected_conforms(
    db_session: Session, tmp_path: Path, validator: Draft202012Validator
) -> None:
    device_id = uuid4()
    _seed(db_session, device_id)
    store = GitStore(str(tmp_path / "repo"))
    base = FakeConnector(running="old\n", startup="old\n")
    backup_device(db_session, base, store, device_id, CIDRS)
    drift_check_device(
        db_session,
        FakeConnector(running="NEW\n", startup="NEW\n"),
        store,
        device_id,
        CIDRS,
        sleep=lambda _: None,
    )

    drift = [e for e in _emitted_envelopes(db_session) if e["eventType"] == "config.drift_detected"]
    assert len(drift) == 1
    validator.validate(drift[0])
    payload = drift[0]["payload"]
    assert payload["baselineBackupId"]
    assert payload["detectedBy"] == "drift-check"
    assert payload["detectedAt"]


# --- Sad: el esquema tiene dientes (rechaza sobre/payload no conformes) ---


def test_schema_rejects_bare_payload_without_envelope(validator: Draft202012Validator) -> None:
    # El bug original: publicar el payload desnudo sin sobre.
    assert not validator.is_valid({"deviceId": "x", "backupId": "y"})


def test_schema_rejects_payload_missing_catalog_fields(validator: Draft202012Validator) -> None:
    # El bug original: backup_completed sin capturedAt/status.
    envelope = {
        "eventId": "e1",
        "eventType": "config.backup_completed",
        "version": "1.0.0",
        "occurredAt": "2026-07-24T00:00:00Z",
        "source": "config-backup-service",
        "payload": {"deviceId": "d", "backupId": "b", "commit": "c", "unsavedChanges": False},
    }
    assert not validator.is_valid(envelope)


def test_schema_rejects_wrong_source(validator: Draft202012Validator) -> None:
    envelope = {
        "eventId": "e1",
        "eventType": "config.backup_failed",
        "version": "1.0.0",
        "occurredAt": "2026-07-24T00:00:00Z",
        "source": "otro-servicio",
        "payload": {"deviceId": "d", "attemptedAt": "t", "reason": "r"},
    }
    assert not validator.is_valid(envelope)
