"""Tests de aceptación BDD (patrón obligatorio E; base de la UAT). Reglas de negocio en lenguaje del
cliente (Gherkin español), ejecutadas con pytest-bdd sobre la misma infraestructura de test
(Testcontainers Postgres + TestClient). Espejo del `inventario.feature` de asset-inventory."""

from __future__ import annotations

from typing import Any
from uuid import uuid4

import pytest
from app.db.models import Device
from pytest_bdd import given, parsers, scenarios, then, when
from sqlalchemy.orm import Session

from tests.fakes import FakeConnector

scenarios("features/respaldo.feature")


@pytest.fixture
def ctx() -> dict[str, Any]:
    return {}


@given(parsers.parse('un dispositivo "{hostname}" con IP de gestión "{ip}" en el inventario'))
def _seed_device(db_session: Session, ctx: dict[str, Any], hostname: str, ip: str) -> None:
    device_id = uuid4()
    ctx["device_id"] = device_id
    ctx["hostname"] = hostname
    db_session.add(Device(device_id=device_id, hostname=hostname, mgmt_ipv4=ip, status="ACTIVO"))
    db_session.commit()


@given(parsers.parse('existe un respaldo previo con la configuración "{config}"'))
def _seed_backup(
    db_session: Session, make_api_client: Any, ctx: dict[str, Any], config: str
) -> None:
    # Se hace un respaldo real (crea el commit en Git) que servirá de línea base para el drift.
    baseline = config + "\n"
    ctx["baseline_config"] = baseline
    client = make_api_client(
        roles=("OPE",), connector=FakeConnector(running=baseline, startup=baseline)
    )
    resp = client.post(f"/api/v1/devices/{ctx['device_id']}/backups")
    assert resp.status_code == 201


@when("un operador solicita el respaldo del dispositivo")
def _operator_backup(make_api_client: Any, ctx: dict[str, Any]) -> None:
    client = make_api_client(roles=("OPE",), connector=FakeConnector(running="c\n", startup="c\n"))
    ctx["response"] = client.post(f"/api/v1/devices/{ctx['device_id']}/backups")


@when("un auditor solicita el respaldo del dispositivo")
def _auditor_backup(make_api_client: Any, ctx: dict[str, Any]) -> None:
    client = make_api_client(roles=("AUD",))
    ctx["response"] = client.post(f"/api/v1/devices/{ctx['device_id']}/backups")


@when(parsers.parse('la configuración en vivo cambia a "{config}"'))
def _live_config_changes(ctx: dict[str, Any], config: str) -> None:
    ctx["live_config"] = config + "\n"


@when("un operador ejecuta la verificación de drift")
def _operator_drift(make_api_client: Any, ctx: dict[str, Any]) -> None:
    live = ctx["live_config"]
    client = make_api_client(roles=("OPE",), connector=FakeConnector(running=live, startup=live))
    ctx["response"] = client.post(f"/api/v1/devices/{ctx['device_id']}/drift-check")


@then(parsers.parse('el respaldo se crea con estado "{status}"'))
def _backup_created(ctx: dict[str, Any], status: str) -> None:
    resp = ctx["response"]
    assert resp.status_code == 201
    assert resp.json()["status"] == status


@then("la solicitud se rechaza por estar fuera del alcance autorizado")
def _rejected_out_of_scope(ctx: dict[str, Any]) -> None:
    assert ctx["response"].status_code == 422


@then("el acceso es denegado")
def _access_denied(ctx: dict[str, Any]) -> None:
    assert ctx["response"].status_code == 403


@then("se reporta drift en el dispositivo")
def _drift_reported(ctx: dict[str, Any]) -> None:
    resp = ctx["response"]
    assert resp.status_code == 200
    assert resp.json()["drift"] is True
