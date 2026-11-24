from __future__ import annotations

import pytest
from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.runtime.migration import MigrationContext
from sqlalchemy import inspect, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from mineledger_api.importer import import_curriculum
from mineledger_api.models import Base
from tests.conftest import alembic_config
from tests.test_importer import snapshot

CURRICULUM_TABLES = {
    "curriculum_bundles", "courses", "units", "assignments", "concepts", "questions", "answer_options",
    "journal_labs", "journal_account_choices", "concept_lessons", "lesson_worked_examples",
}


IDENTITY_TABLES = {"users"}
PROGRESS_TABLES = {"question_attempts", "question_credits", "concept_completions"}
HEAD_TABLES = CURRICULUM_TABLES | IDENTITY_TABLES | PROGRESS_TABLES | {"alembic_version"}


def test_head_creates_exactly_the_curriculum_and_identity_tables(engine):
    tables = set(inspect(engine).get_table_names())
    assert tables == HEAD_TABLES
    assert not any(word in name for name in tables for word in ("password", "token", "secret", "session"))


def test_users_table_stores_no_credentials(engine):
    columns = {column["name"] for column in inspect(engine).get_columns("users")}
    assert columns == {"id", "issuer", "subject", "role", "is_active", "created_at", "role_changed_at"}


def test_users_migration_keeps_every_curriculum_row(engine, clean_db, database_url, bundled_document):
    """0001 -> 0002 -> 0001 -> 0002 with imported curriculum: every existing row survives each step."""
    config = alembic_config(database_url)
    try:
        command.downgrade(config, "0001")
        assert set(inspect(engine).get_table_names()) == CURRICULUM_TABLES | {"alembic_version"}
        with Session(engine) as session:
            import_curriculum(session, bundled_document, "accounting_101", "test")
            before = snapshot(session)
        assert before["questions"] and before["courses"]

        for revision in ("0002", "0001", "head"):
            if revision == "0001":
                command.downgrade(config, revision)
            else:
                command.upgrade(config, revision)
            with Session(engine) as session:
                assert snapshot(session) == before, revision
                assert session.execute(text("SELECT imported_at FROM curriculum_bundles")).scalar_one() is not None
    finally:
        command.upgrade(config, "head")
    assert "users" in inspect(engine).get_table_names()


def test_migrations_match_the_models(engine):
    with engine.connect() as connection:
        diff = compare_metadata(MigrationContext.configure(connection, opts={"compare_type": True}), Base.metadata)
    assert diff == []


def test_downgrade_to_base_and_upgrade_again(engine, database_url):
    config = alembic_config(database_url)
    try:
        command.downgrade(config, "base")
        assert set(inspect(engine).get_table_names()) <= {"alembic_version"}
    finally:
        command.upgrade(config, "head")
    assert set(inspect(engine).get_table_names()) == HEAD_TABLES
    with engine.connect() as connection:
        assert connection.execute(text("SELECT version_num FROM alembic_version")).scalar_one() == "0003"


def test_answer_key_must_be_one_of_the_questions_own_options(imported):
    """Deferred composite FK: a stored key pointing at another question's option fails at commit."""
    imported.execute(text("UPDATE questions SET correct_option_id = 'books.inventory_payable' WHERE id = 'inventory.shovels'"))
    with pytest.raises(IntegrityError, match="fk_questions_correct_option"):
        imported.commit()
    imported.rollback()


def test_progress_migration_keeps_users_and_curriculum(engine, clean_db, database_url, bundled_document):
    """0002 -> 0003 -> 0002 -> 0003 with a user and curriculum present: both survive every step."""
    config = alembic_config(database_url)
    try:
        command.downgrade(config, "0002")
        assert set(inspect(engine).get_table_names()) == CURRICULUM_TABLES | IDENTITY_TABLES | {"alembic_version"}
        with Session(engine) as session:
            import_curriculum(session, bundled_document, "accounting_101", "test")
            session.execute(text(
                "INSERT INTO users (id, issuer, subject, role) "
                "VALUES ('6f1d2c3b-4a59-4e7f-8a1b-2c3d4e5f6a7b', 'https://idp.test', 'alex', 'teacher')"
            ))
            session.commit()
            before = snapshot(session)
            users = session.execute(text("SELECT id, issuer, subject, role, is_active, created_at FROM users")).all()

        for revision in ("0003", "0002", "head"):
            if revision == "0002":
                command.downgrade(config, revision)
            else:
                command.upgrade(config, revision)
            with Session(engine) as session:
                assert snapshot(session) == before, revision
                assert session.execute(text("SELECT id, issuer, subject, role, is_active, created_at FROM users")).all() == users
    finally:
        command.upgrade(config, "head")
    assert PROGRESS_TABLES <= set(inspect(engine).get_table_names())


def test_progress_tables_carry_their_integrity_constraints(engine):
    """The guarantees live in the schema, not only in application code."""
    inspector = inspect(engine)
    assert inspector.get_pk_constraint("question_credits")["constrained_columns"] == ["user_id", "question_id"]
    assert inspector.get_pk_constraint("concept_completions")["constrained_columns"] == ["user_id", "concept_id"]
    uniques = {u["name"]: u["column_names"] for t in PROGRESS_TABLES for u in inspector.get_unique_constraints(t)}
    assert uniques == {
        "uq_question_attempts_user_idempotency_key": ["user_id", "idempotency_key"],
        "uq_question_credits_attempt_id": ["attempt_id"],
        "uq_concept_completions_attempt_id": ["attempt_id"],
    }
    checks = {c["name"] for t in PROGRESS_TABLES for c in inspector.get_check_constraints(t)}
    assert checks == {
        "ck_question_attempts_mode", "ck_question_attempts_status", "ck_question_attempts_credit_needs_graded_correct",
        "ck_question_attempts_completion_needs_credit", "ck_question_attempts_xp_needs_completion",
        "ck_concept_completions_non_negative_xp",
    }
    for table in PROGRESS_TABLES:
        foreign = {(fk["constrained_columns"][0], fk["referred_table"]) for fk in inspector.get_foreign_keys(table)}
        assert ("user_id", "users") in foreign, table
        assert all(not column["nullable"] or column["name"] == "selected_option_id" for column in inspector.get_columns(table)), table
