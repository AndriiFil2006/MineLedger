"""Deployment modes and the public sign-in configuration endpoint (Milestone 13C)."""

from __future__ import annotations

import os
from collections.abc import Iterator
from dataclasses import replace
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session, sessionmaker

from mineledger_api.config import DEFAULT_DATABASE_URL, BUNDLED_CURRICULUM, Settings, get_settings, production_problems, read_env_file
from mineledger_api.db import get_session
from mineledger_api.main import DEVELOPMENT_ONLY, create_app

GRADE = "/api/v1/questions/inventory.shovels/grade"
ANSWER = {"selected_option_id": "shovels.inventory_payable"}
PRODUCTION = Settings(
    database_url="postgresql+psycopg://app:long-random-password@db.internal:5432/mineledger",
    curriculum_path=BUNDLED_CURRICULUM,
    auth_issuer="https://tenant.example-idp.com/",
    auth_audience="https://api.mineledger.example",
    auth_client_id="public-client-id",
    environment="production",
)
DEVELOPMENT = replace(PRODUCTION, environment="development")


@pytest.fixture
def serve(imported, clean_db):
    """serve(settings, peer) -> a client for an app built from those settings, backed by the test database."""
    factory = sessionmaker(bind=clean_db)

    def override() -> Iterator[Session]:
        with factory() as request_session:
            yield request_session

    def build(settings: Settings, peer: str = "testclient") -> TestClient:
        app = create_app(settings)
        app.dependency_overrides[get_session] = override
        app.dependency_overrides[get_settings] = lambda: settings
        return TestClient(app, client=(peer, 50000))

    return build


# --- Sign-in configuration --------------------------------------------------------------------------


def test_auth_config_publishes_the_public_client_settings(serve):
    response = serve(DEVELOPMENT).get("/api/v1/auth/config")
    assert response.status_code == 200
    assert response.json() == {
        "enabled": True,
        "issuer": "https://tenant.example-idp.com/",
        "audience": "https://api.mineledger.example",
        "client_id": "public-client-id",
    }


@pytest.mark.parametrize("missing", ["auth_issuer", "auth_audience", "auth_client_id"])
def test_auth_config_is_disabled_until_every_value_is_set(serve, missing):
    response = serve(replace(DEVELOPMENT, **{missing: None})).get("/api/v1/auth/config")
    assert response.json() == {"enabled": False, "issuer": None, "audience": None, "client_id": None}


def test_auth_config_needs_no_token_and_exposes_nothing_else(serve):
    client = serve(replace(DEVELOPMENT, database_url="postgresql+psycopg://user:hunter2@db/x", auth_jwks_url="https://k/jwks"))
    response = client.get("/api/v1/auth/config", headers={"Authorization": "Bearer garbage"})
    assert response.status_code == 200
    assert set(response.json()) == {"enabled", "issuer", "audience", "client_id"}
    assert "hunter2" not in response.text and "jwks" not in response.text
    schema = client.get("/openapi.json").json()["components"]["schemas"]["AuthConfig"]["properties"]
    assert not any("secret" in name for name in schema)


def test_settings_read_the_client_id_and_environment(monkeypatch):
    monkeypatch.delenv("MINELEDGER_ENV", raising=False)
    monkeypatch.delenv("MINELEDGER_AUTH_CLIENT_ID", raising=False)
    assert (get_settings().environment, get_settings().auth_client_id) == ("development", None)
    monkeypatch.setenv("MINELEDGER_ENV", " Production ")
    monkeypatch.setenv("MINELEDGER_AUTH_CLIENT_ID", "abc123")
    assert (get_settings().environment, get_settings().auth_client_id) == ("production", "abc123")


# --- Production mode --------------------------------------------------------------------------------


def test_production_settings_that_are_safe_have_no_problems():
    assert production_problems(PRODUCTION) == []
    create_app(PRODUCTION)


@pytest.mark.parametrize(
    ("change", "problem"),
    [
        ({"auth_issuer": None}, "MINELEDGER_AUTH_ISSUER is not set"),
        ({"auth_audience": None}, "MINELEDGER_AUTH_AUDIENCE is not set"),
        ({"auth_client_id": None}, "MINELEDGER_AUTH_CLIENT_ID is not set"),
        ({"auth_issuer": "http://127.0.0.1:9011"}, "MINELEDGER_AUTH_ISSUER must be an https:// URL"),
        ({"auth_jwks_url": "http://keys.example/jwks.json"}, "MINELEDGER_AUTH_JWKS_URL must be an https:// URL"),
        ({"database_url": DEFAULT_DATABASE_URL}, "MINELEDGER_DATABASE_URL is the built-in development database and password"),
    ],
)
def test_production_refuses_to_start_with_unsafe_settings(change, problem):
    settings = replace(PRODUCTION, **change)
    assert production_problems(settings) == [problem]
    with pytest.raises(RuntimeError, match="cannot start in production mode") as error:
        create_app(settings)
    assert problem in str(error.value)


def test_production_reports_every_problem_at_once():
    bare = Settings(database_url=DEFAULT_DATABASE_URL, curriculum_path=BUNDLED_CURRICULUM, environment="production")
    assert len(production_problems(bare)) == 4
    with pytest.raises(RuntimeError) as error:
        create_app(bare)
    assert "hunter2" not in str(error.value) and "mineledger:mineledger" not in str(error.value)


@pytest.mark.parametrize("environment", ["prod", "staging", "", "PRODUCTION "])
def test_an_unknown_environment_refuses_to_start(environment):
    with pytest.raises(RuntimeError, match="MINELEDGER_ENV must be one of development, production"):
        create_app(replace(PRODUCTION, environment=environment))


def test_production_disables_the_public_answer_oracle(serve):
    client = serve(PRODUCTION, peer="203.0.113.7")
    for headers in ({}, {"Authorization": "Bearer garbage"}):
        response = client.post(GRADE, json=ANSWER, headers=headers)
        assert response.status_code == 403
        assert response.json() == {"detail": "Public grading is disabled on this server"}
        assert "explanation" not in response.text and "correct" not in response.text
    # Not even to reveal whether a question exists.
    assert client.post("/api/v1/questions/nope/grade", json=ANSWER).status_code == 403


def test_production_serves_remote_clients_and_keeps_authentication(serve):
    client = serve(PRODUCTION, peer="203.0.113.7")
    forwarded = {"X-Forwarded-For": "198.51.100.4", "X-Forwarded-Host": "api.mineledger.example"}
    assert client.get("/api/v1/courses", headers=forwarded).status_code == 200
    assert client.get("/api/v1/auth/config").json()["enabled"] is True
    assert client.get("/api/v1/assignments/inventory_fundamentals/questions").status_code == 200
    assert client.get("/api/v1/users/me").status_code in (401, 503)
    assert client.get("/api/v1/progress").status_code in (401, 503)


# --- Development mode -------------------------------------------------------------------------------


def test_development_keeps_public_grading_for_this_computer(serve):
    for peer in ("testclient", "127.0.0.1", "::1"):
        response = serve(DEVELOPMENT, peer=peer).post(GRADE, json=ANSWER)
        assert response.status_code == 200 and response.json()["status"] == "correct"


@pytest.mark.parametrize("peer", ["203.0.113.7", "192.168.1.20", "10.0.0.5", "::ffff:203.0.113.7", "2001:db8::1", "localhost"])
def test_development_refuses_every_other_computer(serve, peer):
    client = serve(DEVELOPMENT, peer=peer)
    for method, path in (("post", GRADE), ("get", "/api/v1/courses"), ("get", "/health"), ("get", "/docs"), ("get", "/api/v1/users/me")):
        response = client.request(method, path, json=ANSWER if method == "post" else None)
        assert response.status_code == 403, path
        assert response.json() == {"detail": DEVELOPMENT_ONLY}


@pytest.mark.parametrize("header", ["X-Forwarded-For", "Forwarded", "X-Forwarded-Host", "X-Real-IP"])
def test_development_refuses_requests_that_came_through_a_proxy(serve, header):
    """A reverse proxy on the same machine makes every visitor look local; its headers give it away."""
    client = serve(DEVELOPMENT, peer="127.0.0.1")
    response = client.post(GRADE, json=ANSWER, headers={header: "203.0.113.7"})
    assert response.status_code == 403 and response.json() == {"detail": DEVELOPMENT_ONLY}
    assert client.get("/api/v1/courses", headers={header: "for=203.0.113.7"}).status_code == 403


def test_the_default_application_is_development(client):
    assert client.app.state.environment == "development"
    assert client.post(GRADE, json=ANSWER).status_code == 200


# --- backend/.env -----------------------------------------------------------------------------------

AUTH_NAMES = ("MINELEDGER_AUTH_ISSUER", "MINELEDGER_AUTH_AUDIENCE", "MINELEDGER_AUTH_CLIENT_ID", "MINELEDGER_ENV")


@pytest.fixture
def env_file(tmp_path, monkeypatch):
    """write(text) -> settings read with that text as the .env file and no auth variables in the environment."""
    for name in AUTH_NAMES:
        monkeypatch.delenv(name, raising=False)
    path = tmp_path / ".env"
    monkeypatch.setenv("MINELEDGER_ENV_FILE", str(path))

    def write(text: str) -> Settings:
        path.write_text(text, encoding="utf-8")
        return get_settings()

    return write


def test_settings_are_read_from_the_env_file(env_file):
    settings = env_file(
        "# MineLedger sign-in\n"
        "MINELEDGER_AUTH_ISSUER=https://tenant.example-idp.com/\n"
        "MINELEDGER_AUTH_AUDIENCE = https://api.mineledger.example   # the API identifier\n"
        'MINELEDGER_AUTH_CLIENT_ID="abc 123 # not a comment"\n'
        "export MINELEDGER_ENV='development'\n"
        "\n"
        "# MINELEDGER_AUTH_JWKS_URL=https://commented.out/jwks\n"
        "not a setting\n"
    )
    assert settings.auth_issuer == "https://tenant.example-idp.com/"
    assert settings.auth_audience == "https://api.mineledger.example"
    assert settings.auth_client_id == "abc 123 # not a comment"
    assert settings.environment == "development"
    assert settings.auth_jwks_url is None


def test_the_real_environment_wins_over_the_env_file(env_file, monkeypatch):
    monkeypatch.setenv("MINELEDGER_AUTH_AUDIENCE", "from-the-environment")
    settings = env_file("MINELEDGER_AUTH_AUDIENCE=from-the-file\nMINELEDGER_AUTH_CLIENT_ID=from-the-file\n")
    assert (settings.auth_audience, settings.auth_client_id) == ("from-the-environment", "from-the-file")


def test_the_env_file_can_only_set_mineledger_settings(env_file):
    env_file("PATH=/nowhere\nPYTHONPATH=/evil\nMINELEDGER_AUTH_CLIENT_ID=cid\n")
    assert read_env_file(Path(os.environ["MINELEDGER_ENV_FILE"])) == {"MINELEDGER_AUTH_CLIENT_ID": "cid"}
    assert "/nowhere" not in os.environ["PATH"]


def test_a_missing_or_disabled_env_file_means_defaults(tmp_path, monkeypatch):
    for name in AUTH_NAMES:
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("MINELEDGER_ENV_FILE", str(tmp_path / "does-not-exist.env"))
    assert get_settings().auth_issuer is None

    (tmp_path / ".env").write_text("MINELEDGER_AUTH_ISSUER=https://ignored.example/\n", encoding="utf-8")
    monkeypatch.setenv("MINELEDGER_ENV_FILE", "")
    assert get_settings().auth_issuer is None


def test_an_empty_value_in_the_env_file_is_the_same_as_unset(env_file):
    settings = env_file("MINELEDGER_AUTH_ISSUER=\nMINELEDGER_DATABASE_URL=\nMINELEDGER_AUTH_ALGORITHMS=\n")
    assert settings.auth_issuer is None
    assert settings.database_url == DEFAULT_DATABASE_URL
    assert settings.auth_algorithms == ("RS256",)


def test_the_test_suite_ignores_the_developers_own_env_file():
    assert os.environ["MINELEDGER_ENV_FILE"] == ""
    assert not any(name.startswith("MINELEDGER_AUTH_") for name in os.environ)
