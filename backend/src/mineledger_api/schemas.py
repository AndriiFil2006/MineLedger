"""Student-facing response/request models.

Answer keys (correct option IDs, journal correct accounts/amounts) and question explanations are deliberately
absent from every question retrieval schema. Explanations are returned only as grading feedback.
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class ApiModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Health(ApiModel):
    status: Literal["ok", "degraded"]
    database: Literal["ok", "unavailable"]


class Meta(ApiModel):
    """What a client checks before using this server: the API contract version and whether curriculum is imported."""

    api_version: int
    curriculum_schema_versions: list[int]
    course_count: int


class CourseSummary(ApiModel):
    id: str
    title: str
    description: str
    unit_ids: list[str]


class UnitSummary(ApiModel):
    id: str
    number: int
    title: str
    description: str
    assignment_ids: list[str]


class CourseDetail(ApiModel):
    id: str
    title: str
    description: str
    units: list[UnitSummary]


class AssignmentSummary(ApiModel):
    id: str
    title: str
    description: str
    availability: Literal["available", "coming_soon"]
    concept_ids: list[str]


class UnitDetail(ApiModel):
    id: str
    course_id: str
    number: int
    title: str
    description: str
    assignments: list[AssignmentSummary]


class ConceptSummary(ApiModel):
    id: str
    title: str
    description: str
    required_correct_answers: int
    question_count: int
    lesson_id: str | None


class AssignmentDetail(ApiModel):
    id: str
    unit_id: str
    title: str
    description: str
    availability: Literal["available", "coming_soon"]
    concepts: list[ConceptSummary]


class WorkedExample(ApiModel):
    title: str
    scenario: str
    calculation: str
    debit_entry: str
    credit_entry: str
    explanation: str


class Lesson(ApiModel):
    id: str
    title: str
    learning_objective: str
    explanation: str
    accounting_rules: list[str]
    worked_examples: list[WorkedExample]
    common_mistakes: list[str]
    takeaway: str


class ConceptDetail(ApiModel):
    id: str
    assignment_id: str
    title: str
    description: str
    required_correct_answers: int
    question_ids: list[str]
    lesson: Lesson | None


class PublicAnswerOption(ApiModel):
    id: str
    text: str


class PublicQuestion(ApiModel):
    """A multiple-choice question as shown to a learner: no answer key, no explanation."""

    id: str
    concept_id: str
    type: Literal["multiple_choice"]
    prompt: str
    difficulty: str | None
    answer_options: list[PublicAnswerOption]


class AssignmentQuestions(ApiModel):
    assignment_id: str
    questions: list[PublicQuestion]


class GradeRequest(ApiModel):
    selected_option_id: str | None = Field(
        default=None,
        description="The chosen option's stable ID. Missing, null or foreign IDs grade as invalid_option."
    )


class GradeResponse(ApiModel):
    question_id: str
    selected_option_id: str | None
    status: Literal["correct", "incorrect", "invalid_option"]
    correct: bool
    valid: bool
    feedback: str


class UserProfile(ApiModel):
    """The caller's own account. `id` is the opaque internal user ID; the provider's subject is not exposed."""

    id: uuid.UUID
    role: Literal["student", "teacher"]
    created_at: datetime


class SubmissionRequest(ApiModel):
    """The whole of what a student may assert: which option they chose. Everything else is server-side."""

    selected_option_id: str | None = Field(
        default=None,
        max_length=200,
        description="The chosen option's stable ID. Missing, null or foreign IDs are recorded as invalid_option.",
    )


class ConceptProgress(ApiModel):
    concept_id: str
    correct_answers: int = Field(description="Distinct questions answered correctly, e.g. 1 of a required 2")
    required_correct_answers: int
    completed: bool
    locked: bool = Field(description="True until every earlier concept in the assignment is completed")
    xp_awarded: int
    completed_at: datetime | None
    credited_question_ids: list[str]
    attempted_question_ids: list[str]


class AssignmentProgress(ApiModel):
    assignment_id: str
    status: Literal["not_started", "in_progress", "completed"]
    completed_concepts: int
    total_concepts: int
    xp_earned: int
    current_concept_id: str | None
    concepts: list[ConceptProgress]


class ProgressSummary(ApiModel):
    total_xp: int
    completed_concept_ids: list[str]
    assignments: list[AssignmentProgress]


class SubmissionResult(ApiModel):
    """Grading feedback plus the student's updated progress. `replayed` marks a repeated idempotency key."""

    attempt_id: uuid.UUID
    mode: Literal["graded", "recharge"]
    question_id: str
    concept_id: str
    assignment_id: str
    selected_option_id: str | None
    status: Literal["correct", "incorrect", "invalid_option"]
    correct: bool
    valid: bool
    feedback: str
    credit_earned: bool
    concept_completed: bool
    xp_awarded: int
    replayed: bool
    submitted_at: datetime
    total_xp: int
    assignment: AssignmentProgress


class AttemptRecord(ApiModel):
    attempt_id: uuid.UUID
    mode: Literal["graded", "recharge"]
    question_id: str
    concept_id: str
    assignment_id: str
    selected_option_id: str | None
    status: Literal["correct", "incorrect", "invalid_option"]
    credit_earned: bool
    concept_completed: bool
    xp_awarded: int
    submitted_at: datetime


class AttemptHistory(ApiModel):
    attempts: list[AttemptRecord]
    limit: int
    offset: int
