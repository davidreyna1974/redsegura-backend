"""Integración con RabbitMQ real (Testcontainers): el relay publica ``config.*`` al broker
(EVT-OUT) y el consumidor procesa ``asset.*`` desde la cola (EVT-IN), actualizando la vista."""

from __future__ import annotations

import json
import time
from collections.abc import Iterator
from typing import Any
from uuid import uuid4

import pika
import pytest
from app.db.models import Device, OutboxEvent
from app.messaging import relay
from app.messaging.consumer import dispatch
from app.messaging.outbox import OutboxWriter
from app.messaging.topology import ASSET_QUEUE, EXCHANGE, declare_topology
from sqlalchemy import select
from sqlalchemy.orm import Session
from testcontainers.rabbitmq import RabbitMqContainer

from tests.factories import asset_created


@pytest.fixture(scope="session")
def rabbitmq_params() -> Iterator[Any]:
    with RabbitMqContainer("rabbitmq:3.13-alpine") as rabbit:
        yield rabbit.get_connection_params()


@pytest.fixture
def channel(rabbitmq_params: Any) -> Iterator[Any]:
    connection = pika.BlockingConnection(rabbitmq_params)
    ch = connection.channel()
    declare_topology(ch)
    ch.queue_purge(ASSET_QUEUE)
    yield ch
    connection.close()


def _get_with_retry(ch: Any, queue: str, attempts: int = 30) -> tuple[Any, Any, Any]:
    for _ in range(attempts):
        method, props, body = ch.basic_get(queue=queue, auto_ack=False)
        if method is not None:
            return method, props, body
        time.sleep(0.1)
    return None, None, None


def test_relay_publishes_config_events(db_session: Session, channel: Any) -> None:
    OutboxWriter().record(db_session, "config.backup_completed", {"deviceId": "d1"})
    db_session.commit()
    channel.queue_declare(queue="test.config-events", durable=False, auto_delete=True)
    channel.queue_bind(queue="test.config-events", exchange=EXCHANGE, routing_key="config.*")
    channel.confirm_delivery()

    published = relay.publish_pending(db_session, channel)

    assert published == 1
    method, _props, body = _get_with_retry(channel, "test.config-events")
    assert method is not None
    assert json.loads(body)["deviceId"] == "d1"
    event = db_session.scalars(select(OutboxEvent)).first()
    assert event is not None
    assert event.published_at is not None


def test_consumer_updates_device_view(db_session: Session, channel: Any) -> None:
    device_id = uuid4()
    event = asset_created(device_id, hostname="SW-BROKER")
    channel.basic_publish(
        exchange=EXCHANGE, routing_key="asset.created", body=json.dumps(event).encode("utf-8")
    )

    method, _props, body = _get_with_retry(channel, ASSET_QUEUE)
    assert method is not None
    result = dispatch(db_session, body)
    channel.basic_ack(method.delivery_tag)

    assert result == "applied"
    device = db_session.get(Device, device_id)
    assert device is not None
    assert device.hostname == "SW-BROKER"
