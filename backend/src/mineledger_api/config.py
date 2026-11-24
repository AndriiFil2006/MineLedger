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
    # The public (secret-less) OAuth client that players sign in with. Published to clients, never used here.
    auth_client_id: str | None = None
    # "development" serves this computer only; "production" must pass `production_problems` to start.
    environment: str = "development"


ENVIRONMENTS = ("development", "production")


DEFAULT_ENV_FILE = REPOSITORY_ROOT / "backend" / ".env"
ENV_PREFIX = "MINELEDGER_"


def read_env_file(path: Path) -> dict[str, str]:
    """`MINELEDGER_*` settings from a `.env` file: `NAME=value` lines, `#` comments, optional quotes.

    A missing file is simply no settings. Other names are ignored, so the file cannot change anything
    else about the process.
    """
    try:
        lines = path.read_text(encoding="utf-8-sig").splitlines()
    except OSError:
        return {}
    values = {}
    for line in lines:
        line = line.strip().removeprefix("export ").lstrip()
        name, separator, value = line.partition("=")
        name, value = name.strip(), value.strip()
        if not separator or line.startswith("#") or not name.startswith(ENV_PREFIX):
            continue
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        else:
            for marker in (" #", "\t#"):  # an unquoted value ends at a trailing comment
                value = value.split(marker, 1)[0].rstrip()
        values[name] = value
    return values


def _environment() -> dict[str, str]:
    """The process environment, with `backend/.env` filling in whatever it does not set.

    Real environment variables always win. MINELEDGER_ENV_FILE names another file; set it to an empty
    value to read no file at all, which is what the test suite does.
    """
    chosen = os.environ.get("MINELEDGER_ENV_FILE")
    if chosen is not None and not chosen.strip():
        return dict(os.environ)
    return {**read_env_file(Path(chosen) if chosen else DEFAULT_ENV_FILE), **os.environ}


def get_settings() -> Settings:
    env = _environment()
    return Settings(
        database_url=env.get("MINELEDGER_DATABASE_URL") or DEFAULT_DATABASE_URL,
        curriculum_path=Path(env.get("MINELEDGER_CURRICULUM_PATH") or BUNDLED_CURRICULUM),
        auth_issuer=env.get("MINELEDGER_AUTH_ISSUER") or None,
        auth_audience=env.get("MINELEDGER_AUTH_AUDIENCE") or None,
        auth_jwks_url=env.get("MINELEDGER_AUTH_JWKS_URL") or None,
        auth_algorithms=tuple(
            name.strip() for name in (env.get("MINELEDGER_AUTH_ALGORITHMS") or "RS256").split(",") if name.strip()
        ),
        auth_client_id=env.get("MINELEDGER_AUTH_CLIENT_ID") or None,
        environment=(env.get("MINELEDGER_ENV") or "development").strip().lower(),
    )


def production_problems(settings: Settings) -> list[str]:
    """Everything that makes these settings unsafe to serve to real students. Empty means deployable."""
    problems = []
    for name, value in (
        ("MINELEDGER_AUTH_ISSUER", settings.auth_issuer),
        ("MINELEDGER_AUTH_AUDIENCE", settings.auth_audience),
        ("MINELEDGER_AUTH_CLIENT_ID", settings.auth_client_id),
    ):
        if not value:
            problems.append(f"{name} is not set")
    for name, value in (("MINELEDGER_AUTH_ISSUER", settings.auth_issuer), ("MINELEDGER_AUTH_JWKS_URL", settings.auth_jwks_url)):
        if value and not value.startswith("https://"):
            problems.append(f"{name} must be an https:// URL")
    if settings.database_url == DEFAULT_DATABASE_URL:
        problems.append("MINELEDGER_DATABASE_URL is the built-in development database and password")
    return problems
