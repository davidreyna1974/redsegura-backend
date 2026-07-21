"""Fixtures de test. La BD es un PostgreSQL real (Testcontainers, RNF-14/sin mocks de BD) sobre el
que se aplican las **migraciones Alembic** reales (fidelidad esquema↔migración)."""

from __future__ import annotations

from collections.abc import Iterator

import pytest
from alembic import command
from alembic.config import Config
from app.db.base import make_engine
from app.db.models import Backup, Device, OutboxEvent, ProcessedEvent
from app.main import create_app
from fastapi.testclient import TestClient
from sqlalchemy import Engine, delete
from sqlalchemy.orm import Session
from testcontainers.postgres import PostgresContainer


@pytest.fixture
def client() -> Iterator[TestClient]:
    app = create_app()
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture(scope="session")
def pg_engine() -> Iterator[Engine]:
    with PostgresContainer("postgres:16-alpine", driver="psycopg") as postgres:
        url = postgres.get_connection_url()
        cfg = Config("alembic.ini")
        cfg.set_main_option("sqlalchemy.url", url)
        command.upgrade(cfg, "head")
        engine = make_engine(url)
        yield engine
        engine.dispose()


@pytest.fixture
def db_session(pg_engine: Engine) -> Iterator[Session]:
    with Session(pg_engine) as session:
        session.execute(delete(OutboxEvent))
        session.execute(delete(Backup))
        session.execute(delete(ProcessedEvent))
        session.execute(delete(Device))
        session.commit()
        yield session
