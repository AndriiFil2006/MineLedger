"""Strict schema-1 curriculum parsing and validation.

This mirrors `minecraft-mod/.../curriculum/CurriculumLoader.java` and the Java domain constructors it relies
on (`MultipleChoiceQuestion`, `AccountingConcept`, `Assignment`, `Unit`, `Course`, `ConceptLesson`,
`AccountingQuestion`). The JSON file is the only content source: nothing here defines questions.
A file that the Minecraft client would reject must also be rejected here, before any database write.
"""

from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Callable
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
MAX_BYTES = 16 * 1024 * 1024
MAX_DEPTH = 32
MAX_AMOUNT_INPUT_LENGTH = 32  # AccountingAnswer.MAX_AMOUNT_INPUT_LENGTH
# Must equal com.mineledger.accounting.Account; tests/test_curriculum_validation.py checks the Java enum.
ACCOUNTS = ("CASH", "INVENTORY", "ACCOUNTS_PAYABLE", "ADVERTISING_EXPENSE", "SERVICE_REVENUE")
AVAILABILITY = ("available", "coming_soon")
_JAVA_REGEX_WHITESPACE = re.compile(r"[ \t\n\x0b\f\r]+")  # Java's \s, used for option-text uniqueness
_JAVA_BIG_DECIMAL = re.compile(r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?")  # new BigDecimal(String)


class CurriculumValidationError(ValueError):
    """Raised for malformed, inconsistent or unsupported curriculum content."""


@dataclass(frozen=True)
class CourseDef:
    id: str
    title: str
    description: str
    unit_ids: tuple[str, ...]


@dataclass(frozen=True)
class UnitDef:
    id: str
    course_id: str
    number: int
    title: str
    description: str
    assignment_ids: tuple[str, ...]


@dataclass(frozen=True)
class AssignmentDef:
    id: str
    unit_id: str
    title: str
    description: str
    availability: str
    concept_ids: tuple[str, ...]


@dataclass(frozen=True)
class ConceptDef:
    id: str
    assignment_id: str
    title: str
    description: str
    required_correct_answers: int
    lesson_id: str | None
    question_ids: tuple[str, ...]


@dataclass(frozen=True)
class OptionDef:
    id: str
    text: str


@dataclass(frozen=True)
class MultipleChoiceDef:
    id: str
    concept_id: str
    prompt: str
    options: tuple[OptionDef, ...]
    correct_option_id: str
    explanation: str
    difficulty: str | None


@dataclass(frozen=True)
class JournalEntryDef:
    id: str
    lab_id: str
    prompt: str
    debit_accounts: tuple[str, ...]
    credit_accounts: tuple[str, ...]
    correct_debit_account: str
    correct_credit_account: str
    correct_amount: Decimal
    explanation: str
    difficulty: str | None


@dataclass(frozen=True)
class WorkedExampleDef:
    title: str
    scenario: str
    calculation: str
    debit_entry: str
    credit_entry: str
    explanation: str


@dataclass(frozen=True)
class LessonDef:
    id: str
    concept_id: str
    title: str
    learning_objective: str
    explanation: str
    accounting_rules: tuple[str, ...]
    worked_examples: tuple[WorkedExampleDef, ...]
    common_mistakes: tuple[str, ...]
    takeaway: str


@dataclass(frozen=True)
class LabDef:
    id: str
    title: str
    question_ids: tuple[str, ...]


@dataclass(frozen=True)
class CurriculumDocument:
    """Validated content. Dicts preserve file order; reference tuples define presentation order."""

    schema_version: int
    sha256: str
    courses: dict[str, CourseDef]
    units: dict[str, UnitDef]
    assignments: dict[str, AssignmentDef]
    concepts: dict[str, ConceptDef]
    multiple_choice: dict[str, MultipleChoiceDef]
    journal_entries: dict[str, JournalEntryDef]
    lessons: dict[str, LessonDef]
    labs: dict[str, LabDef]


# ---------------------------------------------------------------- reading


def load_file(path: Path | str) -> CurriculumDocument:
    path = Path(path)
    with path.open("rb") as handle:
        data = handle.read(MAX_BYTES + 1)
    return parse_bytes(data, source=str(path))


def parse_bytes(data: bytes, source: str = "<memory>") -> CurriculumDocument:
    try:
        if len(data) > MAX_BYTES:
            raise CurriculumValidationError("Curriculum is too large")
        try:
            text = data.decode("utf-8", errors="strict")
        except UnicodeDecodeError as error:
            raise CurriculumValidationError(f"Curriculum is not valid UTF-8: {error}") from None
        try:
            root = json.loads(text, object_pairs_hook=_unique_object, parse_constant=_reject_constant)
        except json.JSONDecodeError as error:
            raise CurriculumValidationError(f"Malformed JSON: {error}") from None
        _check_depth(root, 0)
        if not isinstance(root, dict):
            raise CurriculumValidationError("Curriculum root must be a JSON object")
        return _resolve(root, hashlib.sha256(data).hexdigest())
    except CurriculumValidationError as error:
        raise CurriculumValidationError(f"{source}: {error}") from None


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise CurriculumValidationError(f"Duplicate JSON field {key}")
        result[key] = value
    return result


def _reject_constant(name: str) -> Any:
    raise CurriculumValidationError(f"Unsupported JSON value {name}")


def _check_depth(value: Any, depth: int) -> None:
    if depth > MAX_DEPTH:
        raise CurriculumValidationError(f"JSON nesting exceeds {MAX_DEPTH} levels")
    children = value.values() if isinstance(value, dict) else value if isinstance(value, list) else ()
    for child in children:
        _check_depth(child, depth + 1)


# ---------------------------------------------------------------- field helpers (CurriculumLoader parity)


def _label(obj: dict[str, Any]) -> str:
    return json.dumps(obj["id"]) if "id" in obj else "root"


def _field(obj: dict[str, Any], name: str) -> Any:
    if name not in obj:
        raise CurriculumValidationError(f"{_label(obj)}: missing required field {name}")
    return obj[name]


def _text(obj: dict[str, Any], name: str) -> str:
    value = _field(obj, name)
    if not isinstance(value, str) or not value.strip():
        raise CurriculumValidationError(f"{_label(obj)}: {name} must be nonblank text")
    return value


def _optional_text(obj: dict[str, Any], name: str, fallback: str) -> str:
    if name not in obj:
        return fallback
    value = obj[name]
    if not isinstance(value, str):
        raise CurriculumValidationError(f"{_label(obj)}: {name} must be text")
    return value


def _integer(obj: dict[str, Any], name: str) -> int:
    value = _field(obj, name)
    # Java accepts any JSON number with an exact int value (2 or 2.0), never booleans.
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise CurriculumValidationError(f"{_label(obj)}: {name} must be an integer")
    if isinstance(value, float) and not value.is_integer():
        raise CurriculumValidationError(f"{_label(obj)}: {name} must be an integer")
    number = int(value)
    if not -(2**31) <= number < 2**31:
        raise CurriculumValidationError(f"{_label(obj)}: {name} must be an integer")
    return number


def _array(obj: dict[str, Any], name: str) -> list[Any]:
    value = _field(obj, name)
    if not isinstance(value, list):
        raise CurriculumValidationError(f"{_label(obj)}: {name} must be an array")
    return value


def _object(value: Any, context: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise CurriculumValidationError(f"{context}: expected a JSON object")
    return value


def _strings(obj: dict[str, Any], name: str) -> tuple[str, ...]:
    result = []
    for value in _array(obj, name):
        if not isinstance(value, str) or not value.strip():
            raise CurriculumValidationError(f"{_label(obj)}: invalid text in {name}")
        result.append(value)
    return tuple(result)


def _keys(obj: dict[str, Any], *allowed: str) -> None:
    for name in obj:
        if name not in allowed:
            raise CurriculumValidationError(f"{_label(obj)}: unknown field {name}")


def _optional_difficulty(obj: dict[str, Any]) -> str | None:
    return _text(obj, "difficulty") if "difficulty" in obj else None


def _index(root: dict[str, Any], table: str) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for element in _array(root, table):
        obj = _object(element, table)
        entity_id = _text(obj, "id")
        if entity_id in result:
            raise CurriculumValidationError(f"{table}: duplicate ID {entity_id}")
        result[entity_id] = obj
    return result


def _references(owner: dict[str, Any], name: str, values: dict[str, Any], used: set[str]) -> tuple[str, ...]:
    result = []
    for entity_id in _strings(owner, name):
        if entity_id not in values:
            raise CurriculumValidationError(f"{_label(owner)}.{name}: missing reference {entity_id}")
        if entity_id in used:
            raise CurriculumValidationError(f"{_label(owner)}: duplicate or multiply-owned reference {entity_id}")
        used.add(entity_id)
        result.append(entity_id)
    return tuple(result)


def _all_used(kind: str, values: dict[str, Any], used: set[str]) -> None:
    orphaned = sorted(set(values) - used)
    if orphaned:
        raise CurriculumValidationError(f"Unreferenced {kind} IDs: {orphaned}")


def _check(condition: bool, context: str, message: str) -> None:
    if not condition:
        raise CurriculumValidationError(f"{context}: {message}")


def _accounts(obj: dict[str, Any], name: str) -> tuple[str, ...]:
    ids = _strings(obj, name)
    if len(set(ids)) != len(ids):
        raise CurriculumValidationError(f"{_label(obj)}: duplicate account in {name}")
    for account in ids:
        _account(obj, account)
    return ids


def _account(obj: dict[str, Any], value: str) -> str:
    if value not in ACCOUNTS:
        raise CurriculumValidationError(f"{_label(obj)}: unknown account {value}")
    return value


def _java_blank(value: str) -> bool:
    return not value.strip()


# ---------------------------------------------------------------- entity validation


def _multiple_choice(entity_id: str, q: dict[str, Any]) -> MultipleChoiceDef:
    _keys(q, "id", "concept_id", "type", "prompt", "answer_options", "correct_answer_id", "explanation", "difficulty")
    options = []
    for item in _array(q, "answer_options"):
        option = _object(item, f"question {entity_id} answer_options")
        _keys(option, "id", "text")
        options.append(OptionDef(_text(option, "id"), _text(option, "text")))
    question = MultipleChoiceDef(
        id=entity_id,
        concept_id=_text(q, "concept_id"),
        prompt=_text(q, "prompt"),
        options=tuple(options),
        correct_option_id=_text(q, "correct_answer_id"),
        explanation=_text(q, "explanation"),
        difficulty=_optional_difficulty(q),
    )
    normalized = {_JAVA_REGEX_WHITESPACE.sub(" ", option.text.strip()).lower() for option in options}
    _check(
        len(options) == 4
        and len({option.id for option in options}) == 4
        and len(normalized) == 4
        and sum(option.id == question.correct_option_id for option in options) == 1,
        f"question {entity_id}",
        "A question requires four unique options and exactly one correct option ID",
    )
    return question


def _journal_entry(entity_id: str, q: dict[str, Any]) -> JournalEntryDef:
    _keys(
        q, "id", "lab_id", "type", "prompt", "debit_accounts", "credit_accounts", "correct_debit_account",
        "correct_credit_account", "correct_amount", "explanation", "difficulty",
    )
    context = f"question {entity_id}"
    raw_amount = _text(q, "correct_amount")
    _check(bool(_JAVA_BIG_DECIMAL.fullmatch(raw_amount)), context, "correct_amount is not a decimal number")
    try:
        amount = Decimal(raw_amount)
    except InvalidOperation:
        raise CurriculumValidationError(f"{context}: correct_amount is not a decimal number") from None
    question = JournalEntryDef(
        id=entity_id,
        lab_id=_text(q, "lab_id"),
        prompt=_text(q, "prompt"),
        debit_accounts=_accounts(q, "debit_accounts"),
        credit_accounts=_accounts(q, "credit_accounts"),
        correct_debit_account=_account(q, _text(q, "correct_debit_account")),
        correct_credit_account=_account(q, _text(q, "correct_credit_account")),
        correct_amount=amount,
        explanation=_text(q, "explanation"),
        difficulty=_optional_difficulty(q),
    )
    # AccountingQuestion: positive, at most two decimals, answerable within the client's input length.
    # Same arithmetic as Java: stripTrailingZeros(), then precision/scale. Done on the digit tuple because
    # Decimal.normalize() rounds to the context precision (28 digits) while Java's operation is exact.
    _, digit_tuple, exponent = amount.as_tuple()
    digits, exponent = list(digit_tuple), int(exponent)
    while len(digits) > 1 and digits[-1] == 0:
        digits.pop()
        exponent += 1
    scale = -exponent
    integer_digits = max(1, len(digits) - scale)
    input_length = integer_digits + (1 + scale if scale > 0 else 0)
    _check(
        question.correct_debit_account in question.debit_accounts
        and question.correct_credit_account in question.credit_accounts
        and question.correct_debit_account != question.correct_credit_account
        and amount > 0
        and scale <= 2
        and input_length <= MAX_AMOUNT_INPUT_LENGTH,
        context,
        "Invalid single-debit, single-credit question",
    )
    return question


def _lesson(entity_id: str, lesson: dict[str, Any]) -> LessonDef:
    _keys(
        lesson, "id", "concept_id", "title", "learning_objective", "explanation", "accounting_rules",
        "worked_examples", "common_mistakes", "takeaway",
    )
    examples = []
    for item in _array(lesson, "worked_examples"):
        example = _object(item, f"lesson {entity_id} worked_examples")
        _keys(example, "title", "scenario", "calculation", "debit_entry", "credit_entry", "explanation")
        examples.append(
            WorkedExampleDef(
                _text(example, "title"), _text(example, "scenario"), _text(example, "calculation"),
                _text(example, "debit_entry"), _text(example, "credit_entry"), _text(example, "explanation"),
            )
        )
    result = LessonDef(
        id=entity_id,
        concept_id=_text(lesson, "concept_id"),
        title=_text(lesson, "title"),
        learning_objective=_text(lesson, "learning_objective"),
        explanation=_text(lesson, "explanation"),
        accounting_rules=_strings(lesson, "accounting_rules"),
        worked_examples=tuple(examples),
        common_mistakes=_strings(lesson, "common_mistakes") if "common_mistakes" in lesson else (),
        takeaway=_optional_text(lesson, "takeaway", ""),
    )
    _check(
        bool(result.accounting_rules) and bool(result.worked_examples),
        f"lesson {entity_id}",
        "A lesson needs key rules, a worked example and a non-null optional takeaway",
    )
    return result


def _resolve(root: dict[str, Any], sha256: str) -> CurriculumDocument:
    if _integer(root, "schema_version") != SCHEMA_VERSION:
        raise CurriculumValidationError(f"Unsupported curriculum schema_version; expected {SCHEMA_VERSION}")
    _keys(root, "schema_version", "courses", "units", "assignments", "concepts", "questions", "lessons", "labs")
    tables = {
        name: _index(root, name)
        for name in ("courses", "units", "assignments", "concepts", "questions", "lessons", "labs")
    }
    seen: set[str] = set()
    for table in tables.values():
        for entity_id in table:
            if entity_id in seen:
                raise CurriculumValidationError(f"Duplicate entity ID across tables: {entity_id}")
            seen.add(entity_id)
    if not tables["courses"]:
        raise CurriculumValidationError("Curriculum requires at least one course")

    builders: dict[str, Callable[[str, dict[str, Any]], Any]] = {
        "multiple_choice": _multiple_choice,
        "journal_entry": _journal_entry,
    }
    multiple_choice: dict[str, MultipleChoiceDef] = {}
    journal_entries: dict[str, JournalEntryDef] = {}
    for entity_id, q in tables["questions"].items():
        kind = _text(q, "type")
        if kind not in builders:
            raise CurriculumValidationError(f"question {entity_id}: unsupported type {kind}")
        question = builders[kind](entity_id, q)
        (multiple_choice if kind == "multiple_choice" else journal_entries)[entity_id] = question

    lessons = {entity_id: _lesson(entity_id, lesson) for entity_id, lesson in tables["lessons"].items()}

    used_questions: set[str] = set()
    used_lessons: set[str] = set()
    concepts: dict[str, ConceptDef] = {}
    for entity_id, c in tables["concepts"].items():
        _keys(c, "id", "assignment_id", "title", "description", "required_correct_answers", "lesson_id", "question_ids")
        assignment_id = _text(c, "assignment_id")
        bank = _references(c, "question_ids", multiple_choice, used_questions)
        required = _integer(c, "required_correct_answers")
        _check(
            required >= 1 and len(bank) >= required,
            f"concept {entity_id}",
            f"insufficient distinct question bank for {required} required correct answers",
        )
        for question_id in bank:
            _check(
                multiple_choice[question_id].concept_id == entity_id,
                f"question {question_id}", f"concept_id does not match {entity_id}",
            )
        lesson_id = None
        if c.get("lesson_id") is not None:
            lesson_id = _text(c, "lesson_id")
            if lesson_id not in lessons:
                raise CurriculumValidationError(f"concept {entity_id} lesson_id: missing reference {lesson_id}")
            _check(
                lessons[lesson_id].concept_id == entity_id and lesson_id not in used_lessons,
                f"concept {entity_id}", f"incorrect or reused lesson reference {lesson_id}",
            )
            used_lessons.add(lesson_id)
        concepts[entity_id] = ConceptDef(
            entity_id, assignment_id, _text(c, "title"), _text(c, "description"), required, lesson_id, bank
        )

    used_concepts: set[str] = set()
    assignments: dict[str, AssignmentDef] = {}
    for entity_id, a in tables["assignments"].items():
        _keys(a, "id", "parent_unit_id", "title", "description", "availability", "concept_ids")
        members = _references(a, "concept_ids", concepts, used_concepts)
        for concept_id in members:
            _check(concepts[concept_id].assignment_id == entity_id, f"concept {concept_id}", f"assignment_id does not match {entity_id}")
        availability = _text(a, "availability")
        _check(availability in AVAILABILITY, f"assignment {entity_id}", "unsupported availability")
        _check(
            (availability == "available") == bool(members),
            f"assignment {entity_id}",
            "Assignment requires uniquely identified concepts",
        )
        assignments[entity_id] = AssignmentDef(
            entity_id, _text(a, "parent_unit_id"), _text(a, "title"), _text(a, "description"), availability, members
        )

    used_assignments: set[str] = set()
    units: dict[str, UnitDef] = {}
    for entity_id, u in tables["units"].items():
        _keys(u, "id", "parent_course_id", "order", "title", "description", "assignment_ids")
        members = _references(u, "assignment_ids", assignments, used_assignments)
        _check(bool(members), f"unit {entity_id}", "assignment_ids is empty")
        unit = UnitDef(
            entity_id, _text(u, "parent_course_id"), _integer(u, "order"), _text(u, "title"), _text(u, "description"), members
        )
        _check(
            unit.number >= 1 and all(assignments[item].unit_id == entity_id for item in members),
            f"unit {entity_id}", "Invalid unit or assignment parent links",
        )
        units[entity_id] = unit

    used_units: set[str] = set()
    courses: dict[str, CourseDef] = {}
    for entity_id, c in tables["courses"].items():
        _keys(c, "id", "title", "description", "unit_ids")
        members = _references(c, "unit_ids", units, used_units)
        _check(bool(members), f"course {entity_id}", "unit_ids is empty")
        _check(all(units[item].course_id == entity_id for item in members), f"course {entity_id}", "Invalid course or unit parent links")
        numbers = [units[item].number for item in members]
        _check(all(a < b for a, b in zip(numbers, numbers[1:])), f"course {entity_id}", "Units must be in increasing number order")
        courses[entity_id] = CourseDef(entity_id, _text(c, "title"), _text(c, "description"), members)

    labs: dict[str, LabDef] = {}
    for entity_id, lab in tables["labs"].items():
        _keys(lab, "id", "title", "question_ids")
        bank = _references(lab, "question_ids", journal_entries, used_questions)
        _check(bool(bank), f"lab {entity_id}", "question_ids is empty")
        for question_id in bank:
            _check(journal_entries[question_id].lab_id == entity_id, f"question {question_id}", "lab_id mismatch")
        labs[entity_id] = LabDef(entity_id, _text(lab, "title"), bank)

    _all_used("unit", tables["units"], used_units)
    _all_used("assignment", tables["assignments"], used_assignments)
    _all_used("concept", tables["concepts"], used_concepts)
    _all_used("question", tables["questions"], used_questions)
    _all_used("lesson", tables["lessons"], used_lessons)
    return CurriculumDocument(
        SCHEMA_VERSION, sha256, courses, units, assignments, concepts, multiple_choice, journal_entries, lessons, labs
    )
