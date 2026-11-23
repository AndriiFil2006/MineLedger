"""Student-facing response/request models.

Answer keys (correct option IDs, journal correct accounts/amounts) and question explanations are deliberately
absent from every question retrieval schema. Explanations are returned only as grading feedback.
"""

from __future__ import annotations

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
