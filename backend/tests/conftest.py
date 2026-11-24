"""Test fixtures. Database tests run against MINELEDGER_TEST_DATABASE_URL (never the development database)."""

from __future__ import annotations

import json
import os
from collections.abc import Iterator
from pathlib import Path

# Tests must not depend on the developer's own settings: no backend/.env, and none of the variables a
# real sign-in setup leaves in the environment. This runs before the application is imported.
os.environ["MINELEDGER_ENV_FILE"] = ""
for _name in [name for name in os.environ if name.startswith("MINELEDGER_AUTH_") or name == "MINELEDGER_ENV"]:
    del os.environ[_name]

import pytest  # noqa: E402
from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from sqlalchemy import Engine, inspect, make_url, text
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import Session, sessionmaker

from mineledger_api.config import BUNDLED_CURRICULUM, DEFAULT_TEST_DATABASE_URL, REPOSITORY_ROOT
from mineledger_api.curriculum import load_file
from mineledger_api.db import get_session, make_engine
from mineledger_api.importer import import_curriculum
from mineledger_api.main import create_app

BACKEND_ROOT = Path(__file__).resolve().parents[1]
GRADING_FIXTURE = REPOSITORY_ROOT / "shared" / "fixtures" / "multiple_choice_grading.json"
TEST_DATABASE_URL = os.environ.get("MINELEDGER_TEST_DATABASE_URL", DEFAULT_TEST_DATABASE_URL)


def alembic_config(url: str) -> Config:
    config = Config(str(BACKEND_ROOT / "alembic.ini"))
    config.attributes["database_url"] = url
    config.attributes["configure_logging"] = False
    return config


@pytest.fixture(scope="session")
def database_url() -> str:
    name = make_url(TEST_DATABASE_URL).database or ""
    if not name.endswith("_test"):
        pytest.exit(f"Refusing to run destructive tests against {name!r}: the test database name must end in _test")
    return TEST_DATABASE_URL


@pytest.fixture(scope="session")
def engine(database_url: str) -> Iterator[Engine]:
    engine = make_engine(database_url)
    try:
        with engine.connect():
            pass
    except OperationalError as error:
        pytest.fail(
            f"PostgreSQL test database is unavailable at {make_url(database_url).render_as_string(hide_password=True)}. "
            f"Start it with `docker compose up -d --wait` in backend/. ({error.orig})",
            pytrace=False,
        )
    config = alembic_config(database_url)
    command.downgrade(config, "base")
    command.upgrade(config, "head")
    yield engine
    engine.dispose()


@pytest.fixture
def clean_db(engine: Engine) -> Engine:
    """Empty every curriculum table before a test (the schema stays at Alembic head)."""
    tables = [name for name in inspect(engine).get_table_names() if name != "alembic_version"]
    with engine.begin() as connection:
        connection.execute(text(f"TRUNCATE {', '.join(tables)} RESTART IDENTITY CASCADE"))
    return engine


@pytest.fixture
def session(clean_db: Engine) -> Iterator[Session]:
    with Session(clean_db) as session:
        yield session


@pytest.fixture(scope="session")
def bundled_document():
    return load_file(BUNDLED_CURRICULUM)


@pytest.fixture(scope="session")
def bundled_json() -> dict:
    return json.loads(BUNDLED_CURRICULUM.read_text(encoding="utf-8"))


@pytest.fixture
def imported(session: Session, bundled_document) -> Session:
    import_curriculum(session, bundled_document, "accounting_101", str(BUNDLED_CURRICULUM))
    return session


@pytest.fixture
def client(imported: Session, clean_db: Engine) -> Iterator[TestClient]:
    factory = sessionmaker(bind=clean_db)

    def override() -> Iterator[Session]:
        with factory() as request_session:
            yield request_session

    app = create_app()
    app.dependency_overrides[get_session] = override
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture(scope="session")
def grading_fixture() -> dict:
    return json.loads(GRADING_FIXTURE.read_text(encoding="utf-8"))
