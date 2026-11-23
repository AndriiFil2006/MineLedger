"""Read-only curriculum endpoints and stateless multiple-choice grading (`/api/v1`)."""

from __future__ import annotations

from typing import Annotated, TypeVar

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy import func, select, text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session, selectinload

from . import schemas
from .db import get_session
from .grading import grade_multiple_choice
from .models import Assignment, Base, Concept, ConceptLesson, Course, CurriculumBundle, Question, Unit

SessionDep = Annotated[Session, Depends(get_session)]
M = TypeVar("M", bound=Base)

health_router = APIRouter(tags=["health"])
router = APIRouter(prefix="/api/v1", tags=["curriculum"])
NOT_FOUND = {404: {"description": "No record with this ID"}}


def _require(session: Session, model: type[M], entity_id: str, label: str, *options) -> M:
    row = session.scalars(select(model).where(model.id == entity_id).options(*options)).one_or_none()
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, f"{label} not found: {entity_id}")
    return row


@health_router.get("/health", response_model=schemas.Health, responses={503: {"model": schemas.Health}})
def health(session: SessionDep, response: Response) -> schemas.Health:
    try:
        session.execute(text("SELECT 1"))
    except SQLAlchemyError:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return schemas.Health(status="degraded", database="unavailable")
    return schemas.Health(status="ok", database="ok")


# Bump when a response shape changes incompatibly; clients refuse versions they do not know.
API_VERSION = 1


@router.get("/meta", response_model=schemas.Meta)
def meta(session: SessionDep) -> schemas.Meta:
    """Compatibility probe: `course_count` 0 means no curriculum has been imported yet."""
    versions = session.scalars(select(CurriculumBundle.schema_version).distinct().order_by(CurriculumBundle.schema_version)).all()
    return schemas.Meta(
        api_version=API_VERSION,
        curriculum_schema_versions=list(versions),
        course_count=session.scalar(select(func.count()).select_from(Course)) or 0,
    )


@router.get("/courses", response_model=list[schemas.CourseSummary])
def list_courses(session: SessionDep) -> list[schemas.CourseSummary]:
    courses = session.scalars(select(Course).order_by(Course.sort_order, Course.id).options(selectinload(Course.units))).all()
    return [
        schemas.CourseSummary(id=c.id, title=c.title, description=c.description, unit_ids=[u.id for u in c.units])
        for c in courses
    ]


@router.get("/courses/{course_id}", response_model=schemas.CourseDetail, responses=NOT_FOUND)
def get_course(course_id: str, session: SessionDep) -> schemas.CourseDetail:
    course = _require(session, Course, course_id, "Course", selectinload(Course.units).selectinload(Unit.assignments))
    return schemas.CourseDetail(
        id=course.id,
        title=course.title,
        description=course.description,
        units=[
            schemas.UnitSummary(
                id=u.id, number=u.unit_number, title=u.title, description=u.description,
                assignment_ids=[a.id for a in u.assignments],
            )
            for u in course.units
        ],
    )


@router.get("/units/{unit_id}", response_model=schemas.UnitDetail, responses=NOT_FOUND)
def get_unit(unit_id: str, session: SessionDep) -> schemas.UnitDetail:
    unit = _require(session, Unit, unit_id, "Unit", selectinload(Unit.assignments).selectinload(Assignment.concepts))
    return schemas.UnitDetail(
        id=unit.id,
        course_id=unit.course_id,
        number=unit.unit_number,
        title=unit.title,
        description=unit.description,
        assignments=[
            schemas.AssignmentSummary(
                id=a.id, title=a.title, description=a.description, availability=a.availability,
                concept_ids=[c.id for c in a.concepts],
            )
            for a in unit.assignments
        ],
    )


@router.get("/assignments/{assignment_id}", response_model=schemas.AssignmentDetail, responses=NOT_FOUND)
def get_assignment(assignment_id: str, session: SessionDep) -> schemas.AssignmentDetail:
    assignment = _require(
        session, Assignment, assignment_id, "Assignment",
        selectinload(Assignment.concepts).selectinload(Concept.questions),
        selectinload(Assignment.concepts).selectinload(Concept.lesson),
    )
    return schemas.AssignmentDetail(
        id=assignment.id,
        unit_id=assignment.unit_id,
        title=assignment.title,
        description=assignment.description,
        availability=assignment.availability,
        concepts=[
            schemas.ConceptSummary(
                id=c.id, title=c.title, description=c.description, required_correct_answers=c.required_correct_answers,
                question_count=len(c.questions), lesson_id=c.lesson.id if c.lesson else None,
            )
            for c in assignment.concepts
        ],
    )


@router.get("/concepts/{concept_id}", response_model=schemas.ConceptDetail, responses=NOT_FOUND)
def get_concept(concept_id: str, session: SessionDep) -> schemas.ConceptDetail:
    concept = _require(
        session, Concept, concept_id, "Concept",
        selectinload(Concept.questions), selectinload(Concept.lesson).selectinload(ConceptLesson.worked_examples),
    )
    lesson = concept.lesson
    return schemas.ConceptDetail(
        id=concept.id,
        assignment_id=concept.assignment_id,
        title=concept.title,
        description=concept.description,
        required_correct_answers=concept.required_correct_answers,
        question_ids=[q.id for q in concept.questions],
        lesson=None if lesson is None else schemas.Lesson(
            id=lesson.id,
            title=lesson.title,
            learning_objective=lesson.learning_objective,
            explanation=lesson.explanation,
            accounting_rules=list(lesson.accounting_rules),
            worked_examples=[
                schemas.WorkedExample(
                    title=e.title, scenario=e.scenario, calculation=e.calculation, debit_entry=e.debit_entry,
                    credit_entry=e.credit_entry, explanation=e.explanation,
                )
                for e in lesson.worked_examples
            ],
            common_mistakes=list(lesson.common_mistakes),
            takeaway=lesson.takeaway,
        ),
    )


@router.get("/assignments/{assignment_id}/questions", response_model=schemas.AssignmentQuestions, responses=NOT_FOUND)
def get_assignment_questions(assignment_id: str, session: SessionDep) -> schemas.AssignmentQuestions:
    """All of an assignment's multiple-choice questions in concept order. Coming Soon assignments return []."""
    assignment = _require(
        session, Assignment, assignment_id, "Assignment",
        selectinload(Assignment.concepts).selectinload(Concept.questions).selectinload(Question.options),
    )
    return schemas.AssignmentQuestions(
        assignment_id=assignment.id,
        questions=[
            schemas.PublicQuestion(
                id=q.id, concept_id=c.id, type="multiple_choice", prompt=q.prompt, difficulty=q.difficulty,
                answer_options=[schemas.PublicAnswerOption(id=o.option_id, text=o.text) for o in q.options],
            )
            for c in assignment.concepts
            for q in c.questions
        ],
    )


@router.post(
    "/questions/{question_id}/grade",
    response_model=schemas.GradeResponse,
    responses={**NOT_FOUND, 422: {"description": "Malformed request, or not a multiple-choice question"}},
)
def grade_question(question_id: str, request: schemas.GradeRequest, session: SessionDep) -> schemas.GradeResponse:
    """Stateless: never records attempts, credits or XP. Invalid option IDs return status `invalid_option`."""
    question = _require(session, Question, question_id, "Question", selectinload(Question.options))
    if question.question_type != "multiple_choice":
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            f"Question {question_id} is a {question.question_type} question; this endpoint grades multiple_choice only",
        )
    result = grade_multiple_choice(
        (o.option_id for o in question.options), question.correct_option_id, question.explanation, request.selected_option_id
    )
    return schemas.GradeResponse(
        question_id=question.id,
        selected_option_id=request.selected_option_id,
        status=result.status.value,
        correct=result.correct,
        valid=result.valid,
        feedback=result.feedback,
    )
