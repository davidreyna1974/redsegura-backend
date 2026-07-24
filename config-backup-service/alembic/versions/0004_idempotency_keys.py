"""Claves de idempotencia de escritura (RN-CB7).

Revision ID: 0004_idempotency_keys
Revises: 0003_jobs_schedules
Create Date: 2026-07-24
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0004_idempotency_keys"
down_revision = "0003_jobs_schedules"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "idempotency_keys",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("idem_key", sa.String(255), nullable=False),
        sa.Column("actor", sa.String(255), nullable=False),
        sa.Column("request_hash", sa.String(64), nullable=False),
        sa.Column("response_status", sa.Integer(), nullable=True),
        sa.Column("response_body", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("idem_key", "actor", name="uq_idempotency_key_actor"),
    )


def downgrade() -> None:
    op.drop_table("idempotency_keys")
