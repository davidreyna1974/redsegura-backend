"""Idempotencia de escritura (RN-CB7, alineada con ADR-09). Cuando un POST trae la cabecera
``Idempotency-Key``, se **reserva** la clave (acotada por actor) **antes** de ejecutar el efecto; un
reintento con la MISMA clave y el MISMO cuerpo devuelve la respuesta guardada sin repetir el efecto
(``replay``); con distinto cuerpo → 409 (misuse); si la reserva aún no completó (petición
concurrente en curso) → 409 con detalle ``en curso``.

La reserva se persiste en su propia transacción **antes** del trabajo, de modo que dos peticiones
concurrentes con la misma clave no dupliquen el efecto: la segunda choca con la restricción única y
se resuelve como replay/conflicto."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any

from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.db.models import IdempotencyKey


class IdempotencyConflictError(Exception):
    """Misma clave + actor, pero cuerpo distinto (uso incorrecto del cliente)."""


class IdempotencyInProgressError(Exception):
    """La clave está reservada pero su respuesta aún no se ha completado (petición concurrente)."""


def _hash(body: str) -> str:
    return hashlib.sha256(body.encode("utf-8")).hexdigest()


@dataclass
class _Reservation:
    row: IdempotencyKey | None
    replay: tuple[int, Any] | None


class Idempotency:
    """Ayudante por-petición. Con ``key`` vacío es un no-op (el endpoint se comporta normal)."""

    def __init__(self, session: Session, key: str | None, actor: str, body: str) -> None:
        self._session = session
        self._key = key
        self._actor = actor
        self._body = body
        self._reservation: _Reservation | None = None

    def replay(self) -> JSONResponse | None:
        """Si es un reintento de una petición ya completada, devuelve su respuesta guardada. Si es
        nueva, **reserva** la clave y devuelve ``None``. Lanza ``IdempotencyConflictError`` /
        ``IdempotencyInProgressError`` según el caso."""
        if not self._key:
            return None
        row = IdempotencyKey(
            idem_key=self._key,
            actor=self._actor,
            request_hash=_hash(self._body),
            response_status=None,
            response_body=None,
        )
        self._session.add(row)
        try:
            self._session.commit()  # reserva atómica (restricción única idem_key+actor)
            self._reservation = _Reservation(row=row, replay=None)
            return None
        except IntegrityError:
            self._session.rollback()
            existing = self._session.scalar(
                select(IdempotencyKey).where(
                    IdempotencyKey.idem_key == self._key,
                    IdempotencyKey.actor == self._actor,
                )
            )
            if existing is None:  # pragma: no cover  (carrera extremadamente rara)
                raise IdempotencyInProgressError from None
            if existing.request_hash != _hash(self._body):
                raise IdempotencyConflictError from None
            if existing.response_status is None:
                raise IdempotencyInProgressError from None
            return JSONResponse(
                status_code=existing.response_status,
                content=json.loads(existing.response_body or "null"),
            )

    def store(self, status_code: int, body: Any) -> None:
        """Guarda la respuesta (status + cuerpo serializable) contra la reserva de esta petición."""
        if self._reservation is None or self._reservation.row is None:
            return
        row = self._reservation.row
        row.response_status = status_code
        row.response_body = json.dumps(jsonable_encoder(body))
        self._session.commit()

    def release(self) -> None:
        """Libera la reserva (borra la clave) si el trabajo falló, para que el cliente pueda
        reintentar. No hace nada si no se reservó (petición sin clave o replay)."""
        if self._reservation is None or self._reservation.row is None:
            return
        self._session.delete(self._reservation.row)
        self._session.commit()
        self._reservation = None
