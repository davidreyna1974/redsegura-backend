"""RNF-15/16/17: métricas Prometheus (HTTP + dominio), endpoint /metrics, y logs JSON con redacción
de secretos y correlación de trazas."""

from __future__ import annotations

import json
import logging
from collections.abc import Callable
from uuid import uuid4

from app.config import Settings
from app.db.models import Device
from app.observability import _JsonFormatter, configure_logging
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session

from tests.fakes import FakeConnector

ClientFactory = Callable[..., TestClient]


def test_metrics_endpoint_is_open_and_prometheus_format(make_api_client: ClientFactory) -> None:
    resp = make_api_client(roles=("AUD",)).get("/metrics")
    assert resp.status_code == 200
    assert "http_requests_total" in resp.text


def test_domain_metric_backups_total_increments(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    device_id = uuid4()
    db_session.add(
        Device(device_id=device_id, hostname="SW1", mgmt_ipv4="10.0.0.5", status="ACTIVO")
    )
    db_session.commit()
    client = make_api_client(roles=("ADM",), connector=FakeConnector(running="c\n", startup="c\n"))

    client.post(f"/api/v1/devices/{device_id}/backups")

    body = client.get("/metrics").text
    assert 'config_backups_total{status="SUCCESS"}' in body


def test_json_formatter_emits_structured_fields() -> None:
    formatter = _JsonFormatter("config-backup-service", ("s3cr3t",))
    record = logging.LogRecord(
        name="test", level=logging.INFO, pathname=__file__, lineno=1,
        msg="hola mundo", args=(), exc_info=None,
    )

    payload = json.loads(formatter.format(record))

    assert payload["service"] == "config-backup-service"
    assert payload["level"] == "INFO"
    assert payload["message"] == "hola mundo"
    assert "timestamp" in payload


def test_json_formatter_redacts_secret() -> None:
    formatter = _JsonFormatter("config-backup-service", ("s3cr3t",))
    record = logging.LogRecord(
        name="test", level=logging.WARNING, pathname=__file__, lineno=1,
        msg="conectando con password=%s", args=("s3cr3t",), exc_info=None,
    )

    payload = json.loads(formatter.format(record))

    assert "s3cr3t" not in payload["message"]
    assert "***" in payload["message"]


def test_configure_logging_sets_json_handler() -> None:
    configure_logging(Settings(service_name="config-backup-service", log_level="DEBUG"))
    root = logging.getLogger()
    assert root.level == logging.DEBUG
    assert isinstance(root.handlers[0].formatter, _JsonFormatter)
