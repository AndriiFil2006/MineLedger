"""Environment-based settings. Defaults match backend/docker-compose.yml."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
BUNDLED_CURRICULUM = (
    REPOSITORY_ROOT / "minecraft-mod" / "src" / "main" / "resources" / "data" / "mineledger" / "curriculum" / "accounting_101.json"
)
DEFAULT_DATABASE_URL = "postgresql+psycopg://mineledger:mineledger@127.0.0.1:5433/mineledger"
DEFAULT_TEST_DATABASE_URL = "postgresql+psycopg://mineledger:mineledger@127.0.0.1:5433/mineledger_test"


@dataclass(frozen=True)
class Settings:
    database_url: str
    curriculum_path: Path


def get_settings() -> Settings:
    return Settings(
        database_url=os.environ.get("MINELEDGER_DATABASE_URL", DEFAULT_DATABASE_URL),
        curriculum_path=Path(os.environ.get("MINELEDGER_CURRICULUM_PATH", BUNDLED_CURRICULUM)),
    )
