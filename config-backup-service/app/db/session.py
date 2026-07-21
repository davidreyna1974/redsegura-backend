"""Dependencia de sesión de BD para FastAPI (engine singleton por proceso)."""

from __future__ import annotations

from collections.abc import Iterator
from functools import lru_cache

from sqlalchemy import Engine
from sqlalchemy.orm import Session

from app.config import get_settings
from app.db.base import make_engine


@lru_cache(maxsize=1)
def _engine() -> Engine:
    return make_engine(get_settings().db_url)


def get_session() -> Iterator[Session]:
    with Session(_engine()) as session:
        yield session
