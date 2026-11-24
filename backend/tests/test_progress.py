"""Server-authoritative submissions, mastery, XP, Recharge and progress retrieval (Milestone 13B).

Authentication uses the loopback identity provider from `test_auth`. The bundled curriculum supplies the
content: assignment `inventory_fundamentals` has two concepts of three questions, each needing two.
"""

from __future__ import annotations

import threading
import uuid
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, sessionmaker

from mineledger_api import progress
from mineledger_api.auth import get_token_verifier
from mineledger_api.db import get_session, make_engine
from mineledger_api.main import create_app
from mineledger_api.models import MODE_GRADED, MODE_RECHARGE, QuestionAttempt
from mineledger_api.users import get_or_create_user, set_active, set_role
from tests.test_api import ANSWER_KEY_FIELDS, keys_in
from tests.test_auth import api, bearer, idp  # noqa: F401 - pytest fixtures
from tests.test_grading_parity import expected_feedback

ASSIGNMENT = "inventory_fundamentals"
FIRST, SECOND = "inventory.purchases_on_credit", "payables.cash_payments"
CORRECT = {
    "inventory.shovels": "shovels.inventory_payable",
    "inventory.books_discount": "books.inventory_payable",
    "inventory.lanterns_delivery": "lanterns.inventory_payable",
    "payables.shovels_settlement": "settlement.payable_cash",
    "payables.books_partial": "partial.payable_cash",
    "payables.two_invoices": "invoices.payable_cash",
}
WRONG = {"inventory.shovels": "shovels.cash", "inventory.books_discount": "books.gross", "payables.shovels_settlement": "settlement.inventory"}
SHOVELS, BOOKS, LANTERNS = "inventory.shovels", "inventory.books_discount", "inventory.lanterns_delivery"
SETTLEMENT, PARTIAL = "payables.shovels_settlement", "payables.books_partial"
PROGRESS = "/api/v1/progress"
ASSIGNMENT_PROGRESS = f"/api/v1/progress/assignments/{ASSIGNMENT}"
ATTEMPTS = "/api/v1/progress/attempts"
XP = progress.CONCEPT_COMPLETION_XP


def new_key() -> str:
    return str(uuid.uuid4())


def post(api, student: str, question: str, option, *, key: str | None = None, recharge: bool = False, idp, **extra):
    path = f"/api/v1/questions/{question}/{'recharge-submissions' if recharge else 'submissions'}"
    headers = {**bearer(idp.token(student)), "Idempotency-Key": key or new_key(), **extra.pop("headers", {})}
    return api.post(path, json={"selected_option_id": option, **extra}, headers=headers)


@pytest.fixture
def submit(api, idp):
    """submit(student, question, option) -> response. A fresh idempotency key unless one is given."""

    def call(student: str, question: str, option, **kwargs):
        return post(api, student, question, option, idp=idp, **kwargs)

    return call


@pytest.fixture
def answer(submit):
    """answer(student, *questions): submit the correct option for each and return the last response body."""

    def call(student: str, *questions: str, **kwargs) -> dict:
        body = None
        for question in questions:
            response = submit(student, question, CORRECT[question], **kwargs)
            assert response.status_code == 200, response.text
            body = response.json()
        return body

    return call


@pytest.fixture
def get(api, idp):
    def call(student: str, path: str, **params):
        return api.get(path, headers=bearer(idp.token(student)), params=params)

    return call


def rows(session: Session, table: str) -> int:
    session.rollback()
    return session.scalar(text(f"SELECT count(*) FROM {table}"))


def all_rows(session: Session) -> tuple[int, int, int]:
    return rows(session, "question_attempts"), rows(session, "question_credits"), rows(session, "concept_completions")


def mastery(body: dict) -> list[str]:
    """An assignment progress body as ['1/2', '0/2']."""
    return [f"{c['correct_answers']}/{c['required_correct_answers']}" for c in body["concepts"]]


# --- Graded submissions -----------------------------------------------------------------------------


def test_first_correct_submission_earns_one_credit_and_no_xp(submit, session, bundled_json):
    response = submit("alex", SHOVELS, CORRECT[SHOVELS])
    assert response.status_code == 200
    body = response.json()
    explanation = next(q["explanation"] for q in bundled_json["questions"] if q["id"] == SHOVELS)
    assert (body["status"], body["correct"], body["valid"], body["feedback"]) == ("correct", True, True, explanation)
    assert (body["question_id"], body["concept_id"], body["assignment_id"]) == (SHOVELS, FIRST, ASSIGNMENT)
    assert (body["mode"], body["selected_option_id"], body["replayed"]) == ("graded", CORRECT[SHOVELS], False)
    assert (body["credit_earned"], body["concept_completed"], body["xp_awarded"], body["total_xp"]) == (True, False, 0, 0)

    assignment = body["assignment"]
    assert mastery(assignment) == ["1/2", "0/2"]
    assert (assignment["status"], assignment["completed_concepts"], assignment["total_concepts"]) == ("in_progress", 0, 2)
    assert (assignment["current_concept_id"], assignment["xp_earned"]) == (FIRST, 0)
    first, second = assignment["concepts"]
    assert first["credited_question_ids"] == first["attempted_question_ids"] == [SHOVELS]
    assert (first["completed"], first["locked"], first["completed_at"]) == (False, False, None)
    assert (second["completed"], second["locked"]) == (False, True)
    assert all_rows(session) == (1, 1, 0)


def test_incorrect_submission_explains_and_earns_nothing(submit, session, bundled_json):
    body = submit("alex", SHOVELS, WRONG[SHOVELS]).json()
    explanation = next(q["explanation"] for q in bundled_json["questions"] if q["id"] == SHOVELS)
    assert (body["status"], body["correct"], body["valid"], body["feedback"]) == ("incorrect", False, True, explanation)
    assert (body["credit_earned"], body["concept_completed"], body["xp_awarded"], body["total_xp"]) == (False, False, 0, 0)
    assert mastery(body["assignment"]) == ["0/2", "0/2"]
    assert body["assignment"]["status"] == "in_progress"
    assert body["assignment"]["concepts"][0]["attempted_question_ids"] == [SHOVELS]
    assert body["assignment"]["concepts"][0]["credited_question_ids"] == []
    assert all_rows(session) == (1, 0, 0)


def test_a_missed_question_can_be_answered_correctly_later(submit, session):
    submit("alex", SHOVELS, WRONG[SHOVELS])
    body = submit("alex", SHOVELS, CORRECT[SHOVELS]).json()
    assert body["credit_earned"] is True and mastery(body["assignment"]) == ["1/2", "0/2"]
    assert all_rows(session) == (2, 1, 0)


def test_a_mistake_does_not_remove_an_earned_credit(submit, answer):
    answer("alex", SHOVELS)
    body = submit("alex", BOOKS, WRONG[BOOKS]).json()
    assert mastery(body["assignment"]) == ["1/2", "0/2"]
    assert mastery(submit("alex", SHOVELS, WRONG[SHOVELS]).json()["assignment"]) == ["1/2", "0/2"]


def test_two_distinct_correct_answers_complete_the_concept_and_award_xp_once(submit, answer, session):
    answer("alex", SHOVELS)
    body = submit("alex", BOOKS, CORRECT[BOOKS]).json()
    assert (body["credit_earned"], body["concept_completed"], body["xp_awarded"], body["total_xp"]) == (True, True, XP, XP)
    assignment = body["assignment"]
    assert mastery(assignment) == ["2/2", "0/2"]
    assert (assignment["completed_concepts"], assignment["current_concept_id"], assignment["xp_earned"]) == (1, SECOND, XP)
    first, second = assignment["concepts"]
    assert (first["completed"], first["xp_awarded"], first["locked"]) == (True, XP, False)
    assert first["completed_at"] is not None
    assert (second["completed"], second["locked"]) == (False, False)
    assert all_rows(session) == (2, 2, 1)
    assert XP == 20  # the amount the Java client awards per concept


def test_answering_the_same_question_correctly_again_earns_nothing(submit, answer, session):
    answer("alex", SHOVELS)
    for _ in range(3):
        body = submit("alex", SHOVELS, CORRECT[SHOVELS]).json()
        assert (body["status"], body["credit_earned"], body["concept_completed"], body["xp_awarded"]) == ("correct", False, False, 0)
        assert mastery(body["assignment"]) == ["1/2", "0/2"]
    assert all_rows(session) == (4, 1, 0)


def test_a_completed_concept_pays_nothing_more(submit, answer, session):
    answer("alex", SHOVELS, BOOKS)
    for question in (LANTERNS, SHOVELS, BOOKS):
        body = submit("alex", question, CORRECT[question]).json()
        assert (body["status"], body["credit_earned"], body["concept_completed"], body["xp_awarded"]) == ("correct", False, False, 0)
        assert body["total_xp"] == XP and mastery(body["assignment"]) == ["2/2", "0/2"]
    assert all_rows(session) == (5, 2, 1)


def test_completing_every_concept_completes_the_assignment(answer, session):
    body = answer("alex", SHOVELS, LANTERNS, SETTLEMENT, PARTIAL)
    assert body["total_xp"] == 2 * XP
    assignment = body["assignment"]
    assert (assignment["status"], assignment["completed_concepts"], assignment["current_concept_id"]) == ("completed", 2, None)
    assert mastery(assignment) == ["2/2", "2/2"] and assignment["xp_earned"] == 2 * XP
    assert all_rows(session) == (4, 4, 2)


def test_a_first_ever_request_can_be_a_submission(submit, session):
    """No earlier sign-in: the user row is created and locked in the same request."""
    assert rows(session, "users") == 0
    assert submit("newcomer", SHOVELS, CORRECT[SHOVELS]).json()["credit_earned"] is True
    assert rows(session, "users") == 1


# --- Refused submissions ----------------------------------------------------------------------------


def test_a_locked_concept_is_refused_until_the_earlier_one_is_completed(submit, answer, session):
    key = new_key()
    for option in (CORRECT[SETTLEMENT], WRONG[SETTLEMENT]):
        response = submit("alex", SETTLEMENT, option)
        assert response.status_code == 409
        assert response.json() == {"detail": f"Concept {SECOND} is locked until the earlier concepts are completed"}
    assert submit("alex", SETTLEMENT, CORRECT[SETTLEMENT], key=key).status_code == 409
    answer("alex", SHOVELS)
    assert submit("alex", SETTLEMENT, CORRECT[SETTLEMENT]).status_code == 409  # 1/2 is not enough
    assert all_rows(session) == (1, 1, 0)

    answer("alex", BOOKS)
    # The refused request consumed nothing, so its key is still good for the same submission.
    body = submit("alex", SETTLEMENT, CORRECT[SETTLEMENT], key=key).json()
    assert (body["credit_earned"], body["replayed"]) == (True, False)


def test_a_refused_answer_reveals_nothing_about_correctness(submit):
    right = submit("alex", SETTLEMENT, CORRECT[SETTLEMENT])
    wrong = submit("alex", SETTLEMENT, WRONG[SETTLEMENT])
    assert right.status_code == wrong.status_code == 409 and right.json() == wrong.json()


def test_an_assignment_that_is_not_available_is_refused(submit, get, session):
    session.execute(text("UPDATE assignments SET availability = 'coming_soon' WHERE id = :id"), {"id": ASSIGNMENT})
    session.commit()
    response = submit("alex", SHOVELS, CORRECT[SHOVELS])
    assert response.status_code == 409
    assert response.json() == {"detail": f"Assignment {ASSIGNMENT} is not available yet"}
    assert submit("alex", SHOVELS, CORRECT[SHOVELS], recharge=True).status_code == 409
    assert all_rows(session) == (0, 0, 0)
    assert get("alex", PROGRESS).json() == {"total_xp": 0, "completed_concept_ids": [], "assignments": []}


@pytest.mark.parametrize("recharge", [False, True])
def test_unknown_question_ids_return_404(submit, session, recharge):
    for question in ("inventory.nope", "INVENTORY.SHOVELS", " inventory.shovels", FIRST, ASSIGNMENT):
        response = submit("alex", question, CORRECT[SHOVELS], recharge=recharge)
        assert response.status_code == 404, question
        assert response.json() == {"detail": f"Question not found: {question}"}
    assert all_rows(session) == (0, 0, 0)


@pytest.mark.parametrize("recharge", [False, True])
def test_journal_entry_questions_cannot_be_submitted(submit, session, recharge):
    response = submit("alex", "journal.inventory_on_credit", "INVENTORY", recharge=recharge)
    assert response.status_code == 422
    assert "only multiple_choice can be submitted" in response.json()["detail"]
    assert all_rows(session) == (0, 0, 0)


@pytest.mark.parametrize(
    "option",
    [None, "", "nope", "books.inventory_payable", "SHOVELS.INVENTORY_PAYABLE", " shovels.inventory_payable", "1",
     "Debit Inventory 60; Credit Accounts Payable 60"],
)
def test_invalid_options_are_recorded_and_earn_nothing(submit, get, session, option):
    body = submit("alex", SHOVELS, option).json()
    assert (body["status"], body["correct"], body["valid"]) == ("invalid_option", False, False)
    assert body["feedback"] == "Select one of this question's four answers before submitting."
    assert (body["credit_earned"], body["xp_awarded"], body["selected_option_id"]) == (False, 0, option)
    # Like the Java client, an invalid selection does not count as an attempt at the question.
    assert body["assignment"]["status"] == "not_started"
    assert body["assignment"]["concepts"][0]["attempted_question_ids"] == []
    assert all_rows(session) == (1, 0, 0)
    assert get("alex", ATTEMPTS).json()["attempts"][0]["status"] == "invalid_option"


def test_a_missing_option_field_is_an_invalid_option(api, idp, session):
    response = api.post(
        f"/api/v1/questions/{SHOVELS}/submissions", json={}, headers={**bearer(idp.token("alex")), "Idempotency-Key": new_key()}
    )
    assert response.json()["status"] == "invalid_option"


@pytest.mark.parametrize(
    "body",
    [
        {"selected_option_id": "x" * 201},
        {"selected_option_id": 7},
        {"selected_option_id": ["shovels.inventory_payable"]},
        [],
        "shovels.inventory_payable",
    ],
)
def test_malformed_bodies_are_rejected(api, idp, session, body):
    headers = {**bearer(idp.token("alex")), "Idempotency-Key": new_key()}
    assert api.post(f"/api/v1/questions/{SHOVELS}/submissions", json=body, headers=headers).status_code == 422
    assert all_rows(session) == (0, 0, 0)


# --- Nothing the client asserts is trusted -------------------------------------------------------------

CLIENT_CLAIMS = [
    {"correct": True},
    {"status": "correct"},
    {"xp_awarded": 9999},
    {"total_xp": 9999},
    {"credit_earned": True},
    {"concept_completed": True},
    {"completed": True},
    {"correct_option_id": "shovels.cash"},
    {"user_id": "00000000-0000-4000-8000-000000000000"},
    {"mode": "graded"},
]


@pytest.mark.parametrize("claim", CLIENT_CLAIMS, ids=lambda claim: next(iter(claim)))
@pytest.mark.parametrize("recharge", [False, True])
def test_client_supplied_results_are_rejected_outright(submit, session, claim, recharge):
    response = submit("alex", SHOVELS, WRONG[SHOVELS], recharge=recharge, **claim)
    assert response.status_code == 422
    assert all_rows(session) == (0, 0, 0)


def test_identity_and_reward_headers_and_parameters_are_ignored(api, idp, session, answer, get):
    victim = uuid.UUID(api.get("/api/v1/users/me", headers=bearer(idp.token("victim"))).json()["id"])
    headers = {
        **bearer(idp.token("alex")), "Idempotency-Key": new_key(),
        "X-User-Id": str(victim), "X-XP": "9999", "X-Correct": "true",
    }
    response = api.post(
        f"/api/v1/questions/{SHOVELS}/submissions?user_id={victim}&xp=9999&correct=true&mode=recharge",
        json={"selected_option_id": WRONG[SHOVELS]}, headers=headers,
    )
    body = response.json()
    assert (body["status"], body["mode"], body["xp_awarded"], body["total_xp"]) == ("incorrect", "graded", 0, 0)
    session.rollback()
    owner = session.scalars(select(QuestionAttempt.user_id)).one()
    assert owner != victim
    assert get("victim", ATTEMPTS).json()["attempts"] == []


# --- Idempotency ------------------------------------------------------------------------------------


def test_a_repeated_idempotency_key_returns_the_original_result(submit, session):
    key = new_key()
    first = submit("alex", SHOVELS, CORRECT[SHOVELS], key=key).json()
    for _ in range(3):
        again = submit("alex", SHOVELS, CORRECT[SHOVELS], key=key).json()
        assert again == {**first, "replayed": True}
    assert first["replayed"] is False
    assert all_rows(session) == (1, 1, 0)


def test_retrying_the_completing_request_does_not_award_xp_twice(submit, answer, get, session):
    answer("alex", SHOVELS)
    key = new_key()
    first = submit("alex", BOOKS, CORRECT[BOOKS], key=key).json()
    assert (first["concept_completed"], first["xp_awarded"], first["total_xp"]) == (True, XP, XP)
    for _ in range(5):
        again = submit("alex", BOOKS, CORRECT[BOOKS], key=key).json()
        assert (again["replayed"], again["attempt_id"], again["xp_awarded"], again["total_xp"]) == (True, first["attempt_id"], XP, XP)
    assert get("alex", PROGRESS).json()["total_xp"] == XP
    assert all_rows(session) == (2, 2, 1)


def test_a_replay_reports_the_original_verdict_and_the_current_progress(submit, answer):
    key = new_key()
    submit("alex", SHOVELS, WRONG[SHOVELS], key=key)
    answer("alex", SHOVELS, BOOKS)
    replay = submit("alex", SHOVELS, WRONG[SHOVELS], key=key).json()
    assert (replay["status"], replay["credit_earned"], replay["xp_awarded"], replay["replayed"]) == ("incorrect", False, 0, True)
    assert mastery(replay["assignment"]) == ["2/2", "0/2"] and replay["total_xp"] == XP


def test_a_replay_keeps_its_verdict_even_if_the_answer_key_changes(submit, session):
    key = new_key()
    assert submit("alex", SHOVELS, CORRECT[SHOVELS], key=key).json()["status"] == "correct"
    session.execute(text("UPDATE questions SET correct_option_id = 'shovels.cash' WHERE id = :id"), {"id": SHOVELS})
    session.commit()
    replay = submit("alex", SHOVELS, CORRECT[SHOVELS], key=key).json()
    assert (replay["status"], replay["correct"], replay["credit_earned"]) == ("correct", True, True)


def test_the_same_key_with_a_different_payload_is_rejected(submit, answer, session):
    key = new_key()
    original = submit("alex", SHOVELS, WRONG[SHOVELS], key=key).json()
    conflict = {"detail": "This idempotency key was already used for a different submission"}
    for question, option, recharge in (
        (SHOVELS, CORRECT[SHOVELS], False),  # a different answer: the classic "retry with the right one"
        (SHOVELS, None, False),
        (BOOKS, CORRECT[BOOKS], False),
        (SHOVELS, WRONG[SHOVELS], True),  # the same answer through the other endpoint
        ("inventory.nope", WRONG[SHOVELS], False),
    ):
        response = submit("alex", question, option, key=key, recharge=recharge)
        assert response.status_code == 422, (question, option, recharge)
        assert response.json() == conflict
    assert all_rows(session) == (1, 0, 0)
    assert submit("alex", SHOVELS, WRONG[SHOVELS], key=key).json() == {**original, "replayed": True}


def test_idempotency_keys_are_scoped_to_the_user(submit, session):
    key = new_key()
    alex = submit("alex", SHOVELS, CORRECT[SHOVELS], key=key).json()
    sam = submit("sam", SHOVELS, WRONG[SHOVELS], key=key).json()
    assert (alex["status"], sam["status"], sam["replayed"]) == ("correct", "incorrect", False)
    assert alex["attempt_id"] != sam["attempt_id"]
    assert all_rows(session) == (2, 1, 0)


@pytest.mark.parametrize("recharge", [False, True])
def test_an_idempotency_key_is_required_and_validated(api, idp, session, recharge):
    path = f"/api/v1/questions/{SHOVELS}/{'recharge-submissions' if recharge else 'submissions'}"
    body = {"selected_option_id": CORRECT[SHOVELS]}
    assert api.post(path, json=body, headers=bearer(idp.token("alex"))).status_code == 422
    for key in ("", "short", "x" * 129, "has spaces in the key!", "semi;colon;separated;key", "ключ-ключ-ключ-ключ"):
        headers = {**bearer(idp.token("alex")), "Idempotency-Key": key.encode()}
        assert api.post(path, json=body, headers=headers).status_code == 422, key
    assert all_rows(session) == (0, 0, 0)


# --- Concurrency ------------------------------------------------------------------------------------


def run_concurrently(engine, calls: list[tuple]) -> list:
    """Run `submit_answer(*call)` for every call at the same moment, each on its own connection."""
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    barrier = threading.Barrier(len(calls))

    def work(call):
        with factory() as own_session:
            barrier.wait(timeout=10)
            try:
                outcome = progress.submit_answer(own_session, *call)
                return outcome.attempt.credit_earned, outcome.attempt.xp_awarded, outcome.replayed, outcome.attempt.id
            except Exception as error:  # noqa: BLE001 - reported to the assertion below
                return error

    with ThreadPoolExecutor(max_workers=len(calls)) as pool:
        results = list(pool.map(work, calls))
    assert not [r for r in results if isinstance(r, Exception)], results
    return results


@pytest.fixture
def student_id(session, clean_db, imported) -> uuid.UUID:
    return get_or_create_user(session, "https://idp.test", "racer").id


def test_concurrent_correct_answers_to_one_question_earn_one_credit(clean_db, session, student_id):
    calls = [(student_id, SHOVELS, CORRECT[SHOVELS], new_key()) for _ in range(12)]
    results = run_concurrently(clean_db, calls)
    assert sum(credit for credit, *_ in results) == 1
    assert sum(xp for _, xp, *_ in results) == 0
    assert all_rows(session) == (12, 1, 0)


def test_concurrent_completing_answers_award_xp_exactly_once(clean_db, session, student_id):
    calls = [(student_id, question, CORRECT[question], new_key()) for question in (SHOVELS, BOOKS, LANTERNS) for _ in range(6)]
    results = run_concurrently(clean_db, calls)
    assert sum(credit for credit, *_ in results) == 2  # the third question arrives after completion
    assert sum(xp for _, xp, *_ in results) == XP
    assert all_rows(session) == (18, 2, 1)
    assert progress.total_xp(session, student_id) == XP
    assert session.scalar(text("SELECT sum(xp_awarded) FROM question_attempts")) == XP


def test_concurrent_requests_with_one_idempotency_key_record_one_attempt(clean_db, session, student_id):
    progress.submit_answer(session, student_id, SHOVELS, CORRECT[SHOVELS], new_key())
    key = new_key()
    results = run_concurrently(clean_db, [(student_id, BOOKS, CORRECT[BOOKS], key) for _ in range(12)])
    assert sum(1 for *_, replayed, _ in results if not replayed) == 1
    assert len({attempt_id for *_, attempt_id in results}) == 1
    assert {(credit, xp) for credit, xp, *_ in results} == {(True, XP)}  # every caller sees the original result
    assert all_rows(session) == (2, 2, 1)
    assert progress.total_xp(session, student_id) == XP


def test_concurrent_students_do_not_block_or_affect_each_other(clean_db, session, imported):
    students = [get_or_create_user(session, "https://idp.test", f"student-{n}").id for n in range(6)]
    session.commit()
    calls = [(student, question, CORRECT[question], new_key()) for student in students for question in (SHOVELS, BOOKS)]
    run_concurrently(clean_db, calls)
    assert all_rows(session) == (12, 12, 6)
    assert [progress.total_xp(session, student) for student in students] == [XP] * 6


def test_concurrent_http_requests_award_xp_exactly_once(api, idp, session, answer):
    answer("alex", SHOVELS)
    token = idp.token("alex")
    barrier = threading.Barrier(8)

    def work(_):
        barrier.wait(timeout=10)
        return TestClient(api.app).post(
            f"/api/v1/questions/{BOOKS}/submissions", json={"selected_option_id": CORRECT[BOOKS]},
            headers={**bearer(token), "Idempotency-Key": new_key()},
        )

    with ThreadPoolExecutor(max_workers=8) as pool:
        responses = list(pool.map(work, range(8)))
    assert [r.status_code for r in responses] == [200] * 8
    assert sorted(r.json()["xp_awarded"] for r in responses) == [0] * 7 + [XP]
    assert sum(r.json()["credit_earned"] for r in responses) == 1
    assert all_rows(session) == (9, 2, 1)


def test_the_user_lock_is_what_serializes_submissions(clean_db, session, student_id):
    """While one transaction holds the student's row, a submission for that student waits; another student's does not."""
    other = get_or_create_user(session, "https://idp.test", "bystander").id
    session.commit()
    done = threading.Event()

    def blocked():
        with Session(clean_db) as own_session:
            progress.submit_answer(own_session, student_id, SHOVELS, CORRECT[SHOVELS], new_key())
        done.set()

    with clean_db.connect() as holder:
        holder.execute(text("SELECT id FROM users WHERE id = :id FOR UPDATE"), {"id": student_id})
        thread = threading.Thread(target=blocked)
        thread.start()
        assert not done.wait(timeout=1.0)
        with Session(clean_db) as own_session:
            progress.submit_answer(own_session, other, SHOVELS, CORRECT[SHOVELS], new_key())
        holder.rollback()
    thread.join(timeout=10)
    assert done.is_set()
    assert all_rows(session) == (2, 2, 0)


# --- The database refuses duplicates even without the application ------------------------------------


@pytest.fixture
def completed(answer, session) -> dict:
    """One student with the first concept completed; returns IDs for raw SQL."""
    body = answer("alex", SHOVELS, BOOKS)
    session.rollback()
    user_id = session.scalar(text("SELECT id FROM users"))
    return {"user": user_id, "attempt": uuid.UUID(body["attempt_id"])}


def insert_attempt(session, user, **overrides) -> uuid.UUID:
    values = {
        "id": uuid.uuid4(), "user_id": user, "question_id": LANTERNS, "idempotency_key": new_key(), "mode": "graded",
        "selected_option_id": CORRECT[LANTERNS], "status": "correct", "credit_earned": False, "concept_completed": False,
        "xp_awarded": 0, **overrides,
    }
    columns = ", ".join(values)
    session.execute(text(f"INSERT INTO question_attempts ({columns}) VALUES ({', '.join(':' + c for c in values)})"), values)
    return values["id"]


def test_the_database_rejects_a_second_credit_for_a_question(session, completed):
    attempt = insert_attempt(session, completed["user"])
    with pytest.raises(IntegrityError, match="pk_question_credits"):
        session.execute(
            text("INSERT INTO question_credits (user_id, question_id, attempt_id) VALUES (:u, :q, :a)"),
            {"u": completed["user"], "q": SHOVELS, "a": attempt},
        )
    session.rollback()


def test_the_database_rejects_a_second_completion_of_a_concept(session, completed):
    attempt = insert_attempt(session, completed["user"])
    with pytest.raises(IntegrityError, match="pk_concept_completions"):
        session.execute(
            text("INSERT INTO concept_completions (user_id, concept_id, attempt_id, xp_awarded) VALUES (:u, :c, :a, 20)"),
            {"u": completed["user"], "c": FIRST, "a": attempt},
        )
    session.rollback()


def test_the_database_rejects_one_attempt_paying_for_two_concepts(session, completed):
    with pytest.raises(IntegrityError, match="uq_concept_completions_attempt_id"):
        session.execute(
            text("INSERT INTO concept_completions (user_id, concept_id, attempt_id, xp_awarded) VALUES (:u, :c, :a, 20)"),
            {"u": completed["user"], "c": SECOND, "a": completed["attempt"]},
        )
    session.rollback()


def test_the_database_rejects_a_reused_idempotency_key(session, completed):
    key = session.scalar(text("SELECT idempotency_key FROM question_attempts LIMIT 1"))
    with pytest.raises(IntegrityError, match="uq_question_attempts_user_idempotency_key"):
        insert_attempt(session, completed["user"], idempotency_key=key)
    session.rollback()


@pytest.mark.parametrize(
    ("overrides", "constraint"),
    [
        ({"mode": "recharge", "credit_earned": True}, "ck_question_attempts_credit_needs_graded_correct"),
        ({"status": "incorrect", "credit_earned": True}, "ck_question_attempts_credit_needs_graded_correct"),
        ({"concept_completed": True}, "ck_question_attempts_completion_needs_credit"),
        ({"xp_awarded": 20}, "ck_question_attempts_xp_needs_completion"),
        ({"xp_awarded": -1}, "ck_question_attempts_xp_needs_completion"),
        ({"mode": "official"}, "ck_question_attempts_mode"),
        ({"status": "partial"}, "ck_question_attempts_status"),
        ({"question_id": "inventory.nope"}, "fk_question_attempts_question_id_questions"),
        ({"user_id": uuid.UUID(int=1)}, "fk_question_attempts_user_id_users"),
    ],
)
def test_the_database_rejects_impossible_attempts(session, completed, overrides, constraint):
    with pytest.raises(IntegrityError, match=constraint):
        insert_attempt(session, completed["user"], **overrides)
    session.rollback()


def test_the_database_rejects_negative_xp_and_unknown_references(session, completed):
    for concept, xp, constraint in (
        (SECOND, -5, "ck_concept_completions_non_negative_xp"),
        ("no.such.concept", 20, "fk_concept_completions_concept_id_concepts"),
    ):
        attempt = insert_attempt(session, completed["user"])
        with pytest.raises(IntegrityError, match=constraint):
            session.execute(
                text("INSERT INTO concept_completions (user_id, concept_id, attempt_id, xp_awarded) VALUES (:u, :c, :a, :x)"),
                {"u": completed["user"], "c": concept, "a": attempt, "x": xp},
            )
        session.rollback()


def test_curriculum_referenced_by_progress_cannot_be_deleted(session, completed):
    for statement, constraint in (
        ("DELETE FROM answer_options WHERE question_id = 'inventory.shovels'", "fk_"),
        ("DELETE FROM users", "fk_"),
    ):
        with pytest.raises(IntegrityError, match=constraint):
            session.execute(text(statement))
            session.commit()
        session.rollback()


# --- Recharge ---------------------------------------------------------------------------------------


def test_recharge_is_refused_until_the_assignment_is_completed(submit, answer, session):
    expected = {"detail": f"Recharge Practice unlocks when assignment {ASSIGNMENT} is completed"}
    for done in ((), (SHOVELS,), (BOOKS,), (SETTLEMENT,)):
        answer("alex", *done)
        for question in (SHOVELS, LANTERNS, SETTLEMENT):
            for option in (CORRECT[question], "nope"):
                response = submit("alex", question, option, recharge=True)
                assert response.status_code == 409 and response.json() == expected
    assert rows(session, "question_attempts") == 3
    assert submit("alex", PARTIAL, CORRECT[PARTIAL]).json()["assignment"]["status"] == "completed"
    assert submit("alex", SHOVELS, CORRECT[SHOVELS], recharge=True).status_code == 200


def test_recharge_grades_and_explains_but_never_rewards(submit, answer, get, session, bundled_json):
    answer("alex", SHOVELS, BOOKS, SETTLEMENT, PARTIAL)
    before = (get("alex", PROGRESS).json(), rows(session, "question_credits"), rows(session, "concept_completions"))
    explanations = {q["id"]: q["explanation"] for q in bundled_json["questions"]}

    # LANTERNS and two_invoices were never credited: Recharge must not credit them either.
    for question in CORRECT:
        for _ in range(2):
            body = submit("alex", question, CORRECT[question], recharge=True).json()
            assert (body["mode"], body["status"], body["correct"], body["feedback"]) == ("recharge", "correct", True, explanations[question])
            assert (body["credit_earned"], body["concept_completed"], body["xp_awarded"], body["total_xp"]) == (False, False, 0, 2 * XP)
    wrong = submit("alex", SHOVELS, WRONG[SHOVELS], recharge=True).json()
    assert (wrong["status"], wrong["feedback"], wrong["xp_awarded"]) == ("incorrect", explanations[SHOVELS], 0)
    invalid = submit("alex", SHOVELS, "nope", recharge=True).json()
    assert (invalid["status"], invalid["xp_awarded"]) == ("invalid_option", 0)

    after = (get("alex", PROGRESS).json(), rows(session, "question_credits"), rows(session, "concept_completions"))
    assert after == before
    assert before[0]["assignments"][0]["concepts"][0]["credited_question_ids"] == [SHOVELS, BOOKS]
    session.rollback()
    recharge_rows = session.execute(
        text("SELECT count(*), bool_or(credit_earned), bool_or(concept_completed), sum(xp_awarded) FROM question_attempts WHERE mode = 'recharge'")
    ).one()
    assert tuple(recharge_rows) == (14, False, False, 0)


def test_recharge_attempts_do_not_count_as_official_attempts(submit, answer, get):
    answer("alex", SHOVELS, BOOKS, SETTLEMENT, PARTIAL)
    submit("alex", LANTERNS, CORRECT[LANTERNS], recharge=True)
    concept = get("alex", ASSIGNMENT_PROGRESS).json()["concepts"][0]
    assert concept["attempted_question_ids"] == [SHOVELS, BOOKS]
    history = get("alex", ATTEMPTS).json()["attempts"]
    assert (history[0]["mode"], history[0]["question_id"], history[0]["credit_earned"]) == ("recharge", LANTERNS, False)


def test_recharge_is_idempotent_too(submit, answer, session):
    answer("alex", SHOVELS, BOOKS, SETTLEMENT, PARTIAL)
    key = new_key()
    first = submit("alex", LANTERNS, CORRECT[LANTERNS], key=key, recharge=True).json()
    assert submit("alex", LANTERNS, CORRECT[LANTERNS], key=key, recharge=True).json() == {**first, "replayed": True}
    assert submit("alex", LANTERNS, CORRECT[LANTERNS], key=key).status_code == 422  # not replayable as graded
    assert rows(session, "question_attempts") == 5


def test_one_students_completion_does_not_unlock_recharge_for_another(submit, answer):
    answer("alex", SHOVELS, BOOKS, SETTLEMENT, PARTIAL)
    assert submit("sam", SHOVELS, CORRECT[SHOVELS], recharge=True).status_code == 409


def test_the_recharge_service_cannot_be_given_another_mode(session, student_id):
    with pytest.raises(ValueError, match="Unknown submission mode"):
        progress.submit_answer(session, student_id, SHOVELS, CORRECT[SHOVELS], new_key(), "bonus")
    assert MODE_GRADED != MODE_RECHARGE


def test_authenticated_grading_matches_the_shared_java_fixture(submit, answer, grading_fixture, bundled_json):
    """Every shared parity case, through the authenticated endpoint (Recharge reaches every question)."""
    answer("alex", SHOVELS, BOOKS, SETTLEMENT, PARTIAL)
    explanations = {q["id"]: q["explanation"] for q in bundled_json["questions"]}
    for case in grading_fixture["cases"]:
        body = submit("alex", case["question_id"], case["selected_option_id"], recharge=True).json()
        expected = expected_feedback(case, grading_fixture, explanations[case["question_id"]])
        assert (body["status"], body["feedback"]) == (case["expected_status"], expected), case["name"]


# --- Progress retrieval -----------------------------------------------------------------------------


def test_a_new_student_starts_at_zero(get):
    body = get("alex", PROGRESS).json()
    assert (body["total_xp"], body["completed_concept_ids"]) == (0, [])
    assert [a["assignment_id"] for a in body["assignments"]] == [ASSIGNMENT]  # Coming Soon assignments are left out
    assignment = body["assignments"][0]
    assert assignment == get("alex", ASSIGNMENT_PROGRESS).json()
    assert (assignment["status"], assignment["completed_concepts"], assignment["total_concepts"]) == ("not_started", 0, 2)
    assert (assignment["current_concept_id"], assignment["xp_earned"]) == (FIRST, 0)
    assert assignment["concepts"] == [
        {
            "concept_id": concept, "correct_answers": 0, "required_correct_answers": 2, "completed": False, "locked": locked,
            "xp_awarded": 0, "completed_at": None, "credited_question_ids": [], "attempted_question_ids": [],
        }
        for concept, locked in ((FIRST, False), (SECOND, True))
    ]
    assert get("alex", ATTEMPTS).json() == {"attempts": [], "limit": 50, "offset": 0}


def test_progress_reports_zero_one_and_two_of_two(get, answer):
    assert mastery(get("alex", ASSIGNMENT_PROGRESS).json()) == ["0/2", "0/2"]
    answer("alex", SHOVELS)
    assert mastery(get("alex", ASSIGNMENT_PROGRESS).json()) == ["1/2", "0/2"]
    answer("alex", LANTERNS)
    assert mastery(get("alex", ASSIGNMENT_PROGRESS).json()) == ["2/2", "0/2"]
    answer("alex", PARTIAL)
    body = get("alex", PROGRESS).json()
    assert mastery(body["assignments"][0]) == ["2/2", "1/2"]
    assert (body["total_xp"], body["completed_concept_ids"]) == (XP, [FIRST])
    answer("alex", SETTLEMENT)
    body = get("alex", PROGRESS).json()
    assert (body["total_xp"], body["completed_concept_ids"]) == (2 * XP, [FIRST, SECOND])
    assert body["assignments"][0]["status"] == "completed"
    # Question IDs are reported in curriculum order, whatever order they were answered in.
    assert body["assignments"][0]["concepts"][1]["credited_question_ids"] == [SETTLEMENT, PARTIAL]


def test_assignment_progress_for_unknown_and_coming_soon_assignments(get):
    missing = get("alex", "/api/v1/progress/assignments/nope")
    assert missing.status_code == 404 and missing.json() == {"detail": "Assignment not found: nope"}
    soon = get("alex", "/api/v1/progress/assignments/accounting101.trial_balance").json()
    assert (soon["status"], soon["total_concepts"], soon["concepts"], soon["current_concept_id"]) == ("not_started", 0, [], None)


def test_attempt_history_is_newest_first_and_pageable(submit, answer, get):
    submit("alex", SHOVELS, WRONG[SHOVELS])
    submit("alex", SHOVELS, "nope")
    answer("alex", SHOVELS, BOOKS)
    history = get("alex", ATTEMPTS).json()["attempts"]
    assert [(a["question_id"], a["status"], a["credit_earned"], a["concept_completed"], a["xp_awarded"]) for a in history] == [
        (BOOKS, "correct", True, True, XP),
        (SHOVELS, "correct", True, False, 0),
        (SHOVELS, "invalid_option", False, False, 0),
        (SHOVELS, "incorrect", False, False, 0),
    ]
    assert all((a["concept_id"], a["assignment_id"], a["mode"]) == (FIRST, ASSIGNMENT, "graded") for a in history)
    assert history[3]["selected_option_id"] == WRONG[SHOVELS]
    assert [a["submitted_at"] for a in history] == sorted((a["submitted_at"] for a in history), reverse=True)

    page = get("alex", ATTEMPTS, limit=2, offset=1).json()
    assert page["attempts"] == history[1:3] and (page["limit"], page["offset"]) == (2, 1)
    assert get("alex", ATTEMPTS, question_id=BOOKS).json()["attempts"] == history[:1]
    assert get("alex", ATTEMPTS, question_id="nope").json()["attempts"] == []
    for params in ({"limit": 0}, {"limit": 101}, {"offset": -1}, {"limit": "many"}):
        assert get("alex", ATTEMPTS, **params).status_code == 422


def test_progress_responses_never_contain_answer_keys(get, submit, answer):
    answer("alex", SHOVELS)
    submit("alex", BOOKS, WRONG[BOOKS])
    for path in (PROGRESS, ASSIGNMENT_PROGRESS, ATTEMPTS):
        response = get("alex", path)
        assert not keys_in(response.json()) & ANSWER_KEY_FIELDS, path
        assert CORRECT[BOOKS] not in response.text and CORRECT[LANTERNS] not in response.text, path


def test_question_retrieval_still_hides_answer_keys_from_signed_in_students(api, idp, answer):
    answer("alex", SHOVELS)
    response = api.get(f"/api/v1/assignments/{ASSIGNMENT}/questions", headers=bearer(idp.token("alex")))
    assert not keys_in(response.json()) & ANSWER_KEY_FIELDS
    schemas = api.get("/openapi.json").json()["components"]["schemas"]
    for name in ("ConceptProgress", "AssignmentProgress", "ProgressSummary", "AttemptRecord", "SubmissionRequest"):
        assert not set(schemas[name]["properties"]) & ANSWER_KEY_FIELDS, name
    assert set(schemas["SubmissionRequest"]["properties"]) == {"selected_option_id"}


# --- Authentication, roles and isolation --------------------------------------------------------------

ENDPOINTS = [
    ("post", f"/api/v1/questions/{SHOVELS}/submissions"),
    ("post", f"/api/v1/questions/{SHOVELS}/recharge-submissions"),
    ("get", PROGRESS),
    ("get", ASSIGNMENT_PROGRESS),
    ("get", ATTEMPTS),
]


def call(api, method: str, path: str, headers: dict):
    if method == "post":
        return api.post(path, json={"selected_option_id": CORRECT[SHOVELS]}, headers={"Idempotency-Key": new_key(), **headers})
    return api.get(path, headers=headers)


@pytest.mark.parametrize(("method", "path"), ENDPOINTS)
def test_unauthenticated_requests_are_refused(api, idp, session, method, path):
    expired = idp.token("alex", exp=1)
    for headers in ({}, {"Authorization": "Bearer garbage"}, bearer(expired), bearer(idp.token("alex", key=idp.other_key))):
        response = call(api, method, path, headers)
        assert response.status_code == 401
        assert response.json() == {"detail": "Not authenticated"}
    assert all_rows(session) == (0, 0, 0) and rows(session, "users") == 0


@pytest.mark.parametrize(("method", "path"), ENDPOINTS)
def test_disabled_students_are_refused(api, idp, session, answer, method, path):
    answer("alex", SHOVELS)
    user_id = session.scalar(text("SELECT id FROM users"))
    set_active(session, user_id, False)
    response = call(api, method, path, bearer(idp.token("alex")))
    assert response.status_code == 403 and response.json() == {"detail": "This account is disabled"}
    assert all_rows(session) == (1, 1, 0)

    set_active(session, user_id, True)
    assert call(api, method, path, bearer(idp.token("alex"))).status_code in (200, 409)  # 409: Recharge still locked


@pytest.mark.parametrize(("method", "path"), ENDPOINTS)
def test_teachers_have_no_student_progress(api, idp, session, method, path):
    user_id = uuid.UUID(api.get("/api/v1/users/me", headers=bearer(idp.token("teacher"))).json()["id"])
    set_role(session, user_id, "teacher")
    assert call(api, method, path, bearer(idp.token("teacher"))).status_code == 403
    assert all_rows(session) == (0, 0, 0)


@pytest.mark.parametrize(("method", "path"), ENDPOINTS)
def test_progress_endpoints_fail_closed_without_configured_authentication(client, idp, session, method, path):
    client.app.dependency_overrides[get_token_verifier] = lambda: None
    assert call(client, method, path, bearer(idp.token("alex"))).status_code == 503
    assert all_rows(session) == (0, 0, 0)


def test_students_only_ever_see_and_change_their_own_progress(api, idp, submit, answer, get, session):
    answer("alex", SHOVELS, BOOKS, SETTLEMENT)
    alex_id = api.get("/api/v1/users/me", headers=bearer(idp.token("alex"))).json()["id"]
    alex_before = (get("alex", PROGRESS).json(), get("alex", ATTEMPTS).json())

    fresh = get("sam", PROGRESS).json()
    assert (fresh["total_xp"], fresh["completed_concept_ids"], mastery(fresh["assignments"][0])) == (0, [], ["0/2", "0/2"])
    assert get("sam", ATTEMPTS).json()["attempts"] == []
    for path in (PROGRESS, ASSIGNMENT_PROGRESS, ATTEMPTS):
        named = api.get(path, params={"user_id": alex_id, "user": "alex"}, headers={**bearer(idp.token("sam")), "X-User-Id": alex_id})
        assert named.json() == get("sam", path).json()
        assert alex_id not in named.text
    for path in (f"/api/v1/users/{alex_id}/progress", f"/api/v1/progress/{alex_id}", f"/api/v1/progress/users/{alex_id}"):
        assert get("sam", path).status_code == 404

    # Sam is still locked out of the second concept although Alex unlocked it, and starts from 0/2.
    assert submit("sam", SETTLEMENT, CORRECT[SETTLEMENT]).status_code == 409
    sam = answer("sam", SHOVELS)
    assert (mastery(sam["assignment"]), sam["total_xp"]) == (["1/2", "0/2"], 0)
    assert (get("alex", PROGRESS).json(), get("alex", ATTEMPTS).json()) == alex_before
    assert all_rows(session) == (4, 4, 1)


def test_the_progress_api_takes_no_user_identifier(api):
    paths = api.get("/openapi.json").json()["paths"]
    student_paths = [p for p in paths if "/progress" in p or p.endswith("submissions")]
    assert len(student_paths) == 5
    for path in student_paths:
        for operation in paths[path].values():
            names = {parameter["name"] for parameter in operation.get("parameters", [])}
            assert names <= {"question_id", "assignment_id", "Idempotency-Key", "limit", "offset"}, path
        assert "user" not in path


# --- Persistence and the existing public API ----------------------------------------------------------


def test_progress_survives_a_backend_restart(api, idp, answer, get, submit, clean_db, database_url):
    answer("alex", SHOVELS, BOOKS, SETTLEMENT)
    key = new_key()
    last = submit("alex", SHOVELS, WRONG[SHOVELS], key=key).json()
    before = [get("alex", path).json() for path in (PROGRESS, ASSIGNMENT_PROGRESS, ATTEMPTS)]

    # A new process: fresh engine, fresh connection pool, fresh application object. Nothing is carried in memory.
    clean_db.dispose()
    engine = make_engine(database_url)
    factory = sessionmaker(bind=engine)

    def fresh_session():
        with factory() as request_session:
            yield request_session

    restarted = create_app()
    restarted.dependency_overrides[get_session] = fresh_session
    restarted.dependency_overrides[get_token_verifier] = lambda: idp.verifier()
    try:
        with TestClient(restarted) as client:
            headers = bearer(idp.token("alex"))
            assert [client.get(path, headers=headers).json() for path in (PROGRESS, ASSIGNMENT_PROGRESS, ATTEMPTS)] == before
            assert before[0]["total_xp"] == XP and mastery(before[1]) == ["2/2", "1/2"]

            # The idempotency record survived too, and mastery continues from where it stopped.
            replay = client.post(
                f"/api/v1/questions/{SHOVELS}/submissions", json={"selected_option_id": WRONG[SHOVELS]},
                headers={**headers, "Idempotency-Key": key},
            ).json()
            assert replay == {**last, "replayed": True}
            done = client.post(
                f"/api/v1/questions/{PARTIAL}/submissions", json={"selected_option_id": CORRECT[PARTIAL]},
                headers={**headers, "Idempotency-Key": new_key()},
            ).json()
            assert (done["concept_completed"], done["total_xp"], done["assignment"]["status"]) == (True, 2 * XP, "completed")
    finally:
        engine.dispose()


def test_the_public_stateless_grading_endpoint_is_unchanged_and_records_nothing(api, idp, session):
    for headers in ({}, bearer(idp.token("alex"))):
        response = api.post(f"/api/v1/questions/{SHOVELS}/grade", json={"selected_option_id": CORRECT[SHOVELS]}, headers=headers)
        assert response.status_code == 200
        assert set(response.json()) == {"question_id", "selected_option_id", "status", "correct", "valid", "feedback"}
        assert response.json()["status"] == "correct"
    assert all_rows(session) == (0, 0, 0)


def test_reimporting_the_curriculum_keeps_progress(answer, get, session, bundled_document):
    from mineledger_api.importer import import_curriculum

    answer("alex", SHOVELS, BOOKS)
    before = get("alex", PROGRESS).json()
    session.rollback()
    report = import_curriculum(session, bundled_document, "accounting_101", "test")
    assert not report.changed
    assert get("alex", PROGRESS).json() == before
    assert all_rows(session) == (2, 2, 1)
