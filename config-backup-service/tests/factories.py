"""Constructores de eventos ``asset.*`` conformes al esquema compartido (para los tests)."""

from __future__ import annotations

from typing import Any
from uuid import UUID, uuid4


def _envelope(event_type: str, payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "eventId": str(uuid4()),
        "eventType": event_type,
        "version": "1.1.0",
        "occurredAt": "2026-07-18T00:00:00Z",
        "source": "asset-inventory-service",
        "payload": payload,
    }


def asset_created(
    device_id: UUID, hostname: str = "SW-1", ipv4: str = "10.0.0.1"
) -> dict[str, Any]:
    return _envelope(
        "asset.created",
        {
            "deviceId": str(device_id),
            "hostname": hostname,
            "managementIpv4": {"address": ipv4, "prefixLength": 24, "gateway": None},
            "criticality": "ALTA",
            "status": "ACTIVO",
        },
    )


def asset_updated(
    device_id: UUID, hostname: str = "SW-1b", ipv6: str = "2001:db8::1"
) -> dict[str, Any]:
    return _envelope(
        "asset.updated",
        {
            "deviceId": str(device_id),
            "hostname": hostname,
            "managementIpv6": {"address": ipv6, "prefixLength": 64, "gateway": None},
            "criticality": "MEDIA",
            "status": "ACTIVO",
            "changedFields": ["hostname", "managementIpv6"],
        },
    )


def asset_decommissioned(device_id: UUID, hostname: str = "SW-1") -> dict[str, Any]:
    return _envelope(
        "asset.decommissioned",
        {
            "deviceId": str(device_id),
            "hostname": hostname,
            "managementIpv4": {"address": "10.0.0.1", "prefixLength": 24, "gateway": None},
            "criticality": "ALTA",
            "status": "BAJA",
        },
    )
