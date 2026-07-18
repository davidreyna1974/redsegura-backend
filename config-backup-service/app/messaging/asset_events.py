"""Consumo de eventos ``asset.*`` (productor: asset-inventory). Mantiene la **vista local de
dispositivos** de forma **idempotente** (dedupe por ``eventId``, RNF-E1). Sobre y payload según el
catálogo (§3.3/§4.1) y el esquema compartido ``contracts/events/asset-event.schema.json``."""

from __future__ import annotations

from typing import Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.db.models import Device, ProcessedEvent


class _CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class Address(_CamelModel):
    address: str
    prefix_length: int
    gateway: str | None = None


class AssetEventPayload(_CamelModel):
    device_id: UUID
    hostname: str
    management_ipv4: Address | None = None
    management_ipv6: Address | None = None
    vendor: str | None = None
    model: str | None = None
    criticality: str
    status: str
    changed_fields: list[str] | None = None


class AssetEventEnvelope(_CamelModel):
    event_id: str
    event_type: str
    version: str
    occurred_at: str
    source: str
    trace_id: str | None = None
    payload: AssetEventPayload


def process_asset_event(session: Session, raw: dict[str, Any]) -> str:
    """Aplica un evento ``asset.*`` a la vista local. Devuelve ``"applied"`` o ``"duplicate"``.

    Idempotente: si el ``eventId`` ya se procesó (o dos entregas concurrentes chocan en la clave
    primaria de ``processed_events``), no se reaplica.
    """
    envelope = AssetEventEnvelope.model_validate(raw)
    if session.get(ProcessedEvent, envelope.event_id) is not None:
        return "duplicate"

    payload = envelope.payload
    if envelope.event_type == "asset.decommissioned":
        device = session.get(Device, payload.device_id)
        if device is not None:
            device.status = "BAJA"
    else:  # asset.created / asset.updated → upsert de la vista
        device = session.get(Device, payload.device_id)
        if device is None:
            device = Device(device_id=payload.device_id)
            session.add(device)
        device.hostname = payload.hostname
        device.mgmt_ipv4 = payload.management_ipv4.address if payload.management_ipv4 else None
        device.mgmt_ipv6 = payload.management_ipv6.address if payload.management_ipv6 else None
        device.status = payload.status
        device.criticality = payload.criticality
        device.vendor = payload.vendor
        device.model = payload.model

    session.add(ProcessedEvent(event_id=envelope.event_id))
    try:
        session.commit()
    except IntegrityError:
        session.rollback()
        return "duplicate"
    return "applied"
