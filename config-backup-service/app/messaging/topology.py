"""Topología de RabbitMQ (catálogo de eventos §3). Exchange común ``redsegura.events`` (topic,
durable); este servicio consume ``asset.*`` en ``q.config-backup.asset-events`` (con DLQ) y publica
``config.*`` al mismo exchange."""

from __future__ import annotations

from typing import Any

EXCHANGE = "redsegura.events"
ASSET_QUEUE = "q.config-backup.asset-events"
ASSET_BINDING = "asset.*"
DLX = "redsegura.events.dlx"
ASSET_DLQ = "q.config-backup.asset-events.dlq"


def declare_topology(channel: Any) -> None:
    """Declara (idempotente) exchange, cola de entrada con DLQ y bindings."""
    channel.exchange_declare(exchange=EXCHANGE, exchange_type="topic", durable=True)
    channel.exchange_declare(exchange=DLX, exchange_type="fanout", durable=True)
    channel.queue_declare(queue=ASSET_DLQ, durable=True)
    channel.queue_bind(queue=ASSET_DLQ, exchange=DLX)
    channel.queue_declare(
        queue=ASSET_QUEUE, durable=True, arguments={"x-dead-letter-exchange": DLX}
    )
    channel.queue_bind(queue=ASSET_QUEUE, exchange=EXCHANGE, routing_key=ASSET_BINDING)
