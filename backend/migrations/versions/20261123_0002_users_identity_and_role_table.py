"""users: one row per identity-provider account, with the server-side role (Milestone 13A)

Adds one table and touches no curriculum table. No credentials, progress or attempts are stored.

Revision ID: 0002
Revises: 0001
Create Date: 2026-11-23 11:21:51.633752
"""
from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = '0002'
down_revision: str | None = '0001'
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table('users',
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('issuer', sa.Text(), nullable=False),
    sa.Column('subject', sa.Text(), nullable=False),
    sa.Column('role', sa.String(length=20), server_default='student', nullable=False),
    sa.Column('is_active', sa.Boolean(), server_default=sa.text('true'), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
    sa.Column('role_changed_at', sa.DateTime(timezone=True), nullable=True),
    sa.CheckConstraint("role IN ('student', 'teacher')", name=op.f('ck_users_role')),
    sa.PrimaryKeyConstraint('id', name=op.f('pk_users')),
    sa.UniqueConstraint('issuer', 'subject', name='uq_users_issuer_subject')
    )


def downgrade() -> None:
    op.drop_table('users')
