"""Probes de salud (RNF-12/ADR-10). liveness = ¿el proceso vive?; readiness = ¿listo para tráfico?
(BD/broker/Git). No requieren autenticación."""

from __future__ import annotations

from collections.abc import Callable
from typing import Annotated

from fastapi import APIRouter, Depends, Response

from app.schemas import Health, HealthStatus

router = APIRouter(tags=["health"])

# Cada chequeo devuelve True si su dependencia está sana. Se registran al añadir BD/broker/Git; una
# función inyectable permite que los tests fuercen el estado DOWN sin dependencias reales.
ReadinessChecks = list[Callable[[], bool]]


def get_readiness_checks() -> ReadinessChecks:
    """Chequeos de readiness activos (vacío en el scaffold → UP)."""
    return []


@router.get("/health/liveness", response_model=Health)
def liveness() -> Health:
    return Health(status=HealthStatus.UP)


@router.get("/health/readiness", response_model=Health)
def readiness(
    response: Response,
    checks: Annotated[ReadinessChecks, Depends(get_readiness_checks)],
) -> Health:
    if all(check() for check in checks):
        return Health(status=HealthStatus.UP)
    response.status_code = 503
    return Health(status=HealthStatus.DOWN)
