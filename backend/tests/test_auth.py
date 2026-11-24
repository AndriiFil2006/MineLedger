"""Authentication and role enforcement (Milestone 13A).

A loopback HTTP server plays the identity provider: it serves a discovery document and a JWKS, and the
tests sign tokens with the matching private key. The backend's real verification path runs unchanged,
including the JWKS fetch.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import threading
import time
import uuid
from collections.abc import Iterator
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from sqlalchemy import select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from mineledger_api import auth, cli
from mineledger_api.auth import TokenVerifier, get_token_verifier
from mineledger_api.models import User
from mineledger_api.users import UserNotFound, get_or_create_user, set_active, set_role

AUDIENCE = "https://api.mineledger.test"
KEY_ID = "test-key-1"
ME = "/api/v1/users/me"
STUDENT_ONLY = "/api/v1/auth/student-check"
TEACHER_ONLY = "/api/v1/auth/teacher-check"
PROTECTED = [ME, STUDENT_ONLY, TEACHER_ONLY]


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


class FakeIdentityProvider:
    """Signs tokens and serves the public half of its key the way an OpenID Connect provider does."""

    def __init__(self, port: int = 0) -> None:
        self.key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.other_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.discovery_issuer: str | None = None
        provider = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                if self.path == "/jwks.json":
                    body = provider.jwks()
                elif self.path == "/.well-known/openid-configuration":
                    body = {"issuer": provider.discovery_issuer or provider.issuer, "jwks_uri": provider.jwks_url}
                else:
                    self.send_error(404)
                    return
                payload = json.dumps(body).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args) -> None:
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        self.issuer = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.jwks_url = f"{self.issuer}/jwks.json"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def jwks(self) -> dict:
        jwk = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(self.key.public_key()))
        return {"keys": [{**jwk, "kid": KEY_ID, "use": "sig", "alg": "RS256"}]}

    def public_pem(self) -> bytes:
        return self.key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        )

    def claims(self, subject: str = "student-1", **overrides) -> dict:
        now = int(time.time())
        claims = {"iss": self.issuer, "aud": AUDIENCE, "sub": subject, "iat": now, "exp": now + 300, **overrides}
        return {name: value for name, value in claims.items() if value is not None}

    def token(self, subject: str = "student-1", *, key=None, kid: str | None = KEY_ID, **overrides) -> str:
        headers = {"kid": kid} if kid else {}
        return jwt.encode(self.claims(subject, **overrides), key or self.key, algorithm="RS256", headers=headers)

    def verifier(self, **overrides) -> TokenVerifier:
        return TokenVerifier(**{"issuer": self.issuer, "audience": AUDIENCE, "jwks_url": self.jwks_url, **overrides})


@pytest.fixture(scope="module")
def idp() -> Iterator[FakeIdentityProvider]:
    provider = FakeIdentityProvider()
    yield provider
    provider.server.shutdown()
    provider.server.server_close()


@pytest.fixture
def api(client, idp):
    """The app with authentication configured against the fake provider, and curriculum imported."""
    client.app.dependency_overrides[get_token_verifier] = lambda: idp.verifier()
    return client


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def assert_unauthenticated(response, challenge: str = 'Bearer error="invalid_token"') -> None:
    assert response.status_code == 401
    assert response.headers["WWW-Authenticate"] == challenge
    assert response.json() == {"detail": "Not authenticated"}


def user_count(session: Session) -> int:
    session.rollback()
    return session.scalar(text("SELECT count(*) FROM users"))


# --- Authentication success -------------------------------------------------------------------------


def test_first_sign_in_creates_a_student_with_an_opaque_uuid(api, idp, session):
    response = api.get(ME, headers=bearer(idp.token("auth0|abc123")))
    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"id", "role", "created_at"}
    assert body["role"] == "student"
    user_id = uuid.UUID(body["id"])
    assert user_id.version == 4
    assert "abc123" not in response.text

    row = session.scalars(select(User)).one()
    assert (row.id, row.issuer, row.subject, row.role, row.is_active) == (user_id, idp.issuer, "auth0|abc123", "student", True)
    assert row.role_changed_at is None


def test_identity_persists_across_requests_and_tokens(api, idp, session):
    first = api.get(ME, headers=bearer(idp.token("alex"))).json()
    again = api.get(ME, headers=bearer(idp.token("alex"))).json()
    later_token = api.get(ME, headers=bearer(idp.token("alex", exp=int(time.time()) + 3600))).json()
    assert first == again == later_token
    assert user_count(session) == 1


def test_different_subjects_are_different_users(api, idp, session):
    alex = api.get(ME, headers=bearer(idp.token("alex"))).json()
    sam = api.get(ME, headers=bearer(idp.token("sam"))).json()
    assert alex["id"] != sam["id"]
    assert user_count(session) == 2


def test_the_same_subject_from_another_issuer_is_a_different_user(session):
    here = get_or_create_user(session, "https://idp-a.test", "alex")
    there = get_or_create_user(session, "https://idp-b.test", "alex")
    assert here.id != there.id
    assert get_or_create_user(session, "https://idp-a.test", "alex").id == here.id


def test_bearer_scheme_is_case_insensitive(api, idp):
    assert api.get(ME, headers={"Authorization": f"bearer {idp.token()}"}).status_code == 200


def test_jwks_url_is_discovered_from_the_issuer(api, idp):
    api.app.dependency_overrides[get_token_verifier] = lambda: idp.verifier(jwks_url=None)
    assert api.get(ME, headers=bearer(idp.token())).status_code == 200


# --- Authentication failure -------------------------------------------------------------------------


@pytest.mark.parametrize("path", PROTECTED)
def test_protected_routes_require_a_token(api, session, path):
    assert_unauthenticated(api.get(path), challenge="Bearer")
    assert user_count(session) == 0


@pytest.mark.parametrize(
    "header",
    ["Basic YWxleDpodW50ZXIy", "Bearer", "Bearer ", "Token abc", "alex"],
)
def test_other_authorization_schemes_are_rejected(api, header):
    response = api.get(ME, headers={"Authorization": header})
    assert response.status_code == 401
    assert response.headers["WWW-Authenticate"].startswith("Bearer")


@pytest.mark.parametrize(
    "token",
    ["garbage", "a.b.c", "a.b", "....", "e30.e30.e30", "x" * 9000, "null", "undefined"],
)
def test_malformed_tokens_are_rejected(api, session, token):
    assert_unauthenticated(api.get(ME, headers=bearer(token)))
    assert user_count(session) == 0


def test_truncated_and_tampered_tokens_are_rejected(api, idp):
    header, payload, signature = idp.token().split(".")
    forged_payload = b64url(json.dumps(idp.claims("someone-else")).encode())
    for token in (
        f"{header}.{payload}",
        f"{header}.{payload}.",
        f"{header}.{payload}.{signature[:-4]}",
        f"{header}.{forged_payload}.{signature}",
    ):
        assert_unauthenticated(api.get(ME, headers=bearer(token)))


def invalid_claims() -> dict[str, dict]:
    now = int(time.time())
    return {
        "expired": {"exp": now - 3600},
        "expired just beyond the clock-skew allowance": {"exp": now - auth.CLOCK_SKEW_SECONDS - 5},
        "not valid yet": {"nbf": now + 3600},
        "issued in the future": {"iat": now + 3600},
        "no expiry": {"exp": None},
        "no subject": {"sub": None},
        "blank subject": {"sub": "   "},
        "oversized subject": {"sub": "s" * 300},
        "no issuer": {"iss": None},
        "another issuer": {"iss": "https://evil.example"},
        "issuer with a trailing slash": {"iss": "TRAILING_SLASH"},
        "no audience": {"aud": None},
        "another audience": {"aud": "https://other-api.example"},
        "audience list without this API": {"aud": ["https://a.example", "https://b.example"]},
        "non-numeric expiry": {"exp": "tomorrow"},
    }


@pytest.mark.parametrize("case", invalid_claims())
def test_tokens_with_invalid_claims_are_rejected(api, idp, session, case):
    overrides = invalid_claims()[case]
    if overrides.get("iss") == "TRAILING_SLASH":
        overrides = {"iss": idp.issuer + "/"}
    assert_unauthenticated(api.get(ME, headers=bearer(idp.token(**overrides))))
    assert user_count(session) == 0


def test_a_non_string_subject_is_rejected(api, idp):
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT", "kid": KEY_ID}).encode())
    payload = b64url(json.dumps(idp.claims(sub=12345)).encode())
    signature = jwt.get_algorithm_by_name("RS256").sign(f"{header}.{payload}".encode(), idp.key)
    assert_unauthenticated(api.get(ME, headers=bearer(f"{header}.{payload}.{b64url(signature)}")))


def test_an_audience_list_containing_this_api_is_accepted(api, idp):
    token = idp.token(aud=["https://other-api.example", AUDIENCE])
    assert api.get(ME, headers=bearer(token)).status_code == 200


def test_a_token_within_the_clock_skew_allowance_is_accepted(api, idp):
    assert api.get(ME, headers=bearer(idp.token(exp=int(time.time()) - 5))).status_code == 200


def test_a_token_signed_by_another_key_is_rejected(api, idp):
    assert_unauthenticated(api.get(ME, headers=bearer(idp.token(key=idp.other_key))))


def test_a_token_with_an_unknown_or_missing_key_id_is_rejected(api, idp):
    assert_unauthenticated(api.get(ME, headers=bearer(idp.token(kid="rotated-away"))))
    assert_unauthenticated(api.get(ME, headers=bearer(idp.token(kid=None))))


def test_an_unsigned_token_is_rejected(api, idp):
    header = b64url(json.dumps({"alg": "none", "typ": "JWT", "kid": KEY_ID}).encode())
    payload = b64url(json.dumps(idp.claims()).encode())
    for token in (f"{header}.{payload}.", f"{header}.{payload}"):
        assert_unauthenticated(api.get(ME, headers=bearer(token)))


def test_a_token_hmac_signed_with_the_public_key_is_rejected(api, idp):
    """Algorithm confusion: the public key is public, so it must never work as a shared secret."""
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT", "kid": KEY_ID}).encode())
    payload = b64url(json.dumps(idp.claims()).encode())
    signature = hmac.new(idp.public_pem(), f"{header}.{payload}".encode(), hashlib.sha256).digest()
    assert_unauthenticated(api.get(ME, headers=bearer(f"{header}.{payload}.{b64url(signature)}")))


def test_a_rejected_token_is_not_echoed_back(api, idp):
    token = idp.token(exp=int(time.time()) - 3600)
    response = api.get(ME, headers=bearer(token))
    assert token not in response.text and token not in str(response.headers)


@pytest.mark.parametrize("algorithms", [["HS256"], ["none"], ["RS256", "HS256"], []])
def test_symmetric_and_unsigned_algorithms_cannot_be_configured(algorithms):
    with pytest.raises(ValueError, match="asymmetric"):
        TokenVerifier("https://idp.test", AUDIENCE, algorithms, "https://idp.test/jwks.json")


# --- Configuration and provider availability ----------------------------------------------------------


@pytest.mark.parametrize("path", PROTECTED)
def test_protected_routes_fail_closed_when_authentication_is_not_configured(client, idp, path):
    client.app.dependency_overrides[get_token_verifier] = lambda: None
    for headers in ({}, bearer(idp.token())):
        response = client.get(path, headers=headers)
        assert response.status_code == 503
        assert response.json() == {"detail": "Authentication is not configured on this server"}


def test_authentication_is_off_unless_issuer_and_audience_are_both_set(monkeypatch):
    for name in ("MINELEDGER_AUTH_ISSUER", "MINELEDGER_AUTH_AUDIENCE", "MINELEDGER_AUTH_JWKS_URL", "MINELEDGER_AUTH_ALGORITHMS"):
        monkeypatch.delenv(name, raising=False)
    try:
        get_token_verifier.cache_clear()
        assert get_token_verifier() is None

        monkeypatch.setenv("MINELEDGER_AUTH_ISSUER", "https://idp.test/")
        get_token_verifier.cache_clear()
        assert get_token_verifier() is None

        monkeypatch.setenv("MINELEDGER_AUTH_AUDIENCE", AUDIENCE)
        monkeypatch.setenv("MINELEDGER_AUTH_ALGORITHMS", "RS256, ES256")
        get_token_verifier.cache_clear()
        verifier = get_token_verifier()
        assert (verifier.issuer, verifier.audience, verifier.algorithms) == ("https://idp.test/", AUDIENCE, ["RS256", "ES256"])

        monkeypatch.setenv("MINELEDGER_AUTH_ALGORITHMS", "HS256")
        get_token_verifier.cache_clear()
        with pytest.raises(ValueError, match="asymmetric"):
            get_token_verifier()
    finally:
        get_token_verifier.cache_clear()


def test_an_unreachable_identity_provider_is_a_503_not_a_login(api, idp, session):
    api.app.dependency_overrides[get_token_verifier] = lambda: idp.verifier(jwks_url="http://127.0.0.1:1/jwks.json", timeout=1)
    response = api.get(ME, headers=bearer(idp.token()))
    assert response.status_code == 503
    assert response.json() == {"detail": "The identity provider is unavailable"}
    assert user_count(session) == 0


def test_a_discovery_document_for_another_issuer_is_refused(api, idp):
    idp.discovery_issuer = "https://evil.example"
    try:
        api.app.dependency_overrides[get_token_verifier] = lambda: idp.verifier(jwks_url=None)
        assert api.get(ME, headers=bearer(idp.token())).status_code == 503
    finally:
        idp.discovery_issuer = None


# --- Roles ------------------------------------------------------------------------------------------


def sign_in(api, idp, subject: str) -> uuid.UUID:
    return uuid.UUID(api.get(ME, headers=bearer(idp.token(subject))).json()["id"])


def test_a_student_reaches_student_resources_and_is_denied_teacher_resources(api, idp):
    user_id = sign_in(api, idp, "student-1")
    headers = bearer(idp.token("student-1"))

    allowed = api.get(STUDENT_ONLY, headers=headers)
    assert allowed.status_code == 200
    assert allowed.json()["id"] == str(user_id) and allowed.json()["role"] == "student"

    denied = api.get(TEACHER_ONLY, headers=headers)
    assert denied.status_code == 403
    assert denied.json() == {"detail": "You do not have permission to use this resource"}


def test_a_teacher_reaches_teacher_resources_and_is_denied_student_resources(api, idp, session):
    user_id = sign_in(api, idp, "teacher-1")
    set_role(session, user_id, "teacher")
    headers = bearer(idp.token("teacher-1"))

    allowed = api.get(TEACHER_ONLY, headers=headers)
    assert allowed.status_code == 200
    assert allowed.json()["id"] == str(user_id) and allowed.json()["role"] == "teacher"
    assert api.get(ME, headers=headers).json()["role"] == "teacher"
    assert api.get(STUDENT_ONLY, headers=headers).status_code == 403


def test_role_changes_apply_to_tokens_that_were_already_issued(api, idp, session):
    token = idp.token("pat")
    user_id = sign_in(api, idp, "pat")
    assert api.get(TEACHER_ONLY, headers=bearer(token)).status_code == 403

    set_role(session, user_id, "teacher")
    assert api.get(TEACHER_ONLY, headers=bearer(token)).status_code == 200

    set_role(session, user_id, "student")
    assert api.get(TEACHER_ONLY, headers=bearer(token)).status_code == 403


def test_granting_one_user_does_not_affect_another(api, idp, session):
    teacher = sign_in(api, idp, "teacher-1")
    sign_in(api, idp, "student-1")
    set_role(session, teacher, "teacher")
    assert api.get(TEACHER_ONLY, headers=bearer(idp.token("student-1"))).status_code == 403
    assert api.get(ME, headers=bearer(idp.token("student-1"))).json()["role"] == "student"


def test_a_token_without_identity_never_reaches_the_role_check(api, idp, session):
    """401 (who are you?) takes precedence over 403 (you may not)."""
    assert api.get(TEACHER_ONLY).status_code == 401
    assert api.get(TEACHER_ONLY, headers=bearer(idp.token(exp=int(time.time()) - 3600))).status_code == 401


# --- Privilege escalation ---------------------------------------------------------------------------

ROLE_CLAIMS = {
    "role": "teacher",
    "roles": ["teacher", "admin"],
    "groups": ["teachers"],
    "scope": "teacher admin role:teacher",
    "permissions": ["teacher"],
    "is_teacher": True,
    "https://mineledger/role": "teacher",
    "realm_access": {"roles": ["teacher"]},
}


def test_role_claims_inside_a_valid_token_grant_nothing(api, idp, session):
    token = idp.token("sneaky", **ROLE_CLAIMS)
    assert api.get(ME, headers=bearer(token)).json()["role"] == "student"
    assert api.get(TEACHER_ONLY, headers=bearer(token)).status_code == 403
    assert session.scalars(select(User.role)).all() == ["student"]


def test_request_parameters_headers_and_bodies_grant_nothing(api, idp, session):
    token = idp.token("sneaky")
    headers = {**bearer(token), "X-Role": "teacher", "X-User-Role": "teacher", "X-User-Id": str(uuid.uuid4())}
    for path in (ME, TEACHER_ONLY):
        api.get(f"{path}?role=teacher&user_id={uuid.uuid4()}", headers=headers)
    assert api.get(TEACHER_ONLY, headers=headers).status_code == 403
    assert session.scalars(select(User.role)).all() == ["student"]


@pytest.mark.parametrize("method", ["post", "put", "patch", "delete"])
@pytest.mark.parametrize("path", PROTECTED + ["/api/v1/users", "/api/v1/users/me/role", "/api/v1/roles"])
def test_no_endpoint_writes_users_or_roles(api, idp, session, method, path):
    user_id = sign_in(api, idp, "sneaky")
    response = api.request(method, path, headers=bearer(idp.token("sneaky")), json={"role": "teacher", "id": str(user_id)})
    assert response.status_code in (404, 405)
    assert session.scalars(select(User.role)).all() == ["student"]


def test_a_user_cannot_read_or_name_another_account(api, idp):
    victim = sign_in(api, idp, "victim")
    for path in (f"/api/v1/users/{victim}", f"/api/v1/users/{victim}/role", "/api/v1/users"):
        assert api.get(path, headers=bearer(idp.token("sneaky"))).status_code == 404
    assert api.get(f"{ME}?id={victim}", headers=bearer(idp.token("sneaky"))).json()["id"] != str(victim)


def test_the_api_surface_has_no_role_management(api):
    schema = api.get("/openapi.json").json()
    for path, operations in schema["paths"].items():
        if path.startswith(("/api/v1/users", "/api/v1/auth")):
            assert set(operations) == {"get"}, path
            assert "parameters" not in operations["get"], path
    assert set(schema["components"]["schemas"]["UserProfile"]["properties"]) == {"id", "role", "created_at"}
    assert not any("role" in path or "admin" in path for path in schema["paths"])


def test_the_database_rejects_unknown_roles_and_duplicate_identities(api, idp, session):
    user_id = sign_in(api, idp, "alex")
    with pytest.raises(IntegrityError, match="ck_users_role"):
        session.execute(text("UPDATE users SET role = 'admin' WHERE id = :id"), {"id": user_id})
    session.rollback()
    with pytest.raises(IntegrityError, match="uq_users_issuer_subject"):
        session.execute(
            text("INSERT INTO users (id, issuer, subject) VALUES (:id, :issuer, 'alex')"),
            {"id": uuid.uuid4(), "issuer": idp.issuer},
        )
    session.rollback()
    with pytest.raises(ValueError, match="Unknown role"):
        set_role(session, user_id, "admin")


# --- Disabled accounts ------------------------------------------------------------------------------


def test_a_disabled_account_is_refused_everywhere_until_re_enabled(api, idp, session):
    user_id = sign_in(api, idp, "teacher-1")
    set_role(session, user_id, "teacher")
    headers = bearer(idp.token("teacher-1"))

    set_active(session, user_id, False)
    for path in PROTECTED:
        response = api.get(path, headers=headers)
        assert response.status_code == 403
        assert response.json() == {"detail": "This account is disabled"}

    set_active(session, user_id, True)
    assert api.get(TEACHER_ONLY, headers=headers).status_code == 200


# --- Operator CLI -----------------------------------------------------------------------------------


def test_cli_grants_and_revokes_the_teacher_role(api, idp, session, database_url, capsys):
    user_id = sign_in(api, idp, "teacher-1")
    headers = bearer(idp.token("teacher-1"))
    db = ["--database-url", database_url]

    assert cli.main(["grant-teacher", str(user_id), *db]) == 0
    assert f"{user_id}  teacher" in capsys.readouterr().out
    assert api.get(TEACHER_ONLY, headers=headers).status_code == 200
    session.rollback()
    granted_at = session.scalars(select(User.role_changed_at)).one()
    assert granted_at is not None

    assert cli.main(["grant-teacher", str(user_id), *db]) == 0  # repeating is a no-op
    session.rollback()
    assert session.scalars(select(User.role_changed_at)).one() == granted_at

    assert cli.main(["list-users", *db]) == 0
    listing = capsys.readouterr().out
    assert str(user_id) in listing and "teacher-1" in listing and "active" in listing

    assert cli.main(["revoke-teacher", str(user_id), *db]) == 0
    assert api.get(TEACHER_ONLY, headers=headers).status_code == 403

    assert cli.main(["disable-user", str(user_id), *db]) == 0
    assert "disabled" in capsys.readouterr().out
    assert api.get(ME, headers=headers).status_code == 403
    assert cli.main(["enable-user", str(user_id), *db]) == 0
    assert api.get(ME, headers=headers).status_code == 200


def test_cli_refuses_unknown_and_malformed_user_ids(clean_db, session, database_url, capsys):
    db = ["--database-url", database_url]
    assert cli.main(["list-users", *db]) == 0
    assert "No users have signed in yet." in capsys.readouterr().out

    missing = uuid.uuid4()
    for command in ("grant-teacher", "revoke-teacher", "disable-user", "enable-user"):
        assert cli.main([command, str(missing), *db]) == 4
        assert f"No user with ID {missing}" in capsys.readouterr().err
    with pytest.raises(SystemExit) as exit_info:
        cli.main(["grant-teacher", "alex", *db])
    assert exit_info.value.code == 2
    assert user_count(session) == 0  # the CLI never creates users

    with pytest.raises(UserNotFound):
        set_role(session, missing, "teacher")


# --- Existing public API ----------------------------------------------------------------------------

PUBLIC_GETS = [
    "/health",
    "/api/v1/meta",
    "/api/v1/courses",
    "/api/v1/courses/accounting101",
    "/api/v1/assignments/inventory_fundamentals",
    "/api/v1/assignments/inventory_fundamentals/questions",
]


@pytest.mark.parametrize("configured", [True, False])
@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer garbage"}], ids=["anonymous", "bad-token"])
def test_curriculum_endpoints_stay_public(client, idp, session, configured, headers):
    client.app.dependency_overrides[get_token_verifier] = (lambda: idp.verifier()) if configured else (lambda: None)
    for path in PUBLIC_GETS:
        assert client.get(path, headers=headers).status_code == 200, path
    grade = client.post(
        "/api/v1/questions/inventory.shovels/grade", json={"selected_option_id": "shovels.inventory_payable"}, headers=headers
    )
    assert grade.status_code == 200 and grade.json()["status"] == "correct"
    assert user_count(session) == 0
