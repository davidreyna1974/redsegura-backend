"""RN-CB7 (idempotencia de escritura): un reintento con la misma ``Idempotency-Key`` y el mismo
cuerpo no duplica el efecto y devuelve la respuesta guardada; con distinto cuerpo → 409. Cubre
HALLAZGO-QA-CBS-02."""

from __future__ import annotations

from collections.abc import Callable
from uuid import uuid4

from app.db.models import Job, Schedule
from fastapi.testclient import TestClient
from sqlalchemy import func, select
from sqlalchemy.orm import Session

ClientFactory = Callable[..., TestClient]
BATCH = {"scope": "all"}
SCHED = {"type": "backup", "cron": "0 2 * * *", "target": {"scope": "all"}}


def test_backup_batch_same_key_same_body_does_not_duplicate(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    client = make_api_client(roles=("ADM",))
    headers = {"Idempotency-Key": "abc-123"}

    r1 = client.post("/api/v1/backups", json=BATCH, headers=headers)
    r2 = client.post("/api/v1/backups", json=BATCH, headers=headers)

    assert r1.status_code == 202
    assert r2.status_code == 202
    # Misma respuesta (mismo jobId) y un solo job creado.
    assert r1.json()["jobId"] == r2.json()["jobId"]
    assert db_session.scalar(select(func.count()).select_from(Job)) == 1


def test_backup_batch_same_key_different_body_conflicts(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    client = make_api_client(roles=("ADM",))
    headers = {"Idempotency-Key": "dup-key"}

    r1 = client.post("/api/v1/backups", json={"scope": "all"}, headers=headers)
    r2 = client.post(
        "/api/v1/backups", json={"deviceIds": [str(uuid4())]}, headers=headers
    )

    assert r1.status_code == 202
    assert r2.status_code == 409
    assert r2.headers["content-type"].startswith("application/problem+json")


def test_different_keys_create_separate_jobs(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    client = make_api_client(roles=("ADM",))

    client.post("/api/v1/backups", json=BATCH, headers={"Idempotency-Key": "k1"})
    client.post("/api/v1/backups", json=BATCH, headers={"Idempotency-Key": "k2"})

    assert db_session.scalar(select(func.count()).select_from(Job)) == 2


def test_no_key_is_not_deduplicated(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    client = make_api_client(roles=("ADM",))

    client.post("/api/v1/backups", json=BATCH)
    client.post("/api/v1/backups", json=BATCH)

    assert db_session.scalar(select(func.count()).select_from(Job)) == 2


def test_key_scoped_by_actor(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    # La misma clave usada por dos usuarios distintos no colisiona (acotada por actor).
    admin = make_api_client(roles=("ADM",), subject="user-a")
    other = make_api_client(roles=("ADM",), subject="user-b")
    headers = {"Idempotency-Key": "shared"}

    admin.post("/api/v1/backups", json=BATCH, headers=headers)
    other.post("/api/v1/backups", json=BATCH, headers=headers)

    assert db_session.scalar(select(func.count()).select_from(Job)) == 2


def test_schedule_idempotent(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    client = make_api_client(roles=("ADM",))
    headers = {"Idempotency-Key": "sched-1"}

    r1 = client.post("/api/v1/schedules", json=SCHED, headers=headers)
    r2 = client.post("/api/v1/schedules", json=SCHED, headers=headers)

    assert r1.status_code == 201
    assert r2.status_code == 201
    assert r1.json()["id"] == r2.json()["id"]
    assert db_session.scalar(select(func.count()).select_from(Schedule)) == 1


def test_failed_request_releases_key_for_retry(
    db_session: Session, make_api_client: ClientFactory
) -> None:
    # Un cron inválido (400) libera la clave: un reintento corregido con la misma clave funciona.
    client = make_api_client(roles=("ADM",))
    headers = {"Idempotency-Key": "retry-me"}

    bad = client.post(
        "/api/v1/schedules",
        json={"type": "backup", "cron": "malo", "target": {"scope": "all"}},
        headers=headers,
    )
    good = client.post("/api/v1/schedules", json=SCHED, headers=headers)

    assert bad.status_code == 400
    assert good.status_code == 201  # la clave se liberó tras el fallo
