"""Respaldos (metadatos) + outbox de eventos config.*.

Revision ID: 0002_backups_outbox
Revises: 0001_device_view
Create Date: 2026-07-21
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0002_backups_outbox"
down_revision = "0001_device_view"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "backups",
        sa.Column("backup_id", sa.Uuid(), primary_key=True),
        sa.Column("device_id", sa.Uuid(), nullable=False),
        sa.Column("commit", sa.String(64), nullable=True),
        sa.Column("captured_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("unsaved_changes", sa.Boolean(), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("failure_reason", sa.String(500), nullable=True),
        sa.Column("created_by", sa.String(255), nullable=False),
    )
    op.create_index("ix_backups_device_id", "backups", ["device_id"])
    op.create_table(
        "outbox_events",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("event_id", sa.Uuid(), nullable=False, unique=True),
        sa.Column("event_type", sa.String(100), nullable=False),
        sa.Column("payload", sa.Text(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=True),
    )


def downgrade() -> None:
    op.drop_table("outbox_events")
    op.drop_index("ix_backups_device_id", table_name="backups")
    op.drop_table("backups")
