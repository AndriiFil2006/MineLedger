"""Validator parity with the Java CurriculumLoader (no database needed).

The broken/edge-case files come from ../shared/fixtures/curriculum_validation_cases.json, which the Java
SharedCurriculumValidationTest also runs, so both implementations must accept and reject the same files.
"""

from __future__ import annotations

import base64
import copy
import json
import re

import pytest

from mineledger_api.config import REPOSITORY_ROOT
from mineledger_api.curriculum import ACCOUNTS, CurriculumValidationError, parse_bytes

ACCOUNT_ENUM = REPOSITORY_ROOT / "minecraft-mod" / "src" / "main" / "java" / "com" / "mineledger" / "accounting" / "Account.java"
CASES_FILE = REPOSITORY_ROOT / "shared" / "fixtures" / "curriculum_validation_cases.json"
FIXTURE = json.loads(CASES_FILE.read_text(encoding="utf-8"))


# ---------------------------------------------------------------- fixture edit operations


def _step(container, step):
    if isinstance(step, dict):
        return next(index for index, item in enumerate(container) if item.get("id") == step["id"])
    return step


def _resolve(data, path):
    for step in path:
        data = data[_step(data, step)]
    return data


def apply_ops(data: dict, ops: list[dict]) -> dict:
    for op in ops:
        kind, path = op["op"], op["path"]
        if kind in ("append", "remove_value"):
            target = _resolve(data, path)
            target.append(op["value"]) if kind == "append" else target.remove(op["value"])
            continue
        parent = _resolve(data, path[:-1])
        key = _step(parent, path[-1])
        if kind == "set":
            parent[key] = op["value"]
        elif kind == "delete":
            del parent[key]
        else:
            raise ValueError(f"unknown fixture op {kind}")
    return data


def case_bytes(case: dict, base: dict) -> bytes:
    if case["kind"] == "raw":
        return base64.b64decode(case["base64"]) if "base64" in case else case["text"].encode("utf-8")
    return json.dumps(apply_ops(copy.deepcopy(base), case["ops"]), ensure_ascii=False).encode("utf-8")


# ---------------------------------------------------------------- tests


def test_bundled_curriculum_counts(bundled_document):
    doc = bundled_document
    assert (len(doc.courses), len(doc.units), len(doc.assignments), len(doc.concepts)) == (1, 3, 8, 2)
    assert (len(doc.multiple_choice), len(doc.journal_entries), len(doc.lessons), len(doc.labs)) == (6, 3, 1, 1)
    assert sum(len(q.options) for q in doc.multiple_choice.values()) == 24
    assert [a.id for a in doc.assignments.values() if a.availability == "available"] == ["inventory_fundamentals"]
    assert doc.concepts["inventory.purchases_on_credit"].lesson_id == "lesson.inventory.purchases_on_credit.perpetual"


def test_supported_accounts_match_the_java_enum():
    source = ACCOUNT_ENUM.read_text(encoding="utf-8")
    body = re.search(r"enum\s+Account\s*\{([^}]*)\}", source).group(1)
    assert tuple(name.strip() for name in body.split(",") if name.strip()) == ACCOUNTS


def test_fixture_is_well_formed():
    assert FIXTURE["fixture_version"] == 1
    names = [case["name"] for case in FIXTURE["cases"]]
    assert len(names) == len(set(names))
    assert {case["expect"] for case in FIXTURE["cases"]} == {"valid", "invalid"}
    assert all(case["expect"] == "valid" or case.get("python_message") for case in FIXTURE["cases"])


@pytest.mark.parametrize("case", FIXTURE["cases"], ids=[case["name"] for case in FIXTURE["cases"]])
def test_shared_validation_case(case, bundled_json):
    data = case_bytes(case, bundled_json)
    if case["expect"] == "valid":
        parse_bytes(data)
        return
    with pytest.raises(CurriculumValidationError, match=re.escape(case["python_message"])):
        parse_bytes(data)


def test_edits_really_change_the_file(bundled_json):
    """Guards the applier: every edit case must produce a document different from the original."""
    original = case_bytes({"kind": "edit", "ops": []}, bundled_json)
    for case in FIXTURE["cases"]:
        if case["kind"] == "edit" and case["ops"]:
            assert case_bytes(case, bundled_json) != original, case["name"]
