"""FastAPI application. Run with: uvicorn mineledger_api.main:app --reload"""

from __future__ import annotations

from fastapi import FastAPI

from .api import health_router, router


def create_app() -> FastAPI:
    app = FastAPI(
        title="MineLedger API",
        version="0.1.0",
        description=(
            "Curated MineLedger curriculum imported from the versioned JSON bundle, plus stateless "
            "multiple-choice grading. No authentication, learner progress or XP in this milestone."
        ),
    )
    app.include_router(health_router)
    app.include_router(router)
    return app


app = create_app()
