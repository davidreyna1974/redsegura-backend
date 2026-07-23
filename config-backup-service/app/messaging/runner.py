"""Transporte de mensajería (pika + hilos de fondo). Arranca el consumidor de ``asset.*``, el relay
del outbox de ``config.*``, el scheduler (RF-08) y la retención cuando ``messaging_enabled``.

Es plumbing sobre servicios externos; la lógica (``consumer.dispatch``, ``relay.publish_pending``,
``topology.declare_topology``, ``schedules.tick_schedules``, ``retention.purge_old``) se prueba con
Testcontainers. Aquí lo que **sí** se prueba es ``run_resilient``: el bucle que mantiene vivo cada
hilo ante caídas de conexión (RNF-10/RNF-30) — sin él, un reset del broker mataba el hilo y el
outbox dejaba de drenarse (HALLAZGO-LIVE-CBS-01)."""

from __future__ import annotations

import contextlib
import logging
import threading
import time
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Any

import pika
from sqlalchemy import Engine
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.config import Settings
from app.git_store import GitStore
from app.messaging import relay
from app.messaging.consumer import dispatch
from app.messaging.topology import ASSET_QUEUE, declare_topology

_log = logging.getLogger(__name__)

# Errores tras los que tiene sentido reconectar/reintentar (no matar el hilo): caída del broker o de
# la BD, o problemas de socket. Un error no listado (bug de programación) sí se propaga.
RECOVERABLE_ERRORS: tuple[type[BaseException], ...] = (
    pika.exceptions.AMQPError,
    SQLAlchemyError,
    OSError,
)


def run_resilient(
    serve: Callable[[], None],
    *,
    should_continue: Callable[[], bool],
    sleep: Callable[[float], None] = time.sleep,
    initial_backoff: float = 1.0,
    max_backoff: float = 30.0,
) -> None:
    """Ejecuta ``serve`` (que corre hasta que falla) en bucle, reconectando con backoff exponencial
    ante errores recuperables. Un ``serve`` que retorna limpio reinicia el backoff y vuelve a
    ejecutarse mientras ``should_continue()`` sea verdadero."""
    backoff = initial_backoff
    while should_continue():
        try:
            serve()
            backoff = initial_backoff
        except RECOVERABLE_ERRORS as exc:
            _log.warning(
                "hilo de fondo: fallo recuperable (%s); reintentando en %.1fs", exc, backoff
            )
            sleep(backoff)
            backoff = min(backoff * 2, max_backoff)


def _forever() -> bool:  # pragma: no cover  (condición de parada de producción: nunca)
    return True


def _serve_consumer(rabbitmq_url: str, engine: Engine) -> None:  # pragma: no cover
    connection = pika.BlockingConnection(pika.URLParameters(rabbitmq_url))
    try:
        channel = connection.channel()
        declare_topology(channel)
        channel.basic_qos(prefetch_count=10)

        def on_message(ch: Any, method: Any, properties: Any, body: bytes) -> None:
            with Session(engine) as session:
                try:
                    dispatch(session, body)
                    ch.basic_ack(method.delivery_tag)
                except Exception:  # noqa: BLE001  (fallo del handler → DLQ vía nack)
                    ch.basic_nack(method.delivery_tag, requeue=False)

        channel.basic_consume(queue=ASSET_QUEUE, on_message_callback=on_message)
        channel.start_consuming()  # bloquea; lanza al perder la conexión
    finally:
        with contextlib.suppress(Exception):
            connection.close()


def _serve_relay(rabbitmq_url: str, engine: Engine, interval_s: float) -> None:  # pragma: no cover
    connection = pika.BlockingConnection(pika.URLParameters(rabbitmq_url))
    try:
        channel = connection.channel()
        declare_topology(channel)
        channel.confirm_delivery()
        while True:
            with Session(engine) as session:
                relay.publish_pending(session, channel)
            time.sleep(interval_s)
    finally:
        with contextlib.suppress(Exception):
            connection.close()


def _serve_scheduler(settings: Settings, engine: Engine) -> None:  # pragma: no cover
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


def _serve_retention(settings: Settings, engine: Engine) -> None:  # pragma: no cover
    from app.services.retention import purge_old

    while True:
        with Session(engine) as session:
            purge_old(session, datetime.now(UTC), settings.retention_days)
        time.sleep(settings.retention_interval_s)


def _daemon(serve: Callable[[], None]) -> threading.Thread:  # pragma: no cover
    return threading.Thread(
        target=lambda: run_resilient(serve, should_continue=_forever), daemon=True
    )


def start_background(settings: Settings, engine: Engine) -> None:  # pragma: no cover
    """Arranca los 4 hilos de fondo, cada uno auto-recuperable ante caídas de conexión."""
    _daemon(lambda: _serve_consumer(settings.rabbitmq_url, engine)).start()
    _daemon(
        lambda: _serve_relay(settings.rabbitmq_url, engine, settings.outbox_relay_interval_s)
    ).start()
    _daemon(lambda: _serve_scheduler(settings, engine)).start()
    _daemon(lambda: _serve_retention(settings, engine)).start()
