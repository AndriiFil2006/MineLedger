from __future__ import annotations

import pytest
from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.runtime.migration import MigrationContext
from sqlalchemy import inspect, text
from sqlalchemy.exc import IntegrityError

from mineledger_api.models import Base
from tests.conftest import alembic_config

CURRICULUM_TABLES = {
    "curriculum_bundles", "courses", "units", "assignments", "concepts", "questions", "answer_options",
    "journal_labs", "journal_account_choices", "concept_lessons", "lesson_worked_examples",
}


def test_head_creates_exactly_the_curriculum_tables(engine):
    tables = set(inspect(engine).get_table_names())
    assert tables == CURRICULUM_TABLES | {"alembic_version"}
    assert not any(word in name for name in tables for word in ("learner", "progress", "user", "auth", "xp"))


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
    assert set(inspect(engine).get_table_names()) == CURRICULUM_TABLES | {"alembic_version"}
    with engine.connect() as connection:
        assert connection.execute(text("SELECT version_num FROM alembic_version")).scalar_one() == "0001"


def test_answer_key_must_be_one_of_the_questions_own_options(imported):
    """Deferred composite FK: a stored key pointing at another question's option fails at commit."""
    imported.execute(text("UPDATE questions SET correct_option_id = 'books.inventory_payable' WHERE id = 'inventory.shovels'"))
    with pytest.raises(IntegrityError, match="fk_questions_correct_option"):
        imported.commit()
    imported.rollback()
