"""Server-authoritative submissions, mastery credits, concept completion and XP (Milestone 13B).

Rules, matching Java `ConceptProgressionService`:

- A credit is one distinct question answered correctly. A concept completes when its credits reach
  `required_correct_answers`, and pays `CONCEPT_COMPLETION_XP` once.
- Concepts in an assignment unlock in order. A completed concept can still be answered, for nothing.
- Recharge grades and explains, and can never earn anything. It opens once the assignment is completed.

Everything the client sends is a question ID, an option ID and an idempotency key. The user comes from
the verified token; the answer key, credits, completion and XP come from PostgreSQL.

Concurrency: each submission locks its user's row, so one student's submissions run one at a time.
The primary keys and unique constraints on the three progress tables enforce the same guarantees
independently of that lock. Assignment progress is derived from those tables, never stored.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass

from sqlalchemy import func, select
from sqlalchemy.orm import Session, selectinload

from .grading import INVALID_OPTION_FEEDBACK, GradingResult, GradingStatus, grade_multiple_choice
from .models import (
    MODE_GRADED,
    MODE_RECHARGE,
    Assignment,
    Concept,
    ConceptCompletion,
    Question,
    QuestionAttempt,
    QuestionCredit,
    User,
)

# XP for completing one concept. The amount paid is stored with each completion, so changing this
# later affects future completions only.
CONCEPT_COMPLETION_XP = 20


class SubmissionError(Exception):
    """A submission that was refused. Nothing was recorded and the idempotency key was not consumed."""


class QuestionNotFound(SubmissionError):
    pass


class NotMultipleChoice(SubmissionError):
    pass


class NotPlayable(SubmissionError):
    """The assignment is Coming Soon, the concept is locked, or Recharge is not unlocked yet."""


class IdempotencyKeyReused(SubmissionError):
    """The key was already used for a different question, answer or mode."""


@dataclass(frozen=True)
class ConceptState:
    concept: Concept
    credited_question_ids: tuple[str, ...]
    attempted_question_ids: tuple[str, ...]
    completion: ConceptCompletion | None
    locked: bool

    @property
    def completed(self) -> bool:
        return self.completion is not None


@dataclass(frozen=True)
class AssignmentState:
    assignment: Assignment
    concepts: tuple[ConceptState, ...]

    @property
    def completed_concepts(self) -> int:
        return sum(1 for state in self.concepts if state.completed)

    @property
    def completed(self) -> bool:
        return bool(self.concepts) and self.completed_concepts == len(self.concepts)

    @property
    def current_concept_id(self) -> str | None:
        return next((state.concept.id for state in self.concepts if not state.completed), None)

    @property
    def xp_earned(self) -> int:
        return sum(state.completion.xp_awarded for state in self.concepts if state.completion)

    @property
    def status(self) -> str:
        if self.completed:
            return "completed"
        started = any(state.completed or state.attempted_question_ids for state in self.concepts)
        return "in_progress" if started else "not_started"


@dataclass(frozen=True)
class SubmissionOutcome:
    attempt: QuestionAttempt
    question: Question
    grading: GradingResult
    replayed: bool
    assignment: AssignmentState
    total_xp: int


def total_xp(session: Session, user_id: uuid.UUID) -> int:
    return session.scalar(
        select(func.coalesce(func.sum(ConceptCompletion.xp_awarded), 0)).where(ConceptCompletion.user_id == user_id)
    )


def assignment_states(session: Session, user_id: uuid.UUID, assignments: list[Assignment]) -> list[AssignmentState]:
    """Derive each assignment's progress for one student. Assignments must have concepts and questions loaded."""
    completions = {
        row.concept_id: row
        for row in session.scalars(select(ConceptCompletion).where(ConceptCompletion.user_id == user_id))
    }
    credited = set(session.scalars(select(QuestionCredit.question_id).where(QuestionCredit.user_id == user_id)))
    # Like the Java client, an invalid option does not count as having attempted the question.
    attempted = set(
        session.scalars(
            select(QuestionAttempt.question_id)
            .where(
                QuestionAttempt.user_id == user_id,
                QuestionAttempt.mode == MODE_GRADED,
                QuestionAttempt.status != GradingStatus.INVALID_OPTION.value,
            )
            .distinct()
        )
    )
    states = []
    for assignment in assignments:
        concepts, unlocked = [], True
        for concept in assignment.concepts:
            completion = completions.get(concept.id)
            question_ids = [question.id for question in concept.questions]
            concepts.append(
                ConceptState(
                    concept=concept,
                    credited_question_ids=tuple(q for q in question_ids if q in credited),
                    attempted_question_ids=tuple(q for q in question_ids if q in attempted),
                    completion=completion,
                    locked=completion is None and not unlocked,
                )
            )
            unlocked = unlocked and completion is not None
        states.append(AssignmentState(assignment, tuple(concepts)))
    return states


ASSIGNMENT_CONTENT = (selectinload(Assignment.concepts).selectinload(Concept.questions),)


def playable_assignments(session: Session) -> list[Assignment]:
    return list(
        session.scalars(
            select(Assignment)
            .where(Assignment.availability == "available")
            .order_by(Assignment.unit_id, Assignment.sort_order)
            .options(*ASSIGNMENT_CONTENT)
        )
    )


def _load_question(session: Session, question_id: str) -> Question:
    question = session.scalars(
        select(Question)
        .where(Question.id == question_id)
        .options(
            selectinload(Question.options),
            selectinload(Question.concept).selectinload(Concept.assignment).options(*ASSIGNMENT_CONTENT),
        )
    ).one_or_none()
    if question is None:
        raise QuestionNotFound(f"Question not found: {question_id}")
    if question.question_type != "multiple_choice":
        raise NotMultipleChoice(
            f"Question {question_id} is a {question.question_type} question; only multiple_choice can be submitted"
        )
    return question


def _stored_grading(attempt: QuestionAttempt, question: Question) -> GradingResult:
    """The recorded verdict of an earlier attempt. A replay never grades the question a second time."""
    status = GradingStatus(attempt.status)
    return GradingResult(status, INVALID_OPTION_FEEDBACK if status is GradingStatus.INVALID_OPTION else question.explanation)


def submit_answer(
    session: Session,
    user_id: uuid.UUID,
    question_id: str,
    selected_option_id: str | None,
    idempotency_key: str,
    mode: str = MODE_GRADED,
) -> SubmissionOutcome:
    """Grade and record one submission in a single transaction. Commits on success, rolls back on refusal."""
    if mode not in (MODE_GRADED, MODE_RECHARGE):
        raise ValueError(f"Unknown submission mode: {mode!r}")
    try:
        outcome = _submit(session, user_id, question_id, selected_option_id, idempotency_key, mode)
        session.commit()
        return outcome
    except BaseException:
        session.rollback()
        raise


def _submit(
    session: Session, user_id: uuid.UUID, question_id: str, selected_option_id: str | None, idempotency_key: str, mode: str
) -> SubmissionOutcome:
    # Serialize this student's submissions. Everything read below is therefore current until commit.
    session.execute(select(User.id).where(User.id == user_id).with_for_update())

    earlier = session.scalars(
        select(QuestionAttempt).where(QuestionAttempt.user_id == user_id, QuestionAttempt.idempotency_key == idempotency_key)
    ).one_or_none()
    if earlier is not None:
        if (earlier.question_id, earlier.selected_option_id, earlier.mode) != (question_id, selected_option_id, mode):
            raise IdempotencyKeyReused("This idempotency key was already used for a different submission")
        question = _load_question(session, earlier.question_id)
        return _outcome(session, user_id, earlier, question, _stored_grading(earlier, question), replayed=True)

    question = _load_question(session, question_id)
    concept = question.concept
    assignment = concept.assignment
    if assignment.availability != "available":
        raise NotPlayable(f"Assignment {assignment.id} is not available yet")
    state = assignment_states(session, user_id, [assignment])[0]
    concept_state = next(item for item in state.concepts if item.concept.id == concept.id)
    if mode == MODE_RECHARGE and not state.completed:
        raise NotPlayable(f"Recharge Practice unlocks when assignment {assignment.id} is completed")
    if mode == MODE_GRADED and concept_state.locked:
        raise NotPlayable(f"Concept {concept.id} is locked until the earlier concepts are completed")

    grading = grade_multiple_choice(
        (option.option_id for option in question.options), question.correct_option_id, question.explanation, selected_option_id
    )
    credit_earned = (
        mode == MODE_GRADED
        and grading.correct
        and not concept_state.completed
        and question.id not in concept_state.credited_question_ids
    )
    concept_completed = credit_earned and len(concept_state.credited_question_ids) + 1 >= concept.required_correct_answers
    attempt = QuestionAttempt(
        id=uuid.uuid4(),
        user_id=user_id,
        question_id=question.id,
        idempotency_key=idempotency_key,
        mode=mode,
        selected_option_id=selected_option_id,
        status=grading.status.value,
        credit_earned=credit_earned,
        concept_completed=concept_completed,
        xp_awarded=CONCEPT_COMPLETION_XP if concept_completed else 0,
    )
    session.add(attempt)
    session.flush()
    if credit_earned:
        session.add(QuestionCredit(user_id=user_id, question_id=question.id, attempt_id=attempt.id))
    if concept_completed:
        session.add(
            ConceptCompletion(user_id=user_id, concept_id=concept.id, attempt_id=attempt.id, xp_awarded=attempt.xp_awarded)
        )
    session.flush()
    return _outcome(session, user_id, attempt, question, grading, replayed=False)


def _outcome(
    session: Session, user_id: uuid.UUID, attempt: QuestionAttempt, question: Question, grading: GradingResult, *, replayed: bool
) -> SubmissionOutcome:
    session.refresh(attempt)
    return SubmissionOutcome(
        attempt=attempt,
        question=question,
        grading=grading,
        replayed=replayed,
        assignment=assignment_states(session, user_id, [question.concept.assignment])[0],
        total_xp=total_xp(session, user_id),
    )


@dataclass(frozen=True)
class AttemptRecord:
    attempt: QuestionAttempt
    concept_id: str
    assignment_id: str


def attempt_history(
    session: Session, user_id: uuid.UUID, *, limit: int, offset: int, question_id: str | None = None
) -> list[AttemptRecord]:
    query = (
        select(QuestionAttempt, Question.concept_id, Concept.assignment_id)
        .join(Question, Question.id == QuestionAttempt.question_id)
        .join(Concept, Concept.id == Question.concept_id)
        .where(QuestionAttempt.user_id == user_id)
        .order_by(QuestionAttempt.created_at.desc(), QuestionAttempt.id)
        .limit(limit)
        .offset(offset)
    )
    if question_id is not None:
        query = query.where(QuestionAttempt.question_id == question_id)
    return [AttemptRecord(*row) for row in session.execute(query)]
