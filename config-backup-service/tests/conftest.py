"""Fixtures de test. La BD es un PostgreSQL real (Testcontainers, RNF-14/sin mocks de BD) sobre el
que se aplican las **migraciones Alembic** reales (fidelidad esquema↔migración)."""

from __future__ import annotations

from collections.abc import Callable, Iterator
from pathlib import Path
from uuid import UUID

import pytest
from alembic import command
from alembic.config import Config
from app.config import get_settings
from app.connectors.base import DeviceConnector
from app.db.base import make_engine
from app.db.models import (
    Backup,
    Device,
    IdempotencyKey,
    Job,
    JobResult,
    OutboxEvent,
    ProcessedEvent,
    Schedule,
)
from app.db.session import get_session
from app.deps import get_connector, get_git_store, get_job_dispatcher
from app.git_store import GitStore
from app.main import create_app
from app.security import Principal, get_principal
from app.services.jobs import run_job
from fastapi.testclient import TestClient
from sqlalchemy import Engine, delete
from sqlalchemy.orm import Session
from testcontainers.postgres import PostgresContainer

from tests.fakes import FakeConnector


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
        session.execute(delete(JobResult))
        session.execute(delete(Job))
        session.execute(delete(Schedule))
        session.execute(delete(IdempotencyKey))
        session.execute(delete(OutboxEvent))
        session.execute(delete(Backup))
        session.execute(delete(ProcessedEvent))
        session.execute(delete(Device))
        session.commit()
        yield session


@pytest.fixture
def make_api_client(pg_engine: Engine, tmp_path: Path) -> Callable[..., TestClient]:
    """Construye un TestClient con las dependencias sobreescritas (sesión de test, principal con
    roles dados, conector doble, store Git temporal). ``roles=None`` deja el auth real (401)."""

    def _make(
        roles: tuple[str, ...] | None = ("ADM",),
        connector: DeviceConnector | None = None,
        subject: str = "tester",
    ) -> TestClient:
        app = create_app()

        def _session() -> Iterator[Session]:
            with Session(pg_engine) as session:
                yield session

        app.dependency_overrides[get_session] = _session
        if roles is not None:
            principal = Principal(subject=subject, roles=frozenset(roles))
            app.dependency_overrides[get_principal] = lambda: principal
        chosen: DeviceConnector = connector or FakeConnector()
        git_store = GitStore(str(tmp_path / "repo"))
        cidrs = get_settings().allowed_scan_cidrs
        app.dependency_overrides[get_connector] = lambda: chosen
        app.dependency_overrides[get_git_store] = lambda: git_store

        def _dispatch(job_id: UUID) -> None:
            # En tests el job corre síncrono (las BackgroundTasks del TestClient se ejecutan tras la
            # respuesta) con los dobles inyectados, en su propia sesión.
            with Session(pg_engine) as job_session:
                run_job(job_session, job_id, chosen, git_store, cidrs)

        app.dependency_overrides[get_job_dispatcher] = lambda: _dispatch
        return TestClient(app)

    return _make
