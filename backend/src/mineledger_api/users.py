"""Server-side user records: first-sign-in provisioning and operator-only role and status changes.

Nothing here is reachable over HTTP except `get_or_create_user`, which only ever creates a student.
`set_role` and `set_active` are called by the operator CLI alone.
"""

from __future__ import annotations

import uuid

from sqlalchemy import func, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.orm import Session

from .models import ROLES, User


class UserNotFound(LookupError):
    pass


def get_or_create_user(session: Session, issuer: str, subject: str) -> User:
    """The user for a verified identity. A first sign-in creates a student; a role never comes from here."""
    query = select(User).where(User.issuer == issuer, User.subject == subject)
    user = session.scalars(query).one_or_none()
    if user is None:
        # Two first requests can race; the unique constraint keeps one row and both read it back.
        session.execute(
            insert(User)
            .values(id=uuid.uuid4(), issuer=issuer, subject=subject)
            .on_conflict_do_nothing(constraint="uq_users_issuer_subject")
        )
        session.commit()
        user = session.scalars(query).one()
    return user


def _require(session: Session, user_id: uuid.UUID) -> User:
    user = session.get(User, user_id)
    if user is None:
        raise UserNotFound(f"No user with ID {user_id}")
    return user


def set_role(session: Session, user_id: uuid.UUID, role: str) -> User:
    if role not in ROLES:
        raise ValueError(f"Unknown role: {role!r}")
    user = _require(session, user_id)
    if user.role != role:
        user.role = role
        user.role_changed_at = func.now()
        session.commit()
        session.refresh(user)
    return user


def set_active(session: Session, user_id: uuid.UUID, active: bool) -> User:
    user = _require(session, user_id)
    if user.is_active != active:
        user.is_active = active
        session.commit()
        session.refresh(user)
    return user
