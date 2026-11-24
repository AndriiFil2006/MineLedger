"""student progress: question attempts, mastery credits and concept completions (Milestone 13B)

Adds three tables that reference `users`, `questions` and `concepts`; no existing table is altered.
Standard PostgreSQL only (no extensions), so it applies unchanged to Supabase.
Downgrading drops the three tables and therefore deletes recorded student progress.

Revision ID: 0003
Revises: 0002
Create Date: 2026-11-24 11:35:05.766570
"""
from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = '0003'
down_revision: str | None = '0002'
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table('question_attempts',
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('user_id', sa.Uuid(), nullable=False),
    sa.Column('question_id', sa.String(length=200), nullable=False),
    sa.Column('idempotency_key', sa.String(length=128), nullable=False),
    sa.Column('mode', sa.String(length=16), nullable=False),
    sa.Column('selected_option_id', sa.String(length=200), nullable=True),
    sa.Column('status', sa.String(length=16), nullable=False),
    sa.Column('credit_earned', sa.Boolean(), nullable=False),
    sa.Column('concept_completed', sa.Boolean(), nullable=False),
    sa.Column('xp_awarded', sa.Integer(), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('clock_timestamp()'), nullable=False),
    sa.CheckConstraint("NOT credit_earned OR (mode = 'graded' AND status = 'correct')", name=op.f('ck_question_attempts_credit_needs_graded_correct')),
    sa.CheckConstraint("mode IN ('graded', 'recharge')", name=op.f('ck_question_attempts_mode')),
    sa.CheckConstraint("status IN ('correct', 'incorrect', 'invalid_option')", name=op.f('ck_question_attempts_status')),
    sa.CheckConstraint('NOT concept_completed OR credit_earned', name=op.f('ck_question_attempts_completion_needs_credit')),
    sa.CheckConstraint('xp_awarded >= 0 AND (xp_awarded = 0 OR concept_completed)', name=op.f('ck_question_attempts_xp_needs_completion')),
    sa.ForeignKeyConstraint(['question_id'], ['questions.id'], name=op.f('fk_question_attempts_question_id_questions')),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], name=op.f('fk_question_attempts_user_id_users')),
    sa.PrimaryKeyConstraint('id', name=op.f('pk_question_attempts')),
    sa.UniqueConstraint('user_id', 'idempotency_key', name='uq_question_attempts_user_idempotency_key')
    )
    op.create_index('ix_question_attempts_user_created', 'question_attempts', ['user_id', 'created_at'], unique=False)
    op.create_index(op.f('ix_question_attempts_question_id'), 'question_attempts', ['question_id'], unique=False)
    op.create_table('concept_completions',
    sa.Column('user_id', sa.Uuid(), nullable=False),
    sa.Column('concept_id', sa.String(length=200), nullable=False),
    sa.Column('attempt_id', sa.Uuid(), nullable=False),
    sa.Column('xp_awarded', sa.Integer(), nullable=False),
    sa.Column('completed_at', sa.DateTime(timezone=True), server_default=sa.text('clock_timestamp()'), nullable=False),
    sa.CheckConstraint('xp_awarded >= 0', name=op.f('ck_concept_completions_non_negative_xp')),
    sa.ForeignKeyConstraint(['attempt_id'], ['question_attempts.id'], name=op.f('fk_concept_completions_attempt_id_question_attempts')),
    sa.ForeignKeyConstraint(['concept_id'], ['concepts.id'], name=op.f('fk_concept_completions_concept_id_concepts')),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], name=op.f('fk_concept_completions_user_id_users')),
    sa.PrimaryKeyConstraint('user_id', 'concept_id', name=op.f('pk_concept_completions')),
    sa.UniqueConstraint('attempt_id', name=op.f('uq_concept_completions_attempt_id'))
    )
    op.create_index(op.f('ix_concept_completions_concept_id'), 'concept_completions', ['concept_id'], unique=False)
    op.create_table('question_credits',
    sa.Column('user_id', sa.Uuid(), nullable=False),
    sa.Column('question_id', sa.String(length=200), nullable=False),
    sa.Column('attempt_id', sa.Uuid(), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('clock_timestamp()'), nullable=False),
    sa.ForeignKeyConstraint(['attempt_id'], ['question_attempts.id'], name=op.f('fk_question_credits_attempt_id_question_attempts')),
    sa.ForeignKeyConstraint(['question_id'], ['questions.id'], name=op.f('fk_question_credits_question_id_questions')),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], name=op.f('fk_question_credits_user_id_users')),
    sa.PrimaryKeyConstraint('user_id', 'question_id', name=op.f('pk_question_credits')),
    sa.UniqueConstraint('attempt_id', name=op.f('uq_question_credits_attempt_id'))
    )
    op.create_index(op.f('ix_question_credits_question_id'), 'question_credits', ['question_id'], unique=False)


def downgrade() -> None:
    op.drop_index(op.f('ix_question_credits_question_id'), table_name='question_credits')
    op.drop_table('question_credits')
    op.drop_index(op.f('ix_concept_completions_concept_id'), table_name='concept_completions')
    op.drop_table('concept_completions')
    op.drop_index(op.f('ix_question_attempts_question_id'), table_name='question_attempts')
    op.drop_index('ix_question_attempts_user_created', table_name='question_attempts')
    op.drop_table('question_attempts')
