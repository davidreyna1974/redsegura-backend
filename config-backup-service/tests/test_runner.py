"""RNF-10/RNF-30 (resiliencia del plumbing): ``run_resilient`` mantiene vivo un hilo de fondo ante
caídas de conexión (reconexión con backoff), en vez de morir. Regresión de HALLAZGO-LIVE-CBS-01."""

from __future__ import annotations

import pika
from app.messaging.runner import RECOVERABLE_ERRORS, run_resilient


def test_reconnects_after_recoverable_error_with_growing_backoff() -> None:
    calls = {"serve": 0}
    sleeps: list[float] = []

    def serve() -> None:
        calls["serve"] += 1
        # Primeras 2 ejecuciones fallan (broker caído); a la 3.ª retorna limpio.
        if calls["serve"] < 3:
            raise pika.exceptions.StreamLostError("connection reset")

    # Corre hasta que serve haya tenido éxito una vez (3 ejecuciones en total).
    run_resilient(
        serve,
        should_continue=lambda: calls["serve"] < 3,
        sleep=sleeps.append,
        initial_backoff=1.0,
        max_backoff=30.0,
    )

    assert calls["serve"] == 3
    assert sleeps == [1.0, 2.0]  # backoff exponencial entre reintentos


def test_backoff_is_capped() -> None:
    sleeps: list[float] = []
    attempts = {"n": 0}

    def serve() -> None:
        attempts["n"] += 1
        raise OSError("socket boom")

    run_resilient(
        serve,
        should_continue=lambda: attempts["n"] < 6,
        sleep=sleeps.append,
        initial_backoff=10.0,
        max_backoff=30.0,
    )

    assert sleeps == [10.0, 20.0, 30.0, 30.0, 30.0, 30.0]  # tope en 30s


def test_non_recoverable_error_propagates() -> None:
    def serve() -> None:
        raise ValueError("bug de programación, no recuperable")

    try:
        run_resilient(serve, should_continue=lambda: True, sleep=lambda _: None)
    except ValueError:
        pass
    else:  # pragma: no cover
        raise AssertionError("un error no recuperable debe propagarse")


def test_stops_when_should_continue_false() -> None:
    def serve() -> None:  # pragma: no cover  (no debe ejecutarse)
        raise AssertionError("no debería llamarse")

    run_resilient(serve, should_continue=lambda: False, sleep=lambda _: None)


def test_recoverable_errors_include_broker_and_db() -> None:
    from sqlalchemy.exc import SQLAlchemyError

    assert pika.exceptions.AMQPError in RECOVERABLE_ERRORS
    assert SQLAlchemyError in RECOVERABLE_ERRORS
    assert OSError in RECOVERABLE_ERRORS
