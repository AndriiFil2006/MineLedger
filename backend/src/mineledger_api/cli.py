"""Command line: curriculum tools (`validate-curriculum`, `import-curriculum`) and operator-only user
administration (`list-users`, `grant-teacher`, `revoke-teacher`, `disable-user`, `enable-user`).

Roles can be changed here and nowhere else: whoever can run this against the database is the administrator.

Exit codes: 0 success, 1 database error, 2 invalid curriculum, 3 import conflict, 4 unknown user
(nothing written for 1-4).
"""

from __future__ import annotations

import argparse
import sys
import uuid
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from .config import get_settings
from .curriculum import CurriculumValidationError, load_file
from .db import make_engine
from .importer import CurriculumImportConflict, import_curriculum
from .models import ROLE_STUDENT, ROLE_TEACHER, User
from .users import UserNotFound, set_active, set_role

USER_COMMANDS = {
    "grant-teacher": "Give a user the teacher role",
    "revoke-teacher": "Return a teacher to the student role",
    "disable-user": "Refuse every authenticated request from a user",
    "enable-user": "Re-enable a disabled user",
}


def _describe(user: User) -> str:
    state = "active" if user.is_active else "disabled"
    return f"{user.id}  {user.role:<7}  {state:<8}  {user.created_at:%Y-%m-%d %H:%M}  {user.issuer}  {user.subject}"


def _run_user_command(args: argparse.Namespace) -> int:
    engine = make_engine(args.database_url)
    try:
        with Session(engine) as session:
            if args.command == "list-users":
                users = session.scalars(select(User).order_by(User.created_at, User.id)).all()
                print("\n".join(_describe(user) for user in users) or "No users have signed in yet.")
                return 0
            if args.command == "grant-teacher":
                user = set_role(session, args.user_id, ROLE_TEACHER)
            elif args.command == "revoke-teacher":
                user = set_role(session, args.user_id, ROLE_STUDENT)
            else:
                user = set_active(session, args.user_id, args.command == "enable-user")
            print(_describe(user))
            return 0
    except UserNotFound as error:
        print(f"{error}. Run `mineledger list-users` to see known users.", file=sys.stderr)
        return 4
    except SQLAlchemyError as error:
        print(f"Database error; nothing was written: {error}", file=sys.stderr)
        return 1
    finally:
        engine.dispose()


def main(argv: list[str] | None = None) -> int:
    settings = get_settings()
    parser = argparse.ArgumentParser(prog="mineledger", description="MineLedger backend tools")
    commands = parser.add_subparsers(dest="command", required=True)

    validate = commands.add_parser("validate-curriculum", help="Validate a curriculum file without a database")
    validate.add_argument("--file", type=Path, default=settings.curriculum_path)

    importer = commands.add_parser("import-curriculum", help="Validate and import a curriculum file")
    importer.add_argument("--file", type=Path, default=settings.curriculum_path)
    importer.add_argument("--bundle-id", help="Stable bundle ID (default: the file name without .json)")
    importer.add_argument("--database-url", default=settings.database_url)
    importer.add_argument("--dry-run", action="store_true", help="Run every check, then roll back")

    users = commands.add_parser("list-users", help="List users: ID, role, state, created, issuer, subject")
    users.add_argument("--database-url", default=settings.database_url)
    for name, description in USER_COMMANDS.items():
        command = commands.add_parser(name, help=description)
        command.add_argument("user_id", type=uuid.UUID, help="Internal user ID (from list-users or GET /api/v1/users/me)")
        command.add_argument("--database-url", default=settings.database_url)

    args = parser.parse_args(argv)
    if args.command == "list-users" or args.command in USER_COMMANDS:
        return _run_user_command(args)
    try:
        document = load_file(args.file)
    except (CurriculumValidationError, OSError) as error:
        print(f"Invalid curriculum: {error}", file=sys.stderr)
        return 2
    if args.command == "validate-curriculum":
        print(
            f"Valid curriculum {args.file} (schema {document.schema_version}, sha256 {document.sha256}): "
            f"{len(document.courses)} courses, {len(document.units)} units, {len(document.assignments)} assignments, "
            f"{len(document.concepts)} concepts, {len(document.multiple_choice)} multiple-choice and "
            f"{len(document.journal_entries)} journal-entry questions, {len(document.lessons)} lessons, {len(document.labs)} labs."
        )
        return 0

    bundle_id = args.bundle_id or args.file.stem
    engine = make_engine(args.database_url)
    try:
        with Session(engine) as session:
            report = import_curriculum(session, document, bundle_id, str(args.file), dry_run=args.dry_run)
    except CurriculumImportConflict as error:
        print(str(error), file=sys.stderr)
        return 3
    except SQLAlchemyError as error:
        print(f"Database error; nothing was written: {error}", file=sys.stderr)
        return 1
    finally:
        engine.dispose()
    mode = "Dry run (rolled back)" if report.dry_run else "Imported"
    state = "changes" if report.changed else "no changes; database already matches the file"
    print(f"{mode} bundle {bundle_id!r} from {args.file} (sha256 {report.content_sha256}): {state}")
    print("\n".join(report.summary_lines()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
