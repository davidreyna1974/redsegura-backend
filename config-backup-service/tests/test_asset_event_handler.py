"""EVT-IN-01..04: el consumidor de ``asset.*`` mantiene la vista local de dispositivos de forma
idempotente."""

from __future__ import annotations

from uuid import uuid4

from app.db.models import Device
from app.messaging.asset_events import process_asset_event
from sqlalchemy.orm import Session

from tests.factories import asset_created, asset_decommissioned, asset_updated


def test_created_upserts_device(db_session: Session) -> None:
    device_id = uuid4()

    result = process_asset_event(db_session, asset_created(device_id, hostname="SW-CORE"))

    assert result == "applied"
    device = db_session.get(Device, device_id)
    assert device is not None
    assert device.hostname == "SW-CORE"
    assert device.mgmt_ipv4 == "10.0.0.1"
    assert device.status == "ACTIVO"


def test_updated_updates_the_view(db_session: Session) -> None:
    device_id = uuid4()
    process_asset_event(db_session, asset_created(device_id))

    process_asset_event(db_session, asset_updated(device_id, hostname="SW-NEW", ipv6="2001:db8::9"))

    device = db_session.get(Device, device_id)
    assert device is not None
    assert device.hostname == "SW-NEW"
    assert device.mgmt_ipv6 == "2001:db8::9"


def test_decommissioned_marks_baja(db_session: Session) -> None:
    device_id = uuid4()
    process_asset_event(db_session, asset_created(device_id))

    process_asset_event(db_session, asset_decommissioned(device_id))

    device = db_session.get(Device, device_id)
    assert device is not None
    assert device.status == "BAJA"


def test_reprocessing_same_event_is_idempotent(db_session: Session) -> None:
    device_id = uuid4()
    event = asset_created(device_id, hostname="SW-1")

    first = process_asset_event(db_session, event)
    # Un update llegó entre medias; el reproceso del `created` NO debe revertir el estado.
    process_asset_event(db_session, asset_updated(device_id, hostname="SW-1-UPDATED"))
    second = process_asset_event(db_session, event)

    assert first == "applied"
    assert second == "duplicate"
    device = db_session.get(Device, device_id)
    assert device is not None
    assert device.hostname == "SW-1-UPDATED"  # el reproceso no pisó el update
