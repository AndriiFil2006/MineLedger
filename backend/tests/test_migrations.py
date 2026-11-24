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
HEAD_TABLES = CURRICULUM_TABLES | IDENTITY_TABLES | {"alembic_version"}


def test_head_creates_exactly_the_curriculum_and_identity_tables(engine):
    tables = set(inspect(engine).get_table_names())
    assert tables == HEAD_TABLES
    assert not any(word in name for name in tables for word in ("learner", "progress", "attempt", "xp", "password", "token"))


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
        assert connection.execute(text("SELECT version_num FROM alembic_version")).scalar_one() == "0002"


def test_answer_key_must_be_one_of_the_questions_own_options(imported):
    """Deferred composite FK: a stored key pointing at another question's option fails at commit."""
    imported.execute(text("UPDATE questions SET correct_option_id = 'books.inventory_payable' WHERE id = 'inventory.shovels'"))
    with pytest.raises(IntegrityError, match="fk_questions_correct_option"):
        imported.commit()
    imported.rollback()
