"""Relational curriculum tables, the `users` identity table (13A) and student progress tables (13B).

Text IDs from the curriculum JSON are primary keys. Reference arrays become `sort_order` positions.
Every curated row records the `bundle_id` that imported it, so an import can only change its own rows.
"""

from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    ForeignKeyConstraint,
    Index,
    Integer,
    MetaData,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    Uuid,
    func,
    true,
)
from sqlalchemy.dialects.postgresql import ARRAY
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship

NAMING = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}

ID = String(200)


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING)


def _bundle_fk() -> Mapped[str]:
    return mapped_column(ID, ForeignKey("curriculum_bundles.id"), nullable=False, index=True)


class CurriculumBundle(Base):
    """One imported curriculum file (e.g. `accounting_101`) and the provenance of its last import."""

    __tablename__ = "curriculum_bundles"
    id: Mapped[str] = mapped_column(ID, primary_key=True)
    schema_version: Mapped[int] = mapped_column(Integer, nullable=False)
    content_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    source: Mapped[str] = mapped_column(Text, nullable=False)
    imported_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


class Course(Base):
    __tablename__ = "courses"
    id: Mapped[str] = mapped_column(ID, primary_key=True)
    bundle_id: Mapped[str] = _bundle_fk()
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False)
    title: Mapped[str] = mapped_column(Text, nullable=False)
    description: Mapped[str] = mapped_column(Text, nullable=False)
    units: Mapped[list[Unit]] = relationship(back_populates="course", order_by="Unit.sort_order")


class Unit(Base):
    __tablename__ = "units"
    __table_args__ = (
        UniqueConstraint("course_id", "sort_order", name="uq_units_course_sort", deferrable=True, initially="DEFERRED"),
        UniqueConstraint("course_id", "unit_number", name="uq_units_course_number", deferrable=True, initially="DEFERRED"),
        CheckConstraint("unit_number >= 1", name="positive_number"),
    )
    id: Mapped[str] = mapped_column(ID, primary_key=True)
    bundle_id: Mapped[str] = _bundle_fk()
    course_id: Mapped[str] = mapped_column(ID, ForeignKey("courses.id"), nullable=False, index=True)
    unit_number: Mapped[int] = mapped_column(Integer, nullable=False)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False)
    title: Mapped[str] = mapped_column(Text, nullable=False)
    description: Mapped[str] = mapped_column(Text, nullable=False)
    course: Mapped[Course] = relationship(back_populates="units")
    assignments: Mapped[list[Assignment]] = relationship(back_populates="unit", order_by="Assignment.sort_order")


class Assignment(Base):
    __tablename__ = "assignments"
    __table_args__ = (
        UniqueConstraint("unit_id", "sort_order", name="uq_assignments_unit_sort", deferrable=True, initially="DEFERRED"),
        CheckConstraint("availability IN ('available', 'coming_soon')", name="availability"),
    )
    id: Mapped[str] = mapped_column(ID, primary_key=True)
    bundle_id: Mapped[str] = _bundle_fk()
    unit_id: Mapped[str] = mapped_column(ID, ForeignKey("units.id"), nullable=False, index=True)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False)
    title: Mapped[str] = mapped_column(Text, nullable=False)
    description: Mapped[str] = mapped_column(Text, nullable=False)
    availability: Mapped[str] = mapped_column(String(32), nullable=False)
    unit: Mapped[Unit] = relationship(back_populates="assignments")
    concepts: Mapped[list[Concept]] = relationship(back_populates="assignment", order_by="Concept.sort_order")


class Concept(Base):
    __tablename__ = "concepts"
    __table_args__ = (
        UniqueConstraint("assignment_id", "sort_order", name="uq_concepts_assignment_sort", deferrable=True, initially="DEFERRED"),
        CheckConstraint("required_correct_answers >= 1", name="positive_required"),
    )
    id: Mapped[str] = mapped_column(ID, primary_key=True)
    bundle_id: Mapped[str] = _bundle_fk()
    assignment_id: Mapped[str] = mapped_column(ID, ForeignKey("assignments.id"), nullable=False, index=True)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False)
    title: Mapped[str] = mapped_column(Text, nullable=False)
    description: Mapped[str] = mapped_column(Text, nullable=False)
    required_correct_answers: Mapped[int] = mapped_column(Integer, nullable=False)
    assignment: Mapped[Assignment] = relationship(back_populates="concepts")
    questions: Mapped[list[Question]] = relationship(back_populates="concept", order_by="Question.sort_order")
    lesson: Mapped[ConceptLesson | None] = relationship(back_populates="concept", uselist=False)


class JournalLab(Base):
    """Standalone journal practice container (`intro_journal_practice`), separate from course mastery."""

    __tablename__ = "journal_labs"
    id: Mapped[str] = mapped_column(ID, primary_key=True)
    bundle_id: Mapped[str] = _bundle_fk()
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False)
    title: Mapped[str] = mapped_column(Text, nullable=False)
    questions: Mapped[list[Question]] = relationship(back_populates="lab", order_by="Question.sort_order")


class Question(Base):
    """Multiple-choice (owned by a concept) or journal-entry (owned by a lab) question.

    Answer keys (`correct_option_id`, journal correct accounts/amount) are stored here and must never be
    copied into student-facing response schemas.
    """

    __tablename__ = "questions"
    __table_args__ = (
        CheckConstraint("question_type IN ('multiple_choice', 'journal_entry')", name="question_type"),
        CheckConstraint(
            "(question_type = 'multiple_choice' AND concept_id IS NOT NULL AND lab_id IS NULL"
            " AND correct_option_id IS NOT NULL AND correct_debit_account IS NULL"
            " AND correct_credit_account IS NULL AND correct_amount IS NULL)"
            " OR (question_type = 'journal_entry' AND lab_id IS NOT NULL AND concept_id IS NULL"
            " AND correct_option_id IS NULL AND correct_debit_account IS NOT NULL"
            " AND correct_credit_account IS NOT NULL AND correct_amount > 0"
            " AND correct_debit_account <> correct_credit_account)",
            name="owner_and_answer_key",
        ),
        UniqueConstraint("concept_id", "sort_order", name="uq_questions_concept_sort", deferrable=True, initially="DEFERRED"),
        UniqueConstraint("lab_id", "sort_order", name="uq_questions_lab_sort", deferrable=True, initially="DEFERRED"),
        # The correct option must be one of this question's own options. Deferred: question and options
        # are written in the same transaction, and use_alter breaks the questions<->options cycle.
        ForeignKeyConstraint(
            ["id", "correct_option_id"],
            ["answer_options.question_id", "answer_options.option_id"],
            name="fk_questions_correct_option",
            deferrable=True,
            initially="DEFERRED",
            use_alter=True,
        ),
    )
    id: Mapped[str] = mapped_column(ID, primary_key=True)
    bundle_id: Mapped[str] = _bundle_fk()
    question_type: Mapped[str] = mapped_column(String(32), nullable=False)
    concept_id: Mapped[str | None] = mapped_column(ID, ForeignKey("concepts.id"), index=True)
    lab_id: Mapped[str | None] = mapped_column(ID, ForeignKey("journal_labs.id"), index=True)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False)
    prompt: Mapped[str] = mapped_column(Text, nullable=False)
    explanation: Mapped[str] = mapped_column(Text, nullable=False)
    difficulty: Mapped[str | None] = mapped_column(Text)
    correct_option_id: Mapped[str | None] = mapped_column(ID)
    correct_debit_account: Mapped[str | None] = mapped_column(String(64))
    correct_credit_account: Mapped[str | None] = mapped_column(String(64))
    correct_amount: Mapped[Decimal | None] = mapped_column(Numeric(34, 2))
    concept: Mapped[Concept | None] = relationship(back_populates="questions")
    lab: Mapped[JournalLab | None] = relationship(back_populates="questions")
    options: Mapped[list[AnswerOption]] = relationship(
        back_populates="question",
        order_by="AnswerOption.sort_order",
        cascade="all, delete-orphan",
        foreign_keys="AnswerOption.question_id",
    )
    account_choices: Mapped[list[JournalAccountChoice]] = relationship(
        back_populates="question",
        order_by=lambda: [JournalAccountChoice.side, JournalAccountChoice.sort_order],
        cascade="all, delete-orphan",
    )


class AnswerOption(Base):
    """Option IDs are scoped to their question: the key is (question ID, option ID)."""

    __tablename__ = "answer_options"
    __table_args__ = (
        UniqueConstraint("question_id", "sort_order", name="uq_answer_options_question_sort", deferrable=True, initially="DEFERRED"),
    )
    question_id: Mapped[str] = mapped_column(ID, ForeignKey("questions.id", ondelete="CASCADE"), primary_key=True)
    option_id: Mapped[str] = mapped_column(ID, primary_key=True)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False)
    text: Mapped[str] = mapped_column(Text, nullable=False)
    question: Mapped[Question] = relationship(back_populates="options", foreign_keys=[question_id])


class JournalAccountChoice(Base):
    """Selectable debit/credit accounts for a journal question, by stable `Account` enum name."""

    __tablename__ = "journal_account_choices"
    __table_args__ = (
        CheckConstraint("side IN ('debit', 'credit')", name="side"),
        UniqueConstraint("question_id", "side", "sort_order", name="uq_journal_choices_sort", deferrable=True, initially="DEFERRED"),
    )
    question_id: Mapped[str] = mapped_column(ID, ForeignKey("questions.id", ondelete="CASCADE"), primary_key=True)
    side: Mapped[str] = mapped_column(String(8), primary_key=True)
    account_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False)
    question: Mapped[Question] = relationship(back_populates="account_choices")


class ConceptLesson(Base):
    """At most one lesson per concept; the concept's `lesson_id` is derived from this unique link."""

    __tablename__ = "concept_lessons"
    id: Mapped[str] = mapped_column(ID, primary_key=True)
    bundle_id: Mapped[str] = _bundle_fk()
    concept_id: Mapped[str] = mapped_column(ID, ForeignKey("concepts.id"), nullable=False, unique=True)
    title: Mapped[str] = mapped_column(Text, nullable=False)
    learning_objective: Mapped[str] = mapped_column(Text, nullable=False)
    explanation: Mapped[str] = mapped_column(Text, nullable=False)
    accounting_rules: Mapped[list[str]] = mapped_column(ARRAY(Text), nullable=False)
    common_mistakes: Mapped[list[str]] = mapped_column(ARRAY(Text), nullable=False)
    takeaway: Mapped[str] = mapped_column(Text, nullable=False)
    concept: Mapped[Concept] = relationship(back_populates="lesson")
    worked_examples: Mapped[list[LessonWorkedExample]] = relationship(
        back_populates="lesson", order_by="LessonWorkedExample.sort_order", cascade="all, delete-orphan"
    )


class LessonWorkedExample(Base):
    __tablename__ = "lesson_worked_examples"
    lesson_id: Mapped[str] = mapped_column(ID, ForeignKey("concept_lessons.id", ondelete="CASCADE"), primary_key=True)
    sort_order: Mapped[int] = mapped_column(Integer, primary_key=True)
    title: Mapped[str] = mapped_column(Text, nullable=False)
    scenario: Mapped[str] = mapped_column(Text, nullable=False)
    calculation: Mapped[str] = mapped_column(Text, nullable=False)
    debit_entry: Mapped[str] = mapped_column(Text, nullable=False)
    credit_entry: Mapped[str] = mapped_column(Text, nullable=False)
    explanation: Mapped[str] = mapped_column(Text, nullable=False)
    lesson: Mapped[ConceptLesson] = relationship(back_populates="worked_examples")


ROLE_STUDENT = "student"
ROLE_TEACHER = "teacher"
ROLES = (ROLE_STUDENT, ROLE_TEACHER)


class User(Base):
    """A person known to the backend: one row per identity-provider account (`issuer` + `subject`).

    `id` is the opaque internal identifier other tables should reference. The role lives here, never in
    a token, and only the operator CLI changes it. No credentials are stored.
    """

    __tablename__ = "users"
    __table_args__ = (
        UniqueConstraint("issuer", "subject", name="uq_users_issuer_subject"),
        CheckConstraint("role IN ('student', 'teacher')", name="role"),
    )
    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    issuer: Mapped[str] = mapped_column(Text, nullable=False)
    subject: Mapped[str] = mapped_column(Text, nullable=False)
    role: Mapped[str] = mapped_column(String(20), nullable=False, default=ROLE_STUDENT, server_default=ROLE_STUDENT)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True, server_default=true())
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    role_changed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


MODE_GRADED = "graded"
MODE_RECHARGE = "recharge"


def _user_fk(**kwargs) -> Mapped[uuid.UUID]:
    return mapped_column(Uuid, ForeignKey("users.id"), nullable=False, **kwargs)


class QuestionAttempt(Base):
    """One processed multiple-choice submission: the append-only history, and the idempotency record.

    `(user_id, idempotency_key)` is unique, so a retried request can never be recorded twice. The reward
    columns say what this attempt earned; the check constraints make a rewarded Recharge attempt, or XP
    without a completed concept, impossible to store.
    """

    __tablename__ = "question_attempts"
    __table_args__ = (
        UniqueConstraint("user_id", "idempotency_key", name="uq_question_attempts_user_idempotency_key"),
        CheckConstraint("mode IN ('graded', 'recharge')", name="mode"),
        CheckConstraint("status IN ('correct', 'incorrect', 'invalid_option')", name="status"),
        CheckConstraint("NOT credit_earned OR (mode = 'graded' AND status = 'correct')", name="credit_needs_graded_correct"),
        CheckConstraint("NOT concept_completed OR credit_earned", name="completion_needs_credit"),
        CheckConstraint("xp_awarded >= 0 AND (xp_awarded = 0 OR concept_completed)", name="xp_needs_completion"),
        Index("ix_question_attempts_user_created", "user_id", "created_at"),
    )
    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = _user_fk()
    question_id: Mapped[str] = mapped_column(ID, ForeignKey("questions.id"), nullable=False, index=True)
    idempotency_key: Mapped[str] = mapped_column(String(128), nullable=False)
    mode: Mapped[str] = mapped_column(String(16), nullable=False)
    selected_option_id: Mapped[str | None] = mapped_column(ID)
    status: Mapped[str] = mapped_column(String(16), nullable=False)
    credit_earned: Mapped[bool] = mapped_column(Boolean, nullable=False)
    concept_completed: Mapped[bool] = mapped_column(Boolean, nullable=False)
    xp_awarded: Mapped[int] = mapped_column(Integer, nullable=False)
    # clock_timestamp(), not now(): attempts made in one transaction-second still sort in the order they happened.
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.clock_timestamp()
    )


class QuestionCredit(Base):
    """A mastery credit: this student answered this question correctly. At most one per question, ever."""

    __tablename__ = "question_credits"
    user_id: Mapped[uuid.UUID] = _user_fk(primary_key=True)
    question_id: Mapped[str] = mapped_column(ID, ForeignKey("questions.id"), primary_key=True, index=True)
    attempt_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("question_attempts.id"), nullable=False, unique=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.clock_timestamp())


class ConceptCompletion(Base):
    """A completed concept and the XP it paid. The primary key is what makes that XP payable only once."""

    __tablename__ = "concept_completions"
    __table_args__ = (CheckConstraint("xp_awarded >= 0", name="non_negative_xp"),)
    user_id: Mapped[uuid.UUID] = _user_fk(primary_key=True)
    concept_id: Mapped[str] = mapped_column(ID, ForeignKey("concepts.id"), primary_key=True, index=True)
    attempt_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("question_attempts.id"), nullable=False, unique=True)
    xp_awarded: Mapped[int] = mapped_column(Integer, nullable=False)
    completed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.clock_timestamp())
