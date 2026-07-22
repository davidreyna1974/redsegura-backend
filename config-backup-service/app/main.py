"""Punto de entrada de la aplicación FastAPI (uvicorn: ``app.main:app``). Contract-first: los
routers implementan el ``openapi.yaml``; FastAPI sirve la doc navegable en runtime (RNF-27)."""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api import backups, drift, health, schedules
from app.config import get_settings
from app.db.session import get_engine
from app.errors import register_error_handlers
from app.messaging.runner import start_background
from app.observability import configure_observability


@asynccontextmanager
async def _lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    if settings.messaging_enabled:  # pragma: no cover
        start_background(settings, get_engine())
    yield


def create_app() -> FastAPI:
    app = FastAPI(
        title="config-backup-service API",
        version="0.1.0",
        description="Respaldo y versionado de configuraciones de red (RF-06..10).",
        lifespan=_lifespan,
    )
    register_error_handlers(app)
    configure_observability(app, get_settings())
    app.include_router(health.router, prefix="/api/v1")
    app.include_router(backups.router, prefix="/api/v1")
    app.include_router(drift.router, prefix="/api/v1")
    app.include_router(schedules.router, prefix="/api/v1")
    return app


app = create_app()
