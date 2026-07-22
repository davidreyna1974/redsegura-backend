"""Transporte de mensajería (pika + hilos de fondo). Arranca el consumidor de ``asset.*`` y el relay
del outbox de ``config.*`` cuando ``messaging_enabled``. Es plumbing sobre el broker real; la lógica
(``consumer.dispatch``, ``relay.publish_pending``, ``topology.declare_topology``) se prueba con un
RabbitMQ de Testcontainers."""

from __future__ import annotations

import threading
import time
from datetime import UTC, datetime
from typing import Any

import pika
from sqlalchemy import Engine
from sqlalchemy.orm import Session

from app.config import Settings
from app.git_store import GitStore
from app.messaging import relay
from app.messaging.consumer import dispatch
from app.messaging.topology import ASSET_QUEUE, declare_topology


def consume_forever(rabbitmq_url: str, engine: Engine) -> None:  # pragma: no cover
    connection = pika.BlockingConnection(pika.URLParameters(rabbitmq_url))
    channel = connection.channel()
    declare_topology(channel)
    channel.basic_qos(prefetch_count=10)

    def on_message(ch: Any, method: Any, properties: Any, body: bytes) -> None:
        with Session(engine) as session:
            try:
                dispatch(session, body)
                ch.basic_ack(method.delivery_tag)
            except Exception:  # noqa: BLE001  (fallo → DLQ vía nack)
                ch.basic_nack(method.delivery_tag, requeue=False)

    channel.basic_consume(queue=ASSET_QUEUE, on_message_callback=on_message)
    channel.start_consuming()


def relay_forever(rabbitmq_url: str, engine: Engine, interval_s: float) -> None:  # pragma: no cover
    connection = pika.BlockingConnection(pika.URLParameters(rabbitmq_url))
    channel = connection.channel()
    declare_topology(channel)
    channel.confirm_delivery()
    while True:
        with Session(engine) as session:
            relay.publish_pending(session, channel)
        time.sleep(interval_s)


def schedule_forever(settings: Settings, engine: Engine) -> None:  # pragma: no cover
    """Bucle del scheduler: dispara las programaciones cron vencidas (RF-08). Construye el conector
    y el store Git desde la configuración (igual que el dispatcher de jobs)."""
    from app.connectors.netmiko_connector import NetmikoConnector
    from app.services.schedules import tick_schedules

    git_store = GitStore(settings.git_repo_path)
    while True:
        connector = NetmikoConnector(
            username=settings.ssh_username,
            password=settings.ssh_password,
            device_type=settings.ssh_device_type,
        )
        with Session(engine) as session:
            tick_schedules(
                session, connector, git_store, settings.allowed_scan_cidrs, datetime.now(UTC)
            )
        time.sleep(settings.scheduler_interval_s)


def retention_forever(settings: Settings, engine: Engine) -> None:  # pragma: no cover
    """Bucle de retención: purga periódica de datos operativos antiguos."""
    from app.services.retention import purge_old

    while True:
        with Session(engine) as session:
            purge_old(session, datetime.now(UTC), settings.retention_days)
        time.sleep(settings.retention_interval_s)


def start_background(settings: Settings, engine: Engine) -> None:  # pragma: no cover
    threading.Thread(
        target=consume_forever, args=(settings.rabbitmq_url, engine), daemon=True
    ).start()
    threading.Thread(
        target=relay_forever,
        args=(settings.rabbitmq_url, engine, settings.outbox_relay_interval_s),
        daemon=True,
    ).start()
    threading.Thread(target=schedule_forever, args=(settings, engine), daemon=True).start()
    threading.Thread(target=retention_forever, args=(settings, engine), daemon=True).start()
