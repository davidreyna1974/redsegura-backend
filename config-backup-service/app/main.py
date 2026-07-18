"""Punto de entrada de la aplicación FastAPI (uvicorn: ``app.main:app``). Contract-first: los
routers implementan el ``openapi.yaml``; FastAPI sirve la doc navegable en runtime (RNF-27)."""

from __future__ import annotations

from fastapi import FastAPI

from app.api import health
from app.errors import register_error_handlers


def create_app() -> FastAPI:
    app = FastAPI(
        title="config-backup-service API",
        version="0.1.0",
        description="Respaldo y versionado de configuraciones de red (RF-06..10).",
    )
    register_error_handlers(app)
    app.include_router(health.router, prefix="/api/v1")
    return app


app = create_app()
