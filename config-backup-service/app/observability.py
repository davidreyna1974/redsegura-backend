"""Observabilidad de 3 pilares (RNF-15/16/17):

- **Métricas** Prometheus en ``/metrics`` — HTTP (peticiones + latencia) y **dominio** (respaldos,
  drift, eventos publicados, jobs). Registro propio (no el global) para ser reentrante en tests.
- **Trazas** OpenTelemetry (instrumentación FastAPI); ``traceId``/``spanId`` se inyectan en los
  logs. El exportador OTLP se activa por entorno (DEPLOY); en DEV queda instrumentado sin exportar.
- **Logs** en JSON, sin PII/secretos: un filtro **redacta** la contraseña SSH aunque se filtre por
  error (RNF-17). Campos mínimos del estándar: timestamp, level, service, traceId, message.
"""

from __future__ import annotations

import json
import logging
import time
from datetime import UTC, datetime
from typing import Any

from opentelemetry import trace
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from prometheus_client import (
    CONTENT_TYPE_LATEST,
    CollectorRegistry,
    Counter,
    Histogram,
    generate_latest,
)
from starlette.requests import Request
from starlette.responses import Response
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.config import Settings

# --- Registro y métricas (definidas una sola vez a nivel de módulo) ---

REGISTRY = CollectorRegistry()

_HTTP_REQUESTS = Counter(
    "http_requests_total",
    "Total de peticiones HTTP",
    ["method", "handler", "status"],
    registry=REGISTRY,
)
_HTTP_LATENCY = Histogram(
    "http_request_duration_seconds",
    "Latencia de las peticiones HTTP",
    ["method", "handler"],
    registry=REGISTRY,
)

# Métricas de dominio (RNF-15)
BACKUPS_TOTAL = Counter(
    "config_backups_total", "Respaldos realizados", ["status"], registry=REGISTRY
)
DRIFT_CHECKS_TOTAL = Counter(
    "config_drift_checks_total", "Drift-checks ejecutados", ["result"], registry=REGISTRY
)
EVENTS_PUBLISHED_TOTAL = Counter(
    "config_events_published_total",
    "Eventos config.* publicados al broker",
    ["event_type"],
    registry=REGISTRY,
)
JOBS_TOTAL = Counter(
    "config_jobs_total", "Jobs por lotes finalizados", ["type", "status"], registry=REGISTRY
)


class PrometheusMiddleware:
    """Middleware ASGI puro (no ``BaseHTTPMiddleware``, que interferiría con ``BackgroundTasks``).
    Mide latencia y cuenta peticiones por método/handler/status. El ``handler`` es la plantilla de
    ruta (no la URL con IDs) para acotar la cardinalidad."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        status_code = 500
        start = time.perf_counter()

        async def send_wrapper(message: Message) -> None:
            nonlocal status_code
            if message["type"] == "http.response.start":
                status_code = message["status"]
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        finally:
            route = scope.get("route")
            handler = getattr(route, "path", None) or "unmatched"
            method = scope.get("method", "GET")
            _HTTP_LATENCY.labels(method, handler).observe(time.perf_counter() - start)
            _HTTP_REQUESTS.labels(method, handler, str(status_code)).inc()


async def metrics_endpoint(_: Request) -> Response:
    """Expone las métricas en formato de texto de Prometheus."""
    return Response(generate_latest(REGISTRY), media_type=CONTENT_TYPE_LATEST)


# --- Logging JSON con redacción y correlación de trazas (RNF-17) ---


class _JsonFormatter(logging.Formatter):
    def __init__(self, service_name: str, secrets: tuple[str, ...]) -> None:
        super().__init__()
        self._service = service_name
        self._secrets = tuple(s for s in secrets if s)

    def _redact(self, text: str) -> str:
        for secret in self._secrets:
            text = text.replace(secret, "***")
        return text

    def format(self, record: logging.LogRecord) -> str:
        data: dict[str, Any] = {
            "timestamp": datetime.now(UTC).isoformat(),
            "level": record.levelname,
            "service": self._service,
            "logger": record.name,
            "message": self._redact(record.getMessage()),
        }
        span = trace.get_current_span()
        ctx = span.get_span_context()
        if ctx.is_valid:
            data["traceId"] = format(ctx.trace_id, "032x")
            data["spanId"] = format(ctx.span_id, "016x")
        if record.exc_info:
            data["exception"] = self._redact(self.formatException(record.exc_info))
        return json.dumps(data, ensure_ascii=False)


def configure_logging(settings: Settings) -> None:
    """Configura el logging raíz a JSON (idempotente)."""
    handler = logging.StreamHandler()
    handler.setFormatter(_JsonFormatter(settings.service_name, (settings.ssh_password,)))
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(settings.log_level.upper())


_tracing_configured = False


def _configure_tracing(settings: Settings) -> None:
    global _tracing_configured
    if _tracing_configured:
        return
    provider = TracerProvider(
        resource=Resource.create({"service.name": settings.service_name})
    )
    if settings.otel_exporter_otlp_endpoint:  # pragma: no cover  (exportador por entorno, DEPLOY)
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

        provider.add_span_processor(
            BatchSpanProcessor(
                OTLPSpanExporter(endpoint=settings.otel_exporter_otlp_endpoint)
            )
        )
    trace.set_tracer_provider(provider)
    _tracing_configured = True


def configure_observability(app: Any, settings: Settings) -> None:
    """Instala los 3 pilares sobre la app FastAPI."""
    configure_logging(settings)
    _configure_tracing(settings)
    FastAPIInstrumentor.instrument_app(app)
    app.add_middleware(PrometheusMiddleware)
    app.add_route("/metrics", metrics_endpoint, include_in_schema=False)
