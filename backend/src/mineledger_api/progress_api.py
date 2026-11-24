"""Authenticated student endpoints: graded and Recharge submissions, and progress retrieval.

Every handler takes the student from the verified token (`StudentUser`). None accepts a user ID, an
answer key, an XP amount or a completion flag, so a client has no way to assert any of them.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Header, HTTPException, Query, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import progress, schemas
from .auth import ROLE_ERRORS, StudentUser
from .db import get_session
from .models import MODE_GRADED, MODE_RECHARGE, Assignment

SessionDep = Annotated[Session, Depends(get_session)]
IdempotencyKey = Annotated[
    str,
    Header(
        alias="Idempotency-Key",
        min_length=16,
        max_length=128,
        pattern=r"^[A-Za-z0-9_-]+$",
        description=(
            "A fresh random value (a UUID is ideal) for each new answer. Send the same value again when "
            "retrying after a timeout: the original result is returned and nothing is recorded twice."
        ),
    ),
]

router = APIRouter(prefix="/api/v1", tags=["student progress"], responses=ROLE_ERRORS)
SUBMISSION_ERRORS = {
    404: {"description": "No question with this ID"},
    409: {"description": "Assignment not available, concept locked, or Recharge not unlocked yet"},
    422: {
        "description": (
            "Malformed request, not a multiple-choice question, or the idempotency key was already "
            "used for a different submission"
        )
    },
}


def _assignment(state: progress.AssignmentState) -> schemas.AssignmentProgress:
    return schemas.AssignmentProgress(
        assignment_id=state.assignment.id,
        status=state.status,
        completed_concepts=state.completed_concepts,
        total_concepts=len(state.concepts),
        xp_earned=state.xp_earned,
        current_concept_id=state.current_concept_id,
        concepts=[
            schemas.ConceptProgress(
                concept_id=item.concept.id,
                correct_answers=len(item.credited_question_ids),
                required_correct_answers=item.concept.required_correct_answers,
                completed=item.completed,
                locked=item.locked,
                xp_awarded=item.completion.xp_awarded if item.completion else 0,
                completed_at=item.completion.completed_at if item.completion else None,
                credited_question_ids=list(item.credited_question_ids),
                attempted_question_ids=list(item.attempted_question_ids),
            )
            for item in state.concepts
        ],
    )


def _submit(
    session: Session, user_id, question_id: str, request: schemas.SubmissionRequest, key: str, mode: str
) -> schemas.SubmissionResult:
    try:
        outcome = progress.submit_answer(session, user_id, question_id, request.selected_option_id, key, mode)
    except progress.QuestionNotFound as error:
        raise HTTPException(status.HTTP_404_NOT_FOUND, str(error)) from None
    except progress.NotPlayable as error:
        raise HTTPException(status.HTTP_409_CONFLICT, str(error)) from None
    except (progress.NotMultipleChoice, progress.IdempotencyKeyReused) as error:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, str(error)) from None
    attempt, question = outcome.attempt, outcome.question
    return schemas.SubmissionResult(
        attempt_id=attempt.id,
        mode=attempt.mode,
        question_id=question.id,
        concept_id=question.concept_id,
        assignment_id=question.concept.assignment_id,
        selected_option_id=attempt.selected_option_id,
        status=outcome.grading.status.value,
        correct=outcome.grading.correct,
        valid=outcome.grading.valid,
        feedback=outcome.grading.feedback,
        credit_earned=attempt.credit_earned,
        concept_completed=attempt.concept_completed,
        xp_awarded=attempt.xp_awarded,
        replayed=outcome.replayed,
        submitted_at=attempt.created_at,
        total_xp=outcome.total_xp,
        assignment=_assignment(outcome.assignment),
    )


@router.post("/questions/{question_id}/submissions", response_model=schemas.SubmissionResult, responses=SUBMISSION_ERRORS)
def submit_graded_answer(
    question_id: str, request: schemas.SubmissionRequest, key: IdempotencyKey, user: StudentUser, session: SessionDep
) -> schemas.SubmissionResult:
    """Official submission: graded on the server, recorded, and able to earn a mastery credit and concept XP."""
    return _submit(session, user.id, question_id, request, key, MODE_GRADED)


@router.post(
    "/questions/{question_id}/recharge-submissions", response_model=schemas.SubmissionResult, responses=SUBMISSION_ERRORS
)
def submit_recharge_answer(
    question_id: str, request: schemas.SubmissionRequest, key: IdempotencyKey, user: StudentUser, session: SessionDep
) -> schemas.SubmissionResult:
    """Recharge Practice: graded and explained, recorded as practice, and never worth credit or XP.

    Available once the student has completed the assignment the question belongs to.
    """
    return _submit(session, user.id, question_id, request, key, MODE_RECHARGE)


@router.get("/progress", response_model=schemas.ProgressSummary)
def get_progress(user: StudentUser, session: SessionDep) -> schemas.ProgressSummary:
    """The caller's total XP, completed concepts, and progress in every available assignment."""
    states = progress.assignment_states(session, user.id, progress.playable_assignments(session))
    return schemas.ProgressSummary(
        total_xp=progress.total_xp(session, user.id),
        completed_concept_ids=[item.concept.id for state in states for item in state.concepts if item.completed],
        assignments=[_assignment(state) for state in states],
    )


@router.get(
    "/progress/assignments/{assignment_id}",
    response_model=schemas.AssignmentProgress,
    responses={404: {"description": "No assignment with this ID"}},
)
def get_assignment_progress(assignment_id: str, user: StudentUser, session: SessionDep) -> schemas.AssignmentProgress:
    """The caller's mastery in one assignment, concept by concept (for example 0/2, 1/2, 2/2)."""
    assignment = session.scalars(
        select(Assignment).where(Assignment.id == assignment_id).options(*progress.ASSIGNMENT_CONTENT)
    ).one_or_none()
    if assignment is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, f"Assignment not found: {assignment_id}")
    return _assignment(progress.assignment_states(session, user.id, [assignment])[0])


@router.get("/progress/attempts", response_model=schemas.AttemptHistory)
def get_attempts(
    user: StudentUser,
    session: SessionDep,
    limit: Annotated[int, Query(ge=1, le=100)] = 50,
    offset: Annotated[int, Query(ge=0)] = 0,
    question_id: Annotated[str | None, Query(max_length=200)] = None,
) -> schemas.AttemptHistory:
    """The caller's own submissions, newest first, including Recharge practice."""
    records = progress.attempt_history(session, user.id, limit=limit, offset=offset, question_id=question_id)
    return schemas.AttemptHistory(
        attempts=[
            schemas.AttemptRecord(
                attempt_id=record.attempt.id,
                mode=record.attempt.mode,
                question_id=record.attempt.question_id,
                concept_id=record.concept_id,
                assignment_id=record.assignment_id,
                selected_option_id=record.attempt.selected_option_id,
                status=record.attempt.status,
                credit_earned=record.attempt.credit_earned,
                concept_completed=record.attempt.concept_completed,
                xp_awarded=record.attempt.xp_awarded,
                submitted_at=record.attempt.created_at,
            )
            for record in records
        ],
        limit=limit,
        offset=offset,
    )
