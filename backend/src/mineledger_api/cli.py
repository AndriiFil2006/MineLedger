"""Command line: `mineledger validate-curriculum` and `mineledger import-curriculum`.

Exit codes: 0 success, 1 database error, 2 invalid curriculum, 3 import conflict (nothing written).
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from .config import get_settings
from .curriculum import CurriculumValidationError, load_file
from .db import make_engine
from .importer import CurriculumImportConflict, import_curriculum


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

    args = parser.parse_args(argv)
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
