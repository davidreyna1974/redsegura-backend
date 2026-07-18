"""Vista local de dispositivos + eventos procesados (idempotencia del consumidor).

Revision ID: 0001_device_view
Revises:
Create Date: 2026-07-18
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0001_device_view"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "devices",
        sa.Column("device_id", sa.Uuid(), primary_key=True),
        sa.Column("hostname", sa.String(255), nullable=False),
        sa.Column("mgmt_ipv4", sa.String(45), nullable=True),
        sa.Column("mgmt_ipv6", sa.String(45), nullable=True),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("criticality", sa.String(20), nullable=True),
        sa.Column("vendor", sa.String(255), nullable=True),
        sa.Column("model", sa.String(255), nullable=True),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_table(
        "processed_events",
        sa.Column("event_id", sa.String(255), primary_key=True),
        sa.Column("processed_at", sa.DateTime(timezone=True), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("processed_events")
    op.drop_table("devices")
