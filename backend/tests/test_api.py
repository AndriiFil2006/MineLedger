from __future__ import annotations

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session

from mineledger_api.db import get_session, make_engine
from mineledger_api.main import create_app
from tests.test_importer import snapshot

ANSWER_KEY_FIELDS = {
    "correct_answer_id", "correct_option_id", "is_correct", "correct", "explanation", "correct_debit_account",
    "correct_credit_account", "correct_amount", "feedback",
}


def keys_in(value) -> set[str]:
    if isinstance(value, dict):
        return set(value) | {key for child in value.values() for key in keys_in(child)}
    if isinstance(value, list):
        return {key for child in value for key in keys_in(child)}
    return set()


def test_health(client):
    assert client.get("/health").json() == {"status": "ok", "database": "ok"}


def test_health_reports_an_unavailable_database():
    engine = make_engine("postgresql+psycopg://mineledger:wrong@127.0.0.1:1/none?connect_timeout=1")

    def broken() -> Iterator[Session]:
        with Session(engine) as session:
            yield session

    app = create_app()
    app.dependency_overrides[get_session] = broken
    with TestClient(app) as client:
        response = client.get("/health")
    engine.dispose()
    assert response.status_code == 503
    assert response.json() == {"status": "degraded", "database": "unavailable"}


def test_list_courses(client):
    assert client.get("/api/v1/courses").json() == [{
        "id": "accounting101",
        "title": "Accounting 101 — Financial Accounting",
        "description": "Explore financial accounting through Minecraft businesses, from fundamentals to adjusting entries.",
        "unit_ids": ["accounting101.fundamentals", "accounting101.recording_transactions", "accounting101.adjusting_entries"],
    }]


def test_course_detail(client):
    body = client.get("/api/v1/courses/accounting101").json()
    assert [(u["id"], u["number"]) for u in body["units"]] == [
        ("accounting101.fundamentals", 1), ("accounting101.recording_transactions", 2), ("accounting101.adjusting_entries", 3)]
    assert body["units"][1]["assignment_ids"] == ["inventory_fundamentals", "accounting101.posting_ledger", "accounting101.trial_balance"]


def test_unit_detail(client):
    body = client.get("/api/v1/units/accounting101.recording_transactions").json()
    assert body["course_id"] == "accounting101" and body["number"] == 2
    assert [(a["id"], a["availability"]) for a in body["assignments"]] == [
        ("inventory_fundamentals", "available"), ("accounting101.posting_ledger", "coming_soon"),
        ("accounting101.trial_balance", "coming_soon")]
    assert body["assignments"][0]["concept_ids"] == ["inventory.purchases_on_credit", "payables.cash_payments"]


def test_assignment_detail(client):
    body = client.get("/api/v1/assignments/inventory_fundamentals").json()
    assert body["unit_id"] == "accounting101.recording_transactions" and body["availability"] == "available"
    assert body["concepts"] == [
        {"id": "inventory.purchases_on_credit", "title": "Recording Inventory Purchases on Credit",
         "description": body["concepts"][0]["description"], "required_correct_answers": 2, "question_count": 3,
         "lesson_id": "lesson.inventory.purchases_on_credit.perpetual"},
        {"id": "payables.cash_payments", "title": "Paying Suppliers", "description": body["concepts"][1]["description"],
         "required_correct_answers": 2, "question_count": 3, "lesson_id": None},
    ]


def test_concept_detail_includes_its_lesson(client, bundled_json):
    body = client.get("/api/v1/concepts/inventory.purchases_on_credit").json()
    assert body["question_ids"] == ["inventory.shovels", "inventory.books_discount", "inventory.lanterns_delivery"]
    lesson_json = bundled_json["lessons"][0]
    assert body["lesson"] == {key: lesson_json[key] for key in body["lesson"]}
    assert client.get("/api/v1/concepts/payables.cash_payments").json()["lesson"] is None


def test_assignment_questions_in_concept_order(client, bundled_json):
    body = client.get("/api/v1/assignments/inventory_fundamentals/questions").json()
    assert [q["id"] for q in body["questions"]] == [
        "inventory.shovels", "inventory.books_discount", "inventory.lanterns_delivery",
        "payables.shovels_settlement", "payables.books_partial", "payables.two_invoices"]
    first = body["questions"][0]
    source = next(q for q in bundled_json["questions"] if q["id"] == "inventory.shovels")
    assert first == {
        "id": "inventory.shovels", "concept_id": "inventory.purchases_on_credit", "type": "multiple_choice",
        "prompt": source["prompt"], "difficulty": None, "answer_options": source["answer_options"],
    }


def test_coming_soon_assignment_has_no_questions(client):
    body = client.get("/api/v1/assignments/accounting101.accruals/questions").json()
    assert body == {"assignment_id": "accounting101.accruals", "questions": []}


def test_answer_keys_and_explanations_are_hidden_from_retrieval(client, bundled_json):
    paths = [
        "/api/v1/courses", "/api/v1/courses/accounting101", "/api/v1/units/accounting101.recording_transactions",
        "/api/v1/assignments/inventory_fundamentals", "/api/v1/concepts/inventory.purchases_on_credit",
        "/api/v1/concepts/payables.cash_payments", "/api/v1/assignments/inventory_fundamentals/questions",
    ]
    explanations = [q["explanation"] for q in bundled_json["questions"]]
    for path in paths:
        response = client.get(path)
        assert response.status_code == 200, path
        leaked = keys_in(response.json()) & (ANSWER_KEY_FIELDS - {"explanation"})
        assert not leaked, (path, leaked)
        assert not any(text in response.text for text in explanations), path
    questions = client.get("/api/v1/assignments/inventory_fundamentals/questions").json()["questions"]
    for question in questions:
        assert set(question) == {"id", "concept_id", "type", "prompt", "difficulty", "answer_options"}
        assert all(set(option) == {"id", "text"} for option in question["answer_options"])


def test_openapi_schemas_for_questions_have_no_answer_key(client):
    components = client.get("/openapi.json").json()["components"]["schemas"]
    for name in ("PublicQuestion", "PublicAnswerOption", "AssignmentQuestions"):
        assert not set(components[name]["properties"]) & ANSWER_KEY_FIELDS, name


def test_grade_correct(client, bundled_json):
    explanation = next(q for q in bundled_json["questions"] if q["id"] == "inventory.shovels")["explanation"]
    response = client.post("/api/v1/questions/inventory.shovels/grade", json={"selected_option_id": "shovels.inventory_payable"})
    assert response.status_code == 200
    assert response.json() == {
        "question_id": "inventory.shovels", "selected_option_id": "shovels.inventory_payable", "status": "correct",
        "correct": True, "valid": True, "feedback": explanation,
    }


def test_grade_incorrect(client):
    body = client.post("/api/v1/questions/payables.books_partial/grade", json={"selected_option_id": "partial.remaining"}).json()
    assert (body["status"], body["correct"], body["valid"]) == ("incorrect", False, True)
    assert body["feedback"].startswith("Record only the 30 emeralds paid today")


@pytest.mark.parametrize("payload", [{"selected_option_id": "books.inventory_payable"}, {"selected_option_id": None}, {}],
                         ids=["foreign option", "null", "missing"])
def test_grade_invalid_option(client, payload):
    body = client.post("/api/v1/questions/inventory.shovels/grade", json=payload).json()
    assert body["status"] == "invalid_option" and body["valid"] is False and body["correct"] is False
    assert body["feedback"] == "Select one of this question's four answers before submitting."


def test_grade_rejects_malformed_requests(client):
    assert client.post("/api/v1/questions/inventory.shovels/grade", json={"selected_option_id": 3}).status_code == 422
    assert client.post("/api/v1/questions/inventory.shovels/grade", json={"option": "x"}).status_code == 422
    response = client.post("/api/v1/questions/journal.cash_services/grade", json={"selected_option_id": "CASH"})
    assert response.status_code == 422 and "multiple_choice only" in response.json()["detail"]


def test_grading_is_stateless(client, imported):
    before = snapshot(imported)
    first = [client.post("/api/v1/questions/inventory.shovels/grade", json={"selected_option_id": o}).json()
             for o in ("shovels.cash", "shovels.inventory_payable", "shovels.cash")]
    assert [r["status"] for r in first] == ["incorrect", "correct", "incorrect"]
    assert first[0] == first[2]
    imported.expire_all()
    assert snapshot(imported) == before  # no table (curriculum or otherwise) changed


@pytest.mark.parametrize(
    ("method", "path", "message"),
    [
        ("get", "/api/v1/courses/accounting999", "Course not found: accounting999"),
        ("get", "/api/v1/units/accounting101.nope", "Unit not found: accounting101.nope"),
        ("get", "/api/v1/assignments/nope", "Assignment not found: nope"),
        ("get", "/api/v1/assignments/nope/questions", "Assignment not found: nope"),
        ("get", "/api/v1/concepts/inventory.nope", "Concept not found: inventory.nope"),
        ("post", "/api/v1/questions/inventory.nope/grade", "Question not found: inventory.nope"),
    ],
)
def test_missing_records_return_404(client, method, path, message):
    kwargs = {"json": {"selected_option_id": "x"}} if method == "post" else {}
    response = getattr(client, method)(path, **kwargs)
    assert response.status_code == 404 and response.json() == {"detail": message}


def test_empty_database_serves_no_courses(clean_db):
    def override() -> Iterator[Session]:
        with Session(clean_db) as session:
            yield session

    app = create_app()
    app.dependency_overrides[get_session] = override
    with TestClient(app) as client:
        assert client.get("/api/v1/courses").json() == []
        assert client.get("/api/v1/courses/accounting101").status_code == 404
