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
    # OpenID Connect resource-server settings (see AUTHENTICATION.md). Authentication is disabled, and every
    # protected endpoint answers 503, until both the issuer and the audience are set.
    auth_issuer: str | None = None
    auth_audience: str | None = None
    auth_jwks_url: str | None = None
    auth_algorithms: tuple[str, ...] = ("RS256",)


def get_settings() -> Settings:
    return Settings(
        database_url=os.environ.get("MINELEDGER_DATABASE_URL", DEFAULT_DATABASE_URL),
        curriculum_path=Path(os.environ.get("MINELEDGER_CURRICULUM_PATH", BUNDLED_CURRICULUM)),
        auth_issuer=os.environ.get("MINELEDGER_AUTH_ISSUER") or None,
        auth_audience=os.environ.get("MINELEDGER_AUTH_AUDIENCE") or None,
        auth_jwks_url=os.environ.get("MINELEDGER_AUTH_JWKS_URL") or None,
        auth_algorithms=tuple(
            name.strip() for name in os.environ.get("MINELEDGER_AUTH_ALGORITHMS", "RS256").split(",") if name.strip()
        ),
    )
