"""Engine/session lifecycle. Endpoints receive a request-scoped session through `get_session`."""

from __future__ import annotations

from collections.abc import Iterator
from functools import lru_cache

from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import Session, sessionmaker

from .config import get_settings


def make_engine(url: str) -> Engine:
    return create_engine(url, pool_pre_ping=True)


@lru_cache(maxsize=1)
def _default_sessionmaker() -> sessionmaker[Session]:
    return sessionmaker(bind=make_engine(get_settings().database_url), expire_on_commit=False)


def get_session() -> Iterator[Session]:
    with _default_sessionmaker()() as session:
        yield session
