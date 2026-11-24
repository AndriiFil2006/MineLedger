"""FastAPI application. Run with: uvicorn mineledger_api.main:app --reload"""

from __future__ import annotations

from fastapi import FastAPI

from .api import health_router, router
from .auth import router as auth_router
from .progress_api import router as progress_router


def create_app() -> FastAPI:
    app = FastAPI(
        title="MineLedger API",
        version="0.1.0",
        description=(
            "Curated MineLedger curriculum imported from the versioned JSON bundle, plus stateless "
            "multiple-choice grading. Curriculum and grading are public. `/users/me` and the role checks need "
            "a bearer access token from the configured OpenID Connect provider, as do student submissions and "
            "progress, which the server grades, records and rewards."
        ),
    )
    app.include_router(health_router)
    app.include_router(router)
    app.include_router(auth_router)
    app.include_router(progress_router)
    return app


app = create_app()
