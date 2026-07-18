"""Manejo centralizado de errores en formato RFC 7807/9457 (``application/problem+json``, ADR-08).
Nunca se filtran internos al cliente (RNF-09)."""

from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.schemas import Problem

PROBLEM_MEDIA_TYPE = "application/problem+json"


def _problem(status: int, title: str, detail: str | None, instance: str) -> JSONResponse:
    body = Problem(title=title, status=status, detail=detail, instance=instance)
    return JSONResponse(
        status_code=status,
        media_type=PROBLEM_MEDIA_TYPE,
        content=body.model_dump(exclude_none=True),
    )


async def _http_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, StarletteHTTPException)
    detail = exc.detail if isinstance(exc.detail, str) else None
    return _problem(exc.status_code, _title_for(exc.status_code), detail, request.url.path)


async def _validation_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, RequestValidationError)
    detail = "El cuerpo o los parámetros no son válidos"
    return _problem(422, "Validación fallida", detail, request.url.path)


def _title_for(status: int) -> str:
    return {
        400: "Petición malformada",
        401: "No autenticado",
        403: "Sin permiso",
        404: "No encontrado",
        409: "Conflicto",
        422: "Validación fallida",
    }.get(status, "Error")


def register_error_handlers(app: FastAPI) -> None:
    """Registra los manejadores que traducen excepciones a `problem+json`."""
    app.add_exception_handler(StarletteHTTPException, _http_exception_handler)
    app.add_exception_handler(RequestValidationError, _validation_exception_handler)
