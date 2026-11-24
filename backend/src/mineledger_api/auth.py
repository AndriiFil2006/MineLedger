"""Authentication and authorization (Milestone 13A). See backend/AUTHENTICATION.md.

The backend is an OAuth 2.0 resource server: it issues no tokens and stores no credentials. An external
OpenID Connect provider signs access tokens; each request's bearer token is verified here (signature
against the provider's JWKS, issuer, audience, expiry) and mapped to a server-side `User` row.
The role is read from that row on every request and never from the token.

Tokens are never logged, and no error response says why a token was rejected.
"""

from __future__ import annotations

import json
import threading
import urllib.request
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from functools import lru_cache
from typing import Annotated
from urllib.parse import urlparse

import jwt
from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jwt import PyJWKClient
from jwt.exceptions import PyJWKClientConnectionError, PyJWKClientError
from sqlalchemy.orm import Session

from . import schemas
from .config import get_settings
from .db import get_session
from .models import ROLE_STUDENT, ROLE_TEACHER, User
from .users import get_or_create_user

# Asymmetric signatures only: the backend holds public keys, so nothing it knows can forge a token.
# "none" and the shared-secret HS* family are never accepted, whatever the configuration says.
ASYMMETRIC_ALGORITHMS = frozenset(
    {"RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512", "EdDSA"}
)
REQUIRED_CLAIMS = ("exp", "iss", "aud", "sub")
CLOCK_SKEW_SECONDS = 30
MAX_TOKEN_LENGTH = 8192
MAX_SUBJECT_LENGTH = 255
HTTP_TIMEOUT_SECONDS = 5.0


class InvalidToken(Exception):
    """The bearer token is malformed, forged, expired or not meant for this API."""


class AuthUnavailable(Exception):
    """The identity provider's signing keys could not be fetched, so nothing can be verified right now."""


@dataclass(frozen=True)
class Identity:
    issuer: str
    subject: str


def discover_jwks_url(issuer: str, timeout: float = HTTP_TIMEOUT_SECONDS) -> str:
    """Read `jwks_uri` from the provider's OpenID Connect discovery document."""
    url = issuer.rstrip("/") + "/.well-known/openid-configuration"
    if urlparse(url).scheme not in ("http", "https"):
        raise AuthUnavailable("The issuer must be an http(s) URL")
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:  # noqa: S310 - scheme checked above
            document = json.load(response)
    except (OSError, ValueError) as error:
        raise AuthUnavailable("The identity provider's discovery document could not be read") from error
    if not isinstance(document, dict) or document.get("issuer") != issuer or not isinstance(document.get("jwks_uri"), str):
        raise AuthUnavailable("The identity provider's discovery document does not match the configured issuer")
    return document["jwks_uri"]


class TokenVerifier:
    """Verifies access tokens from one issuer for one audience."""

    def __init__(
        self,
        issuer: str,
        audience: str,
        algorithms: Sequence[str] = ("RS256",),
        jwks_url: str | None = None,
        timeout: float = HTTP_TIMEOUT_SECONDS,
    ) -> None:
        unsupported = sorted(set(algorithms) - ASYMMETRIC_ALGORITHMS)
        if not algorithms or unsupported:
            raise ValueError(
                f"Token algorithms must be asymmetric ({', '.join(sorted(ASYMMETRIC_ALGORITHMS))}); got {unsupported or 'none'}"
            )
        if not issuer or not audience:
            raise ValueError("Both an issuer and an audience are required")
        self.issuer = issuer
        self.audience = audience
        self.algorithms = list(algorithms)
        self._timeout = timeout
        self._lock = threading.Lock()
        self._jwks_client = PyJWKClient(jwks_url, timeout=timeout) if jwks_url else None

    def _keys(self) -> PyJWKClient:
        with self._lock:
            if self._jwks_client is None:
                self._jwks_client = PyJWKClient(discover_jwks_url(self.issuer, self._timeout), timeout=self._timeout)
            return self._jwks_client

    def verify(self, token: str) -> Identity:
        if not token or len(token) > MAX_TOKEN_LENGTH:
            raise InvalidToken
        try:
            key = self._keys().get_signing_key_from_jwt(token)
            claims = jwt.decode(
                token,
                key.key,
                algorithms=self.algorithms,
                audience=self.audience,
                issuer=self.issuer,
                leeway=CLOCK_SKEW_SECONDS,
                options={"require": list(REQUIRED_CLAIMS)},
            )
        except PyJWKClientConnectionError as error:
            raise AuthUnavailable("The identity provider's signing keys could not be fetched") from error
        except (PyJWKClientError, jwt.InvalidTokenError) as error:
            raise InvalidToken from error
        subject = claims["sub"]
        if not subject.strip() or len(subject) > MAX_SUBJECT_LENGTH:
            raise InvalidToken
        return Identity(issuer=claims["iss"], subject=subject)


@lru_cache(maxsize=1)
def get_token_verifier() -> TokenVerifier | None:
    """The process-wide verifier, or None while authentication is not configured."""
    settings = get_settings()
    if not (settings.auth_issuer and settings.auth_audience):
        return None
    return TokenVerifier(settings.auth_issuer, settings.auth_audience, settings.auth_algorithms, settings.auth_jwks_url)


_bearer = HTTPBearer(auto_error=False, description="An access token from the configured OpenID Connect provider")


def _unauthorized(challenge: str) -> HTTPException:
    return HTTPException(status.HTTP_401_UNAUTHORIZED, "Not authenticated", headers={"WWW-Authenticate": challenge})


def current_user(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)],
    verifier: Annotated[TokenVerifier | None, Depends(get_token_verifier)],
    session: Annotated[Session, Depends(get_session)],
) -> User:
    """The authenticated, active user. Depend on this (or `require_role`) in every protected handler."""
    if verifier is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Authentication is not configured on this server")
    if credentials is None:
        raise _unauthorized("Bearer")
    try:
        identity = verifier.verify(credentials.credentials)
    except InvalidToken:
        raise _unauthorized('Bearer error="invalid_token"') from None
    except AuthUnavailable:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "The identity provider is unavailable") from None
    user = get_or_create_user(session, identity.issuer, identity.subject)
    if not user.is_active:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "This account is disabled")
    return user


CurrentUser = Annotated[User, Depends(current_user)]


def require_role(role: str) -> Callable[[User], User]:
    """A dependency that admits only users whose stored role is exactly `role`."""

    def dependency(user: CurrentUser) -> User:
        if user.role != role:
            raise HTTPException(status.HTTP_403_FORBIDDEN, "You do not have permission to use this resource")
        return user

    return dependency


StudentUser = Annotated[User, Depends(require_role(ROLE_STUDENT))]
TeacherUser = Annotated[User, Depends(require_role(ROLE_TEACHER))]

AUTH_ERRORS = {
    401: {"description": "Missing, malformed, expired or otherwise invalid access token"},
    503: {"description": "Authentication is not configured, or the identity provider is unreachable"},
}
ROLE_ERRORS = {**AUTH_ERRORS, 403: {"description": "The account is disabled or does not have the required role"}}

router = APIRouter(prefix="/api/v1", tags=["users"])


def _profile(user: User) -> schemas.UserProfile:
    return schemas.UserProfile(id=user.id, role=user.role, created_at=user.created_at)


# Every handler below answers about the caller only. None takes a user ID, so there is no other
# account a request could name. There is deliberately no endpoint that writes a role.


@router.get("/users/me", response_model=schemas.UserProfile, responses=ROLE_ERRORS)
def get_me(user: CurrentUser) -> schemas.UserProfile:
    """The caller's own server-side identity. The first authenticated request creates it as a student."""
    return _profile(user)


@router.get("/auth/student-check", response_model=schemas.UserProfile, responses=ROLE_ERRORS)
def student_check(user: StudentUser) -> schemas.UserProfile:
    """Succeeds only for a student. Exists to prove role enforcement until real student resources arrive."""
    return _profile(user)


@router.get("/auth/teacher-check", response_model=schemas.UserProfile, responses=ROLE_ERRORS)
def teacher_check(user: TeacherUser) -> schemas.UserProfile:
    """Succeeds only for a teacher. Exists to prove role enforcement until real teacher resources arrive."""
    return _profile(user)
