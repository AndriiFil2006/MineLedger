from __future__ import annotations

import copy
import json
from decimal import Decimal

import pytest
from sqlalchemy import func, select, text

from mineledger_api import cli
from mineledger_api.config import BUNDLED_CURRICULUM
from mineledger_api.curriculum import parse_bytes
from mineledger_api.importer import CurriculumImportConflict, _Import, import_curriculum
from mineledger_api.models import (
    AnswerOption,
    Assignment,
    Concept,
    ConceptLesson,
    Course,
    CurriculumBundle,
    JournalAccountChoice,
    JournalLab,
    LessonWorkedExample,
    Question,
    Unit,
)

TABLE_MODELS = [
    CurriculumBundle, Course, Unit, Assignment, Concept, JournalLab, ConceptLesson, Question, AnswerOption,
    JournalAccountChoice, LessonWorkedExample,
]


def counts(session) -> dict[str, int]:
    return {m.__tablename__: session.scalar(select(func.count()).select_from(m)) for m in TABLE_MODELS}


def snapshot(session) -> dict[str, list]:
    """Every row of every curriculum table, excluding import timestamps."""
    result = {}
    for model in TABLE_MODELS:
        columns = [c for c in model.__table__.columns if c.name != "imported_at"]
        rows = session.execute(select(*columns).order_by(*model.__table__.primary_key.columns)).all()
        result[model.__tablename__] = [tuple(row) for row in rows]
    return result


def run(session, doc, bundle_id="accounting_101", source="test", **kwargs):
    """End the test's read transaction, then import (the importer owns its own transaction)."""
    session.rollback()
    return import_curriculum(session, doc, bundle_id, source, **kwargs)


def document(data: dict):
    return parse_bytes(json.dumps(data).encode("utf-8"))


def find(data: dict, table: str, entity_id: str) -> dict:
    return next(item for item in data[table] if item["id"] == entity_id)


def test_first_import_creates_every_record(session, bundled_document):
    report = run(session, bundled_document, "accounting_101", "test")
    expected = {
        "curriculum_bundles": 1, "courses": 1, "units": 3, "assignments": 8, "concepts": 2, "journal_labs": 1,
        "concept_lessons": 1, "questions": 9, "answer_options": 24, "journal_account_choices": 30,
        "lesson_worked_examples": 1,
    }
    assert dict(report.created) == expected
    assert counts(session) == expected
    assert report.changed and not report.updated and not report.answer_key_changes


def test_repeated_import_is_a_no_op(imported, bundled_document):
    before_counts, before_rows = counts(imported), snapshot(imported)
    for _ in range(2):
        report = run(imported, bundled_document, "accounting_101", "test")
        assert not report.changed
        assert not report.created and not report.updated and not report.replaced
    assert counts(imported) == before_counts
    assert snapshot(imported) == before_rows


def test_stable_ids_are_preserved(imported, bundled_json):
    def ids(model):
        return set(imported.scalars(select(model.id)))

    by_table = {"courses": Course, "units": Unit, "assignments": Assignment, "concepts": Concept,
                "questions": Question, "lessons": ConceptLesson, "labs": JournalLab}
    for table, model in by_table.items():
        assert ids(model) == {item["id"] for item in bundled_json[table]}, table
    stored_options = {tuple(row) for row in imported.execute(select(AnswerOption.question_id, AnswerOption.option_id))}
    assert stored_options == {
        (q["id"], o["id"]) for q in bundled_json["questions"] if q["type"] == "multiple_choice" for o in q["answer_options"]
    }
    assert imported.get(Assignment, "inventory_fundamentals").title == "Assignment 2.1: Journal Entries"


def test_relationships_order_and_content_match_the_file(imported, bundled_json):
    course = imported.get(Course, "accounting101")
    assert [u.id for u in course.units] == find(bundled_json, "courses", "accounting101")["unit_ids"]
    for unit_json in bundled_json["units"]:
        unit = imported.get(Unit, unit_json["id"])
        assert unit.course_id == unit_json["parent_course_id"] and unit.unit_number == unit_json["order"]
        assert [a.id for a in unit.assignments] == unit_json["assignment_ids"]
    for assignment_json in bundled_json["assignments"]:
        assignment = imported.get(Assignment, assignment_json["id"])
        assert assignment.availability == assignment_json["availability"]
        assert [c.id for c in assignment.concepts] == assignment_json["concept_ids"]
    for concept_json in bundled_json["concepts"]:
        concept = imported.get(Concept, concept_json["id"])
        assert [q.id for q in concept.questions] == concept_json["question_ids"]
        assert (concept.lesson.id if concept.lesson else None) == concept_json["lesson_id"]
        assert concept.required_correct_answers == concept_json["required_correct_answers"]
    for question_json in bundled_json["questions"]:
        question = imported.get(Question, question_json["id"])
        assert question.prompt == question_json["prompt"]
        assert question.explanation == question_json["explanation"]  # accounting explanations verbatim
        if question_json["type"] == "multiple_choice":
            assert question.correct_option_id == question_json["correct_answer_id"]
            assert [(o.option_id, o.text) for o in question.options] == [(o["id"], o["text"]) for o in question_json["answer_options"]]
        else:
            assert question.lab_id == question_json["lab_id"]
            assert question.correct_amount == Decimal(question_json["correct_amount"])
            assert (question.correct_debit_account, question.correct_credit_account) == (
                question_json["correct_debit_account"], question_json["correct_credit_account"])
            for side in ("debit", "credit"):
                stored = [c.account_id for c in question.account_choices if c.side == side]
                assert stored == question_json[f"{side}_accounts"]
    lab = imported.get(JournalLab, "intro_journal_practice")
    assert [q.id for q in lab.questions] == find(bundled_json, "labs", "intro_journal_practice")["question_ids"]
    lesson_json = bundled_json["lessons"][0]
    lesson = imported.get(ConceptLesson, lesson_json["id"])
    assert lesson.accounting_rules == lesson_json["accounting_rules"]
    assert lesson.common_mistakes == lesson_json["common_mistakes"]
    assert [e.calculation for e in lesson.worked_examples] == [e["calculation"] for e in lesson_json["worked_examples"]]


def test_content_update_changes_only_the_edited_records(imported, bundled_json):
    data = copy.deepcopy(bundled_json)
    shovels = find(data, "questions", "inventory.shovels")
    shovels["prompt"] += " (Revised.)"
    shovels["answer_options"].reverse()
    find(data, "lessons", "lesson.inventory.purchases_on_credit.perpetual")["worked_examples"][0]["title"] = "Restocking"
    report = run(imported, document(data), "accounting_101", "edited")
    assert report.updated["questions"] == 1 and report.unchanged["questions"] == 8
    assert report.updated["answer_options"] == 4 and report.unchanged["answer_options"] == 20
    assert report.replaced["lesson_worked_examples"] == 1
    assert report.updated["curriculum_bundles"] == 1 and report.answer_key_changes == []
    imported.expire_all()
    question = imported.get(Question, "inventory.shovels")
    assert question.prompt.endswith("(Revised.)")
    assert [o.option_id for o in question.options] == [o["id"] for o in shovels["answer_options"]]
    assert imported.get(CurriculumBundle, "accounting_101").content_sha256 == document(data).sha256


def test_new_records_are_added_and_answer_key_changes_are_reported(imported, bundled_json):
    data = copy.deepcopy(bundled_json)
    find(data, "questions", "payables.two_invoices")["correct_answer_id"] = "invoices.one_only"
    new_question = copy.deepcopy(find(data, "questions", "payables.books_partial"))
    new_question["id"] = "payables.new_partial"
    for option in new_question["answer_options"]:
        option["id"] = option["id"].replace("partial.", "new_partial.")
    new_question["correct_answer_id"] = "new_partial.payable_cash"
    data["questions"].append(new_question)
    find(data, "concepts", "payables.cash_payments")["question_ids"].append("payables.new_partial")
    report = run(imported, document(data), "accounting_101", "extended")
    assert report.created["questions"] == 1 and report.created["answer_options"] == 4
    assert report.answer_key_changes == ["payables.two_invoices"]
    imported.expire_all()
    assert imported.get(Question, "payables.two_invoices").correct_option_id == "invoices.one_only"
    assert [q.id for q in imported.get(Concept, "payables.cash_payments").questions][-1] == "payables.new_partial"


def _without_lanterns(data):
    find(data, "concepts", "inventory.purchases_on_credit")["question_ids"].remove("inventory.lanterns_delivery")
    data["questions"] = [q for q in data["questions"] if q["id"] != "inventory.lanterns_delivery"]


def _rename_option(data):
    question = find(data, "questions", "inventory.shovels")
    question["answer_options"][0]["id"] = "shovels.cash_renamed"


def _retype_question(data):
    journal = find(data, "questions", "journal.cash_services")
    find(data, "labs", "intro_journal_practice")["question_ids"].remove("journal.cash_services")
    template = copy.deepcopy(find(data, "questions", "payables.books_partial"))
    for option in template["answer_options"]:
        option["id"] = "retyped." + option["id"]
    template.update(id=journal["id"], correct_answer_id="retyped.partial.payable_cash")
    data["questions"] = [q for q in data["questions"] if q["id"] != journal["id"]] + [template]
    find(data, "concepts", "payables.cash_payments")["question_ids"].append(journal["id"])


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (_without_lanterns, "questions 'inventory.lanterns_delivery' was imported before but is missing"),
        (_rename_option, "answer option 'shovels.cash' of question 'inventory.shovels' was imported before"),
        (_retype_question, "cannot change type"),
    ],
    ids=["removed question", "renamed option", "changed question type"],
)
def test_removals_and_reassignments_are_refused_without_writes(imported, bundled_json, mutate, message):
    before = snapshot(imported)
    data = copy.deepcopy(bundled_json)
    mutate(data)
    with pytest.raises(CurriculumImportConflict, match=message):
        run(imported, document(data), "accounting_101", "removal")
    assert snapshot(imported) == before


def test_records_owned_by_another_bundle_are_not_overwritten(imported, bundled_document):
    before = snapshot(imported)
    with pytest.raises(CurriculumImportConflict, match="belongs to bundle 'accounting_101'; it will not be overwritten"):
        run(imported, bundled_document, "someone_else", "other")
    assert snapshot(imported) == before
    assert imported.get(CurriculumBundle, "someone_else") is None


def test_unrelated_bundles_are_left_untouched(imported, bundled_document):
    unrelated = {
        "schema_version": 1,
        "courses": [{"id": "other.course", "title": "Other", "description": "Other course.", "unit_ids": ["other.unit"]}],
        "units": [{"id": "other.unit", "parent_course_id": "other.course", "order": 1, "title": "U", "description": "U.",
                   "assignment_ids": ["other.assignment"]}],
        "assignments": [{"id": "other.assignment", "parent_unit_id": "other.unit", "title": "A", "description": "A.",
                         "availability": "coming_soon", "concept_ids": []}],
        "concepts": [], "questions": [], "lessons": [], "labs": [],
    }
    run(imported, document(unrelated), "other_bundle", "other")
    report = run(imported, bundled_document, "accounting_101", "again")
    assert not report.changed
    assert imported.get(Course, "other.course").bundle_id == "other_bundle"
    assert len(imported.scalars(select(Course.id)).all()) == 2


def test_dry_run_writes_nothing(session, bundled_document):
    report = run(session, bundled_document, "accounting_101", "test", dry_run=True)
    assert report.dry_run and report.created["questions"] == 9
    assert all(count == 0 for count in counts(session).values())


def test_failure_mid_import_rolls_back_everything(session, bundled_document, monkeypatch):
    def explode(*args, **kwargs):
        raise RuntimeError("simulated failure after earlier writes")

    monkeypatch.setattr(_Import, "_replace_details", explode)
    with pytest.raises(RuntimeError, match="simulated failure"):
        run(session, bundled_document, "accounting_101", "test")
    assert all(count == 0 for count in counts(session).values())


def test_cli_imports_idempotently_and_rejects_invalid_files(clean_db, database_url, tmp_path, capsys):
    assert cli.main(["import-curriculum", "--database-url", database_url]) == 0
    assert "Imported bundle 'accounting_101'" in capsys.readouterr().out
    assert cli.main(["import-curriculum", "--database-url", database_url]) == 0
    assert "no changes; database already matches the file" in capsys.readouterr().out

    broken = json.loads(BUNDLED_CURRICULUM.read_text(encoding="utf-8"))
    find(broken, "questions", "inventory.shovels")["correct_answer_id"] = "shovels.missing"
    path = tmp_path / "accounting_101.json"
    path.write_text(json.dumps(broken), encoding="utf-8")
    with clean_db.connect() as connection:
        before = connection.execute(text("SELECT correct_option_id FROM questions WHERE id = 'inventory.shovels'")).scalar_one()
    assert cli.main(["import-curriculum", "--file", str(path), "--database-url", database_url]) == 2
    assert "exactly one correct option" in capsys.readouterr().err
    with clean_db.connect() as connection:
        after = connection.execute(text("SELECT correct_option_id FROM questions WHERE id = 'inventory.shovels'")).scalar_one()
    assert before == after == "shovels.inventory_payable"
