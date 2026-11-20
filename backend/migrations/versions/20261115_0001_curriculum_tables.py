"""curriculum tables: courses, units, assignments, concepts, questions, options, lessons, labs

No learner progress or authentication tables (Milestone 11A).

Revision ID: 0001
Revises: 
Create Date: 2026-11-15 20:16:28.803154
"""
from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = '0001'
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table('curriculum_bundles',
    sa.Column('id', sa.String(length=200), nullable=False),
    sa.Column('schema_version', sa.Integer(), nullable=False),
    sa.Column('content_sha256', sa.String(length=64), nullable=False),
    sa.Column('source', sa.Text(), nullable=False),
    sa.Column('imported_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
    sa.PrimaryKeyConstraint('id', name=op.f('pk_curriculum_bundles'))
    )
    op.create_table('courses',
    sa.Column('id', sa.String(length=200), nullable=False),
    sa.Column('bundle_id', sa.String(length=200), nullable=False),
    sa.Column('sort_order', sa.Integer(), nullable=False),
    sa.Column('title', sa.Text(), nullable=False),
    sa.Column('description', sa.Text(), nullable=False),
    sa.ForeignKeyConstraint(['bundle_id'], ['curriculum_bundles.id'], name=op.f('fk_courses_bundle_id_curriculum_bundles')),
    sa.PrimaryKeyConstraint('id', name=op.f('pk_courses'))
    )
    op.create_index(op.f('ix_courses_bundle_id'), 'courses', ['bundle_id'], unique=False)
    op.create_table('journal_labs',
    sa.Column('id', sa.String(length=200), nullable=False),
    sa.Column('bundle_id', sa.String(length=200), nullable=False),
    sa.Column('sort_order', sa.Integer(), nullable=False),
    sa.Column('title', sa.Text(), nullable=False),
    sa.ForeignKeyConstraint(['bundle_id'], ['curriculum_bundles.id'], name=op.f('fk_journal_labs_bundle_id_curriculum_bundles')),
    sa.PrimaryKeyConstraint('id', name=op.f('pk_journal_labs'))
    )
    op.create_index(op.f('ix_journal_labs_bundle_id'), 'journal_labs', ['bundle_id'], unique=False)
    op.create_table('units',
    sa.Column('id', sa.String(length=200), nullable=False),
    sa.Column('bundle_id', sa.String(length=200), nullable=False),
    sa.Column('course_id', sa.String(length=200), nullable=False),
    sa.Column('unit_number', sa.Integer(), nullable=False),
    sa.Column('sort_order', sa.Integer(), nullable=False),
    sa.Column('title', sa.Text(), nullable=False),
    sa.Column('description', sa.Text(), nullable=False),
    sa.CheckConstraint('unit_number >= 1', name=op.f('ck_units_positive_number')),
    sa.ForeignKeyConstraint(['bundle_id'], ['curriculum_bundles.id'], name=op.f('fk_units_bundle_id_curriculum_bundles')),
    sa.ForeignKeyConstraint(['course_id'], ['courses.id'], name=op.f('fk_units_course_id_courses')),
    sa.PrimaryKeyConstraint('id', name=op.f('pk_units')),
    sa.UniqueConstraint('course_id', 'sort_order', deferrable=True, initially='DEFERRED', name='uq_units_course_sort'),
    sa.UniqueConstraint('course_id', 'unit_number', deferrable=True, initially='DEFERRED', name='uq_units_course_number')
    )
    op.create_index(op.f('ix_units_bundle_id'), 'units', ['bundle_id'], unique=False)
    op.create_index(op.f('ix_units_course_id'), 'units', ['course_id'], unique=False)
    op.create_table('assignments',
    sa.Column('id', sa.String(length=200), nullable=False),
    sa.Column('bundle_id', sa.String(length=200), nullable=False),
    sa.Column('unit_id', sa.String(length=200), nullable=False),
    sa.Column('sort_order', sa.Integer(), nullable=False),
    sa.Column('title', sa.Text(), nullable=False),
    sa.Column('description', sa.Text(), nullable=False),
    sa.Column('availability', sa.String(length=32), nullable=False),
    sa.CheckConstraint("availability IN ('available', 'coming_soon')", name=op.f('ck_assignments_availability')),
    sa.ForeignKeyConstraint(['bundle_id'], ['curriculum_bundles.id'], name=op.f('fk_assignments_bundle_id_curriculum_bundles')),
    sa.ForeignKeyConstraint(['unit_id'], ['units.id'], name=op.f('fk_assignments_unit_id_units')),
    sa.PrimaryKeyConstraint('id', name=op.f('pk_assignments')),
    sa.UniqueConstraint('unit_id', 'sort_order', deferrable=True, initially='DEFERRED', name='uq_assignments_unit_sort')
    )
    op.create_index(op.f('ix_assignments_bundle_id'), 'assignments', ['bundle_id'], unique=False)
    op.create_index(op.f('ix_assignments_unit_id'), 'assignments', ['unit_id'], unique=False)
    op.create_table('concepts',
    sa.Column('id', sa.String(length=200), nullable=False),
    sa.Column('bundle_id', sa.String(length=200), nullable=False),
    sa.Column('assignment_id', sa.String(length=200), nullable=False),
    sa.Column('sort_order', sa.Integer(), nullable=False),
    sa.Column('title', sa.Text(), nullable=False),
    sa.Column('description', sa.Text(), nullable=False),
    sa.Column('required_correct_answers', sa.Integer(), nullable=False),
    sa.CheckConstraint('required_correct_answers >= 1', name=op.f('ck_concepts_positive_required')),
    sa.ForeignKeyConstraint(['assignment_id'], ['assignments.id'], name=op.f('fk_concepts_assignment_id_assignments')),
    sa.ForeignKeyConstraint(['bundle_id'], ['curriculum_bundles.id'], name=op.f('fk_concepts_bundle_id_curriculum_bundles')),
    sa.PrimaryKeyConstraint('id', name=op.f('pk_concepts')),
    sa.UniqueConstraint('assignment_id', 'sort_order', deferrable=True, initially='DEFERRED', name='uq_concepts_assignment_sort')
    )
    op.create_index(op.f('ix_concepts_assignment_id'), 'concepts', ['assignment_id'], unique=False)
    op.create_index(op.f('ix_concepts_bundle_id'), 'concepts', ['bundle_id'], unique=False)
    op.create_table('concept_lessons',
    sa.Column('id', sa.String(length=200), nullable=False),
    sa.Column('bundle_id', sa.String(length=200), nullable=False),
    sa.Column('concept_id', sa.String(length=200), nullable=False),
    sa.Column('title', sa.Text(), nullable=False),
    sa.Column('learning_objective', sa.Text(), nullable=False),
    sa.Column('explanation', sa.Text(), nullable=False),
    sa.Column('accounting_rules', postgresql.ARRAY(sa.Text()), nullable=False),
    sa.Column('common_mistakes', postgresql.ARRAY(sa.Text()), nullable=False),
    sa.Column('takeaway', sa.Text(), nullable=False),
    sa.ForeignKeyConstraint(['bundle_id'], ['curriculum_bundles.id'], name=op.f('fk_concept_lessons_bundle_id_curriculum_bundles')),
    sa.ForeignKeyConstraint(['concept_id'], ['concepts.id'], name=op.f('fk_concept_lessons_concept_id_concepts')),
    sa.PrimaryKeyConstraint('id', name=op.f('pk_concept_lessons')),
    sa.UniqueConstraint('concept_id', name=op.f('uq_concept_lessons_concept_id'))
    )
    op.create_index(op.f('ix_concept_lessons_bundle_id'), 'concept_lessons', ['bundle_id'], unique=False)
    op.create_table('questions',
    sa.Column('id', sa.String(length=200), nullable=False),
    sa.Column('bundle_id', sa.String(length=200), nullable=False),
    sa.Column('question_type', sa.String(length=32), nullable=False),
    sa.Column('concept_id', sa.String(length=200), nullable=True),
    sa.Column('lab_id', sa.String(length=200), nullable=True),
    sa.Column('sort_order', sa.Integer(), nullable=False),
    sa.Column('prompt', sa.Text(), nullable=False),
    sa.Column('explanation', sa.Text(), nullable=False),
    sa.Column('difficulty', sa.Text(), nullable=True),
    sa.Column('correct_option_id', sa.String(length=200), nullable=True),
    sa.Column('correct_debit_account', sa.String(length=64), nullable=True),
    sa.Column('correct_credit_account', sa.String(length=64), nullable=True),
    sa.Column('correct_amount', sa.Numeric(precision=34, scale=2), nullable=True),
    sa.CheckConstraint("(question_type = 'multiple_choice' AND concept_id IS NOT NULL AND lab_id IS NULL AND correct_option_id IS NOT NULL AND correct_debit_account IS NULL AND correct_credit_account IS NULL AND correct_amount IS NULL) OR (question_type = 'journal_entry' AND lab_id IS NOT NULL AND concept_id IS NULL AND correct_option_id IS NULL AND correct_debit_account IS NOT NULL AND correct_credit_account IS NOT NULL AND correct_amount > 0 AND correct_debit_account <> correct_credit_account)", name=op.f('ck_questions_owner_and_answer_key')),
    sa.CheckConstraint("question_type IN ('multiple_choice', 'journal_entry')", name=op.f('ck_questions_question_type')),
    sa.ForeignKeyConstraint(['bundle_id'], ['curriculum_bundles.id'], name=op.f('fk_questions_bundle_id_curriculum_bundles')),
    sa.ForeignKeyConstraint(['concept_id'], ['concepts.id'], name=op.f('fk_questions_concept_id_concepts')),
    sa.ForeignKeyConstraint(['lab_id'], ['journal_labs.id'], name=op.f('fk_questions_lab_id_journal_labs')),
    sa.PrimaryKeyConstraint('id', name=op.f('pk_questions')),
    sa.UniqueConstraint('concept_id', 'sort_order', deferrable=True, initially='DEFERRED', name='uq_questions_concept_sort'),
    sa.UniqueConstraint('lab_id', 'sort_order', deferrable=True, initially='DEFERRED', name='uq_questions_lab_sort')
    )
    op.create_index(op.f('ix_questions_bundle_id'), 'questions', ['bundle_id'], unique=False)
    op.create_index(op.f('ix_questions_concept_id'), 'questions', ['concept_id'], unique=False)
    op.create_index(op.f('ix_questions_lab_id'), 'questions', ['lab_id'], unique=False)
    op.create_table('answer_options',
    sa.Column('question_id', sa.String(length=200), nullable=False),
    sa.Column('option_id', sa.String(length=200), nullable=False),
    sa.Column('sort_order', sa.Integer(), nullable=False),
    sa.Column('text', sa.Text(), nullable=False),
    sa.ForeignKeyConstraint(['question_id'], ['questions.id'], name=op.f('fk_answer_options_question_id_questions'), ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('question_id', 'option_id', name=op.f('pk_answer_options')),
    sa.UniqueConstraint('question_id', 'sort_order', deferrable=True, initially='DEFERRED', name='uq_answer_options_question_sort')
    )
    # Created after answer_options to break the questions <-> answer_options cycle. Deferred so a question
    # and its options can be written in one transaction; it guarantees the key is one of its own options.
    op.create_foreign_key(
        'fk_questions_correct_option', 'questions', 'answer_options',
        ['id', 'correct_option_id'], ['question_id', 'option_id'], deferrable=True, initially='DEFERRED',
    )
    op.create_table('journal_account_choices',
    sa.Column('question_id', sa.String(length=200), nullable=False),
    sa.Column('side', sa.String(length=8), nullable=False),
    sa.Column('account_id', sa.String(length=64), nullable=False),
    sa.Column('sort_order', sa.Integer(), nullable=False),
    sa.CheckConstraint("side IN ('debit', 'credit')", name=op.f('ck_journal_account_choices_side')),
    sa.ForeignKeyConstraint(['question_id'], ['questions.id'], name=op.f('fk_journal_account_choices_question_id_questions'), ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('question_id', 'side', 'account_id', name=op.f('pk_journal_account_choices')),
    sa.UniqueConstraint('question_id', 'side', 'sort_order', deferrable=True, initially='DEFERRED', name='uq_journal_choices_sort')
    )
    op.create_table('lesson_worked_examples',
    sa.Column('lesson_id', sa.String(length=200), nullable=False),
    sa.Column('sort_order', sa.Integer(), nullable=False),
    sa.Column('title', sa.Text(), nullable=False),
    sa.Column('scenario', sa.Text(), nullable=False),
    sa.Column('calculation', sa.Text(), nullable=False),
    sa.Column('debit_entry', sa.Text(), nullable=False),
    sa.Column('credit_entry', sa.Text(), nullable=False),
    sa.Column('explanation', sa.Text(), nullable=False),
    sa.ForeignKeyConstraint(['lesson_id'], ['concept_lessons.id'], name=op.f('fk_lesson_worked_examples_lesson_id_concept_lessons'), ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('lesson_id', 'sort_order', name=op.f('pk_lesson_worked_examples'))
    )


def downgrade() -> None:
    op.drop_constraint('fk_questions_correct_option', 'questions', type_='foreignkey')
    op.drop_table('lesson_worked_examples')
    op.drop_table('journal_account_choices')
    op.drop_table('answer_options')
    op.drop_index(op.f('ix_questions_lab_id'), table_name='questions')
    op.drop_index(op.f('ix_questions_concept_id'), table_name='questions')
    op.drop_index(op.f('ix_questions_bundle_id'), table_name='questions')
    op.drop_table('questions')
    op.drop_index(op.f('ix_concept_lessons_bundle_id'), table_name='concept_lessons')
    op.drop_table('concept_lessons')
    op.drop_index(op.f('ix_concepts_bundle_id'), table_name='concepts')
    op.drop_index(op.f('ix_concepts_assignment_id'), table_name='concepts')
    op.drop_table('concepts')
    op.drop_index(op.f('ix_assignments_unit_id'), table_name='assignments')
    op.drop_index(op.f('ix_assignments_bundle_id'), table_name='assignments')
    op.drop_table('assignments')
    op.drop_index(op.f('ix_units_course_id'), table_name='units')
    op.drop_index(op.f('ix_units_bundle_id'), table_name='units')
    op.drop_table('units')
    op.drop_index(op.f('ix_journal_labs_bundle_id'), table_name='journal_labs')
    op.drop_table('journal_labs')
    op.drop_index(op.f('ix_courses_bundle_id'), table_name='courses')
    op.drop_table('courses')
    op.drop_table('curriculum_bundles')
