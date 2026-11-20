"""Idempotent, transactional import of a validated curriculum file into PostgreSQL.

Rules:
- Rows are matched by their stable text IDs; repeated imports of the same file change nothing.
- Content edits for existing IDs update rows in place; new IDs are inserted.
- An import may only touch rows owned by its own bundle. An ID owned by another bundle (or by future
  non-curated content) aborts the import instead of being overwritten.
- Nothing is deleted silently. If the file no longer contains an entity or answer option that this bundle
  previously imported, the import aborts: retiring IDs needs an explicit learner-progress migration
  policy (see minecraft-mod/CURRICULUM_ARCHITECTURE.md). Only ID-less owned details (worked examples,
  journal account choice lists) are replaced with the file's version.
- Everything happens in one transaction; any validation, conflict or database error writes nothing.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from typing import Any

from sqlalchemy import func, select, text
from sqlalchemy.orm import Session

from .curriculum import CurriculumDocument
from .models import (
    AnswerOption,
    Assignment,
    Base,
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

_IMPORT_LOCK_KEY = 0x4D4C_4355  # "MLCU": serializes concurrent curriculum imports


class CurriculumImportConflict(RuntimeError):
    """The file conflicts with existing database records; nothing was written."""


@dataclass
class ImportReport:
    bundle_id: str
    content_sha256: str
    dry_run: bool
    created: Counter[str] = field(default_factory=Counter)
    updated: Counter[str] = field(default_factory=Counter)
    unchanged: Counter[str] = field(default_factory=Counter)
    replaced: Counter[str] = field(default_factory=Counter)
    answer_key_changes: list[str] = field(default_factory=list)

    @property
    def changed(self) -> bool:
        return bool(sum(self.created.values()) + sum(self.updated.values()) + sum(self.replaced.values()))

    def summary_lines(self) -> list[str]:
        kinds = sorted(set(self.created) | set(self.updated) | set(self.unchanged) | set(self.replaced))
        lines = [f"{'entity':<24}{'created':>9}{'updated':>9}{'unchanged':>11}"]
        for kind in kinds:
            lines.append(f"{kind:<24}{self.created[kind]:>9}{self.updated[kind]:>9}{self.unchanged[kind]:>11}")
        for kind, count in sorted(self.replaced.items()):
            lines.append(f"replaced {count} {kind} row(s) with the file's version")
        for question_id in self.answer_key_changes:
            lines.append(f"WARNING: answer key changed for question {question_id}")
        return lines


def import_curriculum(
    session: Session, document: CurriculumDocument, bundle_id: str, source: str, *, dry_run: bool = False
) -> ImportReport:
    """Import `document` as `bundle_id`. The session must not already be in a transaction."""
    if session.in_transaction():
        raise RuntimeError("import_curriculum owns its transaction; commit or roll back the session first")
    report = ImportReport(bundle_id, document.sha256, dry_run)
    transaction = session.begin()
    try:
        _acquire_import_lock(session)
        _Import(session, document, bundle_id, source, report).run()
        if dry_run:
            _check_deferred_constraints(session)
            transaction.rollback()
        else:
            transaction.commit()
    except BaseException:
        transaction.rollback()
        raise
    return report


def _acquire_import_lock(session: Session) -> None:
    """Transaction-scoped: a concurrent import waits here until the first one commits or rolls back."""
    session.execute(select(func.pg_advisory_xact_lock(_IMPORT_LOCK_KEY)))


def _check_deferred_constraints(session: Session) -> None:
    """Deferred constraints normally run at COMMIT; a dry run never commits, so check them now."""
    session.execute(text("SET CONSTRAINTS ALL IMMEDIATE"))


class _Import:
    def __init__(self, session: Session, document: CurriculumDocument, bundle_id: str, source: str, report: ImportReport):
        self.session = session
        self.doc = document
        self.bundle_id = bundle_id
        self.source = source
        self.report = report

    # -------------------------------------------------------------- desired rows from the file

    def _rows(self) -> dict[type[Base], dict[Any, dict[str, Any]]]:
        doc, owner = self.doc, {"bundle_id": self.bundle_id}
        courses, units, assignments, concepts, lessons, labs, questions = ({} for _ in range(7))
        options: dict[Any, dict[str, Any]] = {}
        for position, course in enumerate(doc.courses.values()):
            courses[course.id] = owner | {"sort_order": position, "title": course.title, "description": course.description}
            for unit_position, unit_id in enumerate(course.unit_ids):
                unit = doc.units[unit_id]
                units[unit_id] = owner | {
                    "course_id": course.id, "unit_number": unit.number, "sort_order": unit_position,
                    "title": unit.title, "description": unit.description,
                }
                for assignment_position, assignment_id in enumerate(unit.assignment_ids):
                    assignment = doc.assignments[assignment_id]
                    assignments[assignment_id] = owner | {
                        "unit_id": unit_id, "sort_order": assignment_position, "title": assignment.title,
                        "description": assignment.description, "availability": assignment.availability,
                    }
                    for concept_position, concept_id in enumerate(assignment.concept_ids):
                        concept = doc.concepts[concept_id]
                        concepts[concept_id] = owner | {
                            "assignment_id": assignment_id, "sort_order": concept_position, "title": concept.title,
                            "description": concept.description,
                            "required_correct_answers": concept.required_correct_answers,
                        }
                        for question_position, question_id in enumerate(concept.question_ids):
                            question = doc.multiple_choice[question_id]
                            questions[question_id] = owner | {
                                "question_type": "multiple_choice", "concept_id": concept_id, "lab_id": None,
                                "sort_order": question_position, "prompt": question.prompt,
                                "explanation": question.explanation, "difficulty": question.difficulty,
                                "correct_option_id": question.correct_option_id, "correct_debit_account": None,
                                "correct_credit_account": None, "correct_amount": None,
                            }
                            for option_position, option in enumerate(question.options):
                                options[(question_id, option.id)] = {"sort_order": option_position, "text": option.text}
        for position, lab in enumerate(doc.labs.values()):
            labs[lab.id] = owner | {"sort_order": position, "title": lab.title}
            for question_position, question_id in enumerate(lab.question_ids):
                question = doc.journal_entries[question_id]
                questions[question_id] = owner | {
                    "question_type": "journal_entry", "concept_id": None, "lab_id": lab.id,
                    "sort_order": question_position, "prompt": question.prompt, "explanation": question.explanation,
                    "difficulty": question.difficulty, "correct_option_id": None,
                    "correct_debit_account": question.correct_debit_account,
                    "correct_credit_account": question.correct_credit_account,
                    "correct_amount": question.correct_amount,
                }
        for lesson in doc.lessons.values():
            lessons[lesson.id] = owner | {
                "concept_id": lesson.concept_id, "title": lesson.title, "learning_objective": lesson.learning_objective,
                "explanation": lesson.explanation, "accounting_rules": list(lesson.accounting_rules),
                "common_mistakes": list(lesson.common_mistakes), "takeaway": lesson.takeaway,
            }
        # Parents before children so foreign keys resolve at each flush.
        return {
            Course: courses, Unit: units, Assignment: assignments, Concept: concepts, JournalLab: labs,
            ConceptLesson: lessons, Question: questions, AnswerOption: options,
        }

    def _owned_details(self) -> tuple[dict[Any, dict[str, Any]], dict[Any, dict[str, Any]]]:
        choices: dict[Any, dict[str, Any]] = {}
        for question in self.doc.journal_entries.values():
            for side, accounts in (("debit", question.debit_accounts), ("credit", question.credit_accounts)):
                for position, account in enumerate(accounts):
                    choices[(question.id, side, account)] = {"sort_order": position}
        examples: dict[Any, dict[str, Any]] = {}
        for lesson in self.doc.lessons.values():
            for position, example in enumerate(lesson.worked_examples):
                examples[(lesson.id, position)] = {
                    "title": example.title, "scenario": example.scenario, "calculation": example.calculation,
                    "debit_entry": example.debit_entry, "credit_entry": example.credit_entry,
                    "explanation": example.explanation,
                }
        return choices, examples

    # -------------------------------------------------------------- import steps

    def run(self) -> None:
        rows = self._rows()
        self._check_conflicts(rows)
        self._sync_bundle()
        for model, desired in rows.items():
            if model is Question:
                self._record_answer_key_changes(desired)
            self._sync(model, desired)
            self.session.flush()
        choices, examples = self._owned_details()
        self._replace_details(JournalAccountChoice, JournalAccountChoice.question_id, set(self.doc.journal_entries), choices)
        self._replace_details(LessonWorkedExample, LessonWorkedExample.lesson_id, set(self.doc.lessons), examples)
        self.session.flush()

    def _check_conflicts(self, rows: dict[type[Base], dict[Any, dict[str, Any]]]) -> None:
        problems: list[str] = []
        for model, desired in rows.items():
            if model is AnswerOption:
                continue
            table = model.__tablename__
            owners = self.session.execute(select(model.id, model.bundle_id).where(model.id.in_(list(desired)))).all()
            for entity_id, owner in owners:
                if owner != self.bundle_id:
                    problems.append(f"{table} {entity_id!r} belongs to bundle {owner!r}; it will not be overwritten")
            missing = self.session.scalars(
                select(model.id).where(model.bundle_id == self.bundle_id, model.id.not_in(list(desired)))
            ).all()
            problems.extend(f"{table} {entity_id!r} was imported before but is missing from the file" for entity_id in sorted(missing))
        stored_types = self.session.execute(
            select(Question.id, Question.question_type).where(Question.id.in_(list(rows[Question])))
        ).all()
        for question_id, question_type in stored_types:
            if question_type != rows[Question][question_id]["question_type"]:
                problems.append(f"question {question_id!r} cannot change type from {question_type!r}; use a new ID")
        own_questions = select(Question.id).where(Question.bundle_id == self.bundle_id)
        existing_options = self.session.execute(
            select(AnswerOption.question_id, AnswerOption.option_id).where(AnswerOption.question_id.in_(own_questions))
        ).all()
        for key in existing_options:
            if tuple(key) not in rows[AnswerOption]:
                problems.append(f"answer option {key[1]!r} of question {key[0]!r} was imported before but is missing from the file")
        if problems:
            raise CurriculumImportConflict(
                "Import aborted; nothing was written. Removing or reassigning stored curriculum IDs needs an explicit "
                "migration:\n  - " + "\n  - ".join(problems)
            )

    def _sync_bundle(self) -> None:
        bundle = self.session.get(CurriculumBundle, self.bundle_id)
        values = {"schema_version": self.doc.schema_version, "content_sha256": self.doc.sha256, "source": self.source}
        if bundle is None:
            self.session.add(CurriculumBundle(id=self.bundle_id, **values))
            self.report.created["curriculum_bundles"] += 1
        elif bundle.content_sha256 != self.doc.sha256 or bundle.schema_version != self.doc.schema_version:
            for name, value in values.items():
                setattr(bundle, name, value)
            bundle.imported_at = func.now()
            self.report.updated["curriculum_bundles"] += 1
        else:
            self.report.unchanged["curriculum_bundles"] += 1
        self.session.flush()

    def _record_answer_key_changes(self, desired: dict[Any, dict[str, Any]]) -> None:
        stored = self.session.execute(
            select(Question.id, Question.correct_option_id, Question.correct_debit_account,
                   Question.correct_credit_account, Question.correct_amount).where(Question.id.in_(list(desired)))
        ).all()
        for question_id, option, debit, credit, amount in stored:
            new = desired[question_id]
            if (option, debit, credit, amount) != (
                new["correct_option_id"], new["correct_debit_account"], new["correct_credit_account"], new["correct_amount"]
            ):
                self.report.answer_key_changes.append(question_id)

    def _sync(self, model: type[Base], desired: dict[Any, dict[str, Any]]) -> None:
        table = model.__tablename__
        key_names = [column.key for column in model.__mapper__.primary_key]
        for key, values in desired.items():
            key_tuple = key if isinstance(key, tuple) else (key,)
            row = self.session.get(model, key_tuple if len(key_tuple) > 1 else key_tuple[0])
            if row is None:
                self.session.add(model(**dict(zip(key_names, key_tuple)), **values))
                self.report.created[table] += 1
                continue
            changes = {name: value for name, value in values.items() if getattr(row, name) != value}
            for name, value in changes.items():
                setattr(row, name, value)
            (self.report.updated if changes else self.report.unchanged)[table] += 1

    def _replace_details(self, model: type[Base], parent_column: Any, parent_ids: set[str], desired: dict[Any, dict[str, Any]]) -> None:
        """Owned, ID-less details: rewrite them when (and only when) they differ from the file."""
        table = model.__tablename__
        key_names = [column.key for column in model.__mapper__.primary_key]
        value_names = sorted({name for values in desired.values() for name in values})
        existing = {
            tuple(getattr(row, name) for name in key_names): row
            for row in self.session.scalars(select(model).where(parent_column.in_(list(parent_ids))))
        }
        current = {key: {name: getattr(row, name) for name in value_names} for key, row in existing.items()}
        if current == desired:
            self.report.unchanged[table] += len(desired)
            return
        for row in existing.values():
            self.session.delete(row)
        self.session.flush()
        for key, values in desired.items():
            self.session.add(model(**dict(zip(key_names, key)), **values))
        self.report.replaced[table] += len(desired)
        if not existing:
            self.report.replaced.pop(table)
            self.report.created[table] += len(desired)
