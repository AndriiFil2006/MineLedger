"""FastAPI application. Run with: uvicorn mineledger_api.main:app --reload

Two modes, chosen by MINELEDGER_ENV (see AUTHENTICATION.md, "Deployment modes"):

- development (default): everything is on, including the public stateless grader, and only requests
  from this computer are answered. A development server put on a network refuses everyone else.
- production: refuses to start unless authentication is fully configured over HTTPS and the database
  is not the development default, and the public grader is off.
"""

from __future__ import annotations

from fastapi import FastAPI, Request, status
from fastapi.responses import JSONResponse

from .api import health_router, router
from .auth import router as auth_router
from .config import ENVIRONMENTS, Settings, get_settings, production_problems
from .progress_api import router as progress_router

# "testclient" is the peer address Starlette's in-process TestClient reports; no network peer can have it.
LOCAL_PEERS = frozenset({"127.0.0.1", "::1", "testclient"})
FORWARDING_HEADERS = ("forwarded", "x-forwarded-for", "x-forwarded-host", "x-real-ip")
DEVELOPMENT_ONLY = (
    "This MineLedger server is in development mode and only answers requests from the computer it runs on. "
    "Set MINELEDGER_ENV=production (see backend/AUTHENTICATION.md) to serve other computers."
)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    if settings.environment not in ENVIRONMENTS:
        raise RuntimeError(f"MINELEDGER_ENV must be one of {', '.join(ENVIRONMENTS)}; got {settings.environment!r}")
    production = settings.environment == "production"
    if production and (problems := production_problems(settings)):
        raise RuntimeError("MineLedger cannot start in production mode: " + "; ".join(problems))

    app = FastAPI(
        title="MineLedger API",
        version="0.1.0",
        description=(
            "Curated MineLedger curriculum imported from the versioned JSON bundle. Curriculum is public. "
            "`/users/me`, student submissions and progress need a bearer access token from the configured "
            "OpenID Connect provider. The public stateless grader exists in development mode only."
        ),
    )
    app.state.environment = settings.environment

    if not production:

        @app.middleware("http")
        async def development_serves_this_computer_only(request: Request, call_next):
            peer = request.client.host if request.client else None
            if peer not in LOCAL_PEERS or any(name in request.headers for name in FORWARDING_HEADERS):
                return JSONResponse({"detail": DEVELOPMENT_ONLY}, status_code=status.HTTP_403_FORBIDDEN)
            return await call_next(request)

    app.include_router(health_router)
    app.include_router(router)
    app.include_router(auth_router)
    app.include_router(progress_router)
    return app


app = create_app()
