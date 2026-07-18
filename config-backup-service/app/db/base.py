"""Base declarativa y fábrica de engine de SQLAlchemy. Se usa SQLAlchemy **síncrono** (2.0) por
simplicidad y tipado; FastAPI ejecuta las llamadas a BD en su threadpool."""

from __future__ import annotations

from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    """Base común de los modelos ORM."""


def make_engine(db_url: str) -> Engine:
    return create_engine(db_url, future=True, pool_pre_ping=True)
