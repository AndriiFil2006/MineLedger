"""Runs the same shared fixture as the Java SharedGradingFixtureTest, against the Python grader and the API.

The mutant tests feed deliberately broken graders through the same check and require it to fail for
every one, proving the fixture actually detects grading drift (it is not a rubber stamp).
"""

from __future__ import annotations

import pytest

from mineledger_api import api
from mineledger_api.grading import INVALID_OPTION_FEEDBACK, GradingResult, GradingStatus, grade_multiple_choice

CORRECT, INCORRECT, INVALID = GradingStatus.CORRECT, GradingStatus.INCORRECT, GradingStatus.INVALID_OPTION


def expected_feedback(case: dict, fixture: dict, explanation: str) -> str:
    return explanation if case["expected_feedback"] == "question_explanation" else fixture["messages"][case["expected_feedback"]]


def mismatches(grader, fixture: dict, document) -> list[str]:
    """Every fixture case `grader(option_ids, correct_id, explanation, selected)` gets wrong."""
    wrong = []
    for case in fixture["cases"]:
        question = document.multiple_choice[case["question_id"]]
        result = grader([o.id for o in question.options], question.correct_option_id, question.explanation, case["selected_option_id"])
        if result.status.value != case["expected_status"] or result.feedback != expected_feedback(case, fixture, question.explanation):
            wrong.append(case["name"])
    return wrong


def _case_insensitive(ids, correct, explanation, selected):
    lowered = {i.lower() for i in ids}
    if selected is None or selected.lower() not in lowered:
        return GradingResult(INVALID, INVALID_OPTION_FEEDBACK)
    return GradingResult(CORRECT if selected.lower() == correct.lower() else INCORRECT, explanation)


def _trims(ids, correct, explanation, selected):
    return grade_multiple_choice(ids, correct, explanation, selected.strip() if selected else selected)


def _unknown_is_incorrect(ids, correct, explanation, selected):
    if selected is None:
        return GradingResult(INVALID, INVALID_OPTION_FEEDBACK)
    return GradingResult(CORRECT if selected == correct else INCORRECT, explanation)


def _leaks_explanation(ids, correct, explanation, selected):
    real = grade_multiple_choice(ids, correct, explanation, selected)
    return real if real.valid else GradingResult(INVALID, explanation)


def _first_option_correct(ids, correct, explanation, selected):
    ids = list(ids)
    real = grade_multiple_choice(ids, correct, explanation, selected)
    return real if not real.valid else GradingResult(CORRECT if selected == ids[0] else INCORRECT, explanation)


def _empty_selection_is_incorrect(ids, correct, explanation, selected):
    if selected == "":
        return GradingResult(INCORRECT, explanation)
    return grade_multiple_choice(ids, correct, explanation, selected)


MUTANTS = {
    "case-insensitive IDs": _case_insensitive,
    "trims whitespace": _trims,
    "unknown option counts as incorrect": _unknown_is_incorrect,
    "explanation leaked on invalid": _leaks_explanation,
    "first option is always correct": _first_option_correct,
    "empty selection counts as incorrect": _empty_selection_is_incorrect,
}


def test_invalid_message_matches_fixture(grading_fixture):
    assert grading_fixture["fixture_version"] == 1
    assert grading_fixture["messages"]["invalid_option"] == INVALID_OPTION_FEEDBACK


def test_fixture_covers_every_bundled_option(grading_fixture, bundled_document):
    selected = {(c["question_id"], c["selected_option_id"]) for c in grading_fixture["cases"] if c["expected_status"] != "invalid_option"}
    every_option = {(q.id, o.id) for q in bundled_document.multiple_choice.values() for o in q.options}
    assert selected == every_option
    assert {c["expected_status"] for c in grading_fixture["cases"]} == {"correct", "incorrect", "invalid_option"}


def test_python_grader_matches_fixture(grading_fixture, bundled_document):
    assert mismatches(grade_multiple_choice, grading_fixture, bundled_document) == []


@pytest.mark.parametrize("name", MUTANTS)
def test_fixture_catches_broken_grader(name, grading_fixture, bundled_document):
    assert mismatches(MUTANTS[name], grading_fixture, bundled_document), f"fixture did not detect: {name}"


def api_mismatches(client, grading_fixture, bundled_document) -> list[str]:
    wrong = []
    for case in grading_fixture["cases"]:
        response = client.post(
            f"/api/v1/questions/{case['question_id']}/grade", json={"selected_option_id": case["selected_option_id"]}
        )
        body = response.json()
        explanation = bundled_document.multiple_choice[case["question_id"]].explanation
        if (
            response.status_code != 200
            or body["status"] != case["expected_status"]
            or body["correct"] != (case["expected_status"] == "correct")
            or body["valid"] != (case["expected_status"] != "invalid_option")
            or body["feedback"] != expected_feedback(case, grading_fixture, explanation)
        ):
            wrong.append(case["name"])
    return wrong


def test_api_grader_matches_fixture(client, grading_fixture, bundled_document):
    assert api_mismatches(client, grading_fixture, bundled_document) == []


def test_api_check_catches_a_broken_endpoint_grader(client, grading_fixture, bundled_document, monkeypatch):
    """Swap a broken grader into the live endpoint: the HTTP-level parity check must notice."""
    monkeypatch.setattr(api, "grade_multiple_choice", _trims)
    assert api_mismatches(client, grading_fixture, bundled_document) == ["invalid: option IDs are not trimmed"]
