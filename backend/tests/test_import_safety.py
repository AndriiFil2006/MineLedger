"""Concurrency and dry-run guarantees of the importer.

Each guarantee has a control test that disables the protection and shows the failure it prevents,
so these tests cannot pass vacuously.
"""

from __future__ import annotations

import threading
import time

import pytest
from sqlalchemy import Engine, func, select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from mineledger_api import importer
from mineledger_api.importer import ImportReport, _Import, import_curriculum
from mineledger_api.models import CurriculumBundle, Question

TIMEOUT = 20


def wait_until(predicate, timeout: float = TIMEOUT) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.05)
    return False


def waiting_locks(engine: Engine, locktype: str | None = None) -> int:
    """How many backends are currently blocked waiting for a lock (optionally of one type)."""
    sql = "SELECT count(*) FROM pg_locks WHERE NOT granted" + (" AND locktype = :t" if locktype else "")
    with engine.connect() as connection:
        return connection.execute(text(sql), {"t": locktype}).scalar_one()


def count(engine: Engine, model) -> int:
    with Session(engine) as session:
        return session.scalar(select(func.count()).select_from(model))


@pytest.fixture
def paused_first_import(monkeypatch):
    """The thread named 'first' finishes all writes, then waits (still inside its transaction) until released."""
    original = _Import.run
    inside, release = threading.Event(), threading.Event()

    def run(self):
        original(self)
        if threading.current_thread().name == "first":
            inside.set()
            if not release.wait(TIMEOUT):
                raise TimeoutError("test never released the first import")

    monkeypatch.setattr(_Import, "run", run)
    yield inside, release
    release.set()  # never leave a thread hanging if an assertion failed


def start_import(engine: Engine, document, name: str, results: dict) -> threading.Thread:
    def target():
        try:
            with Session(engine) as session:
                results[name] = import_curriculum(session, document, "accounting_101", name)
        except Exception as error:  # captured for the assertions on the main thread
            results[name] = error

    thread = threading.Thread(target=target, name=name, daemon=True)
    thread.start()
    return thread


def test_concurrent_imports_are_serialized(clean_db, bundled_document, paused_first_import):
    inside, release = paused_first_import
    results: dict = {}
    first = start_import(clean_db, bundled_document, "first", results)
    assert inside.wait(TIMEOUT), "first import never reached its commit point"

    second = start_import(clean_db, bundled_document, "second", results)
    # PostgreSQL itself reports the second import blocked on the advisory lock the first one holds.
    assert wait_until(lambda: waiting_locks(clean_db, "advisory") == 1)
    time.sleep(0.3)
    assert second.is_alive() and "second" not in results  # still waiting, has written nothing

    release.set()
    first.join(TIMEOUT)
    second.join(TIMEOUT)
    assert isinstance(results["first"], ImportReport) and results["first"].created["questions"] == 9
    # The second import ran only after the first committed, so it found everything already present.
    assert isinstance(results["second"], ImportReport) and not results["second"].changed
    assert count(clean_db, CurriculumBundle) == 1 and count(clean_db, Question) == 9


def test_control_without_the_lock_concurrent_imports_collide(clean_db, bundled_document, paused_first_import, monkeypatch):
    """Proves the lock is what makes the test above pass: without it, the second import fails."""
    monkeypatch.setattr(importer, "_acquire_import_lock", lambda session: None)
    inside, release = paused_first_import
    results: dict = {}
    first = start_import(clean_db, bundled_document, "first", results)
    assert inside.wait(TIMEOUT)

    second = start_import(clean_db, bundled_document, "second", results)
    # Nothing serializes them now: the second import inserts the same primary keys and blocks on row locks.
    assert wait_until(lambda: waiting_locks(clean_db) >= 1)
    assert waiting_locks(clean_db, "advisory") == 0

    release.set()
    first.join(TIMEOUT)
    second.join(TIMEOUT)
    assert isinstance(results["first"], ImportReport)
    assert isinstance(results["second"], IntegrityError) and "duplicate key" in str(results["second"])
    assert count(clean_db, Question) == 9  # the failed import was rolled back in full


@pytest.fixture
def foreign_answer_key(monkeypatch):
    """Simulates an importer bug that bypasses validation: an answer key pointing at another question's option.

    Only the deferred composite foreign key can catch this, and deferred constraints normally run at COMMIT.
    """
    original = _Import._rows

    def rows(self):
        result = original(self)
        result[Question]["inventory.shovels"]["correct_option_id"] = "books.inventory_payable"
        return result

    monkeypatch.setattr(_Import, "_rows", rows)


@pytest.mark.usefixtures("foreign_answer_key")
def test_dry_run_catches_deferred_constraint_violations(session, bundled_document):
    with pytest.raises(IntegrityError, match="fk_questions_correct_option"):
        import_curriculum(session, bundled_document, "accounting_101", "test", dry_run=True)
    session.rollback()
    assert session.scalar(select(func.count()).select_from(Question)) == 0


@pytest.mark.usefixtures("foreign_answer_key")
def test_real_import_rejects_the_same_violation_at_commit(session, bundled_document):
    with pytest.raises(IntegrityError, match="fk_questions_correct_option"):
        import_curriculum(session, bundled_document, "accounting_101", "test")
    session.rollback()
    assert session.scalar(select(func.count()).select_from(CurriculumBundle)) == 0


@pytest.mark.usefixtures("foreign_answer_key")
def test_control_without_the_check_dry_run_would_wrongly_succeed(session, bundled_document, monkeypatch):
    """Proves `_check_deferred_constraints` is what catches it: without it the dry run reports success."""
    monkeypatch.setattr(importer, "_check_deferred_constraints", lambda session: None)
    report = import_curriculum(session, bundled_document, "accounting_101", "test", dry_run=True)
    assert report.created["questions"] == 9  # a false "all clear" for a file a real import would reject
