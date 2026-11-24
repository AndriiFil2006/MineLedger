# MineLedger

MineLedger teaches real introductory accounting inside Minecraft. A computer block opens "MineOS",
whose MineLedger app offers courses, multiple-choice concept mastery, a journal-entry lab, and local
progress. A separate FastAPI service holds the same curriculum in PostgreSQL for online browsing and,
later, authoritative progress and a teacher portal.

`AGENTS.md` is the detailed guide: the per-task file map, exact behavioral rules, and machine-specific
build notes. It is imported below, so it loads with this file. This file is the overview; where the
two disagree, check the code and fix whichever is stale.

@AGENTS.md

## Layout

| Path | Responsibility |
| --- | --- |
| `minecraft-mod/` | Fabric mod (Java 25, Minecraft 26.3). Run Gradle here |
| `minecraft-mod/src/main/java/com/mineledger/` | Domain, free of Minecraft classes: `accounting/` (grading, mastery), `education/` (courses, lessons), `curriculum/` (JSON loader), `progress/` (save files), `backend/` (HTTP client, connection state), `presentation/` |
| `minecraft-mod/src/client/` | Every screen, widget and render call |
| `minecraft-mod/src/main/resources/data/mineledger/curriculum/accounting_101.json` | The single source of curriculum content |
| `backend/` | FastAPI + SQLAlchemy 2 + Alembic + psycopg 3, Python 3.13, PostgreSQL 17 in Docker |
| `backend/src/mineledger_api/` | `curriculum` (Python port of the Java loader), `importer`, `api`, `grading`, `auth`, `users`, `models`, `schemas`, `cli` |
| `shared/fixtures/` | Cases that the Java and Python suites both run, so the two sides cannot drift |

Topic documents live beside the code (`minecraft-mod/*.md`, `backend/README.md`,
`backend/AUTHENTICATION.md`); `AGENTS.md` says which one to open for which task.

## How the pieces fit

**Curriculum.** The JSON file is authored by hand and bundled in the mod. The mod loads and validates
it at startup. The backend validates the same file with the same rules and imports it into PostgreSQL
(`mineledger import-curriculum`); the import is idempotent and refuses destructive changes. The
database is a copy, never the source. Content is never written as Java or Python literals.

**Mod to backend.** The mod sends read-only `GET` requests over plain `java.net.http`, off the render
thread: `/health`, `/api/v1/meta` (version check), then the curriculum endpoints. Online mode is a
view-only browser and question preview. The API hides answer keys, so backend content is never graded
locally, never earns XP, and is never turned into local mastery models. The backend also has a
stateless `POST /api/v1/questions/{id}/grade`, which the mod does not call yet.

**Connection modes.** Settings offers Automatic (default), Online Required and Offline Practice, saved
in `<game dir>/mineledger/config.json`. Automatic falls back to offline practice only when deciding
fresh; a failure while backend content is on screen becomes "Connection lost" and waits for the
student. Online Required shows local content only after an explicit switch. Offline makes no network
calls. Details: `minecraft-mod/BACKEND_MODE.md`.

**Offline practice.** All grading, mastery, XP and Recharge happen locally. A concept is mastered after
two distinct correct questions and awards 20 XP once; the journal lab awards 10 XP per unique correct
question. Missed questions rotate back automatically. Recharge Practice replays a completed
assignment's full bank and writes nothing. Progress is JSON per profile at
`<game dir>/mineledger/progress/<uuid>.json`; it is labeled local XP and never uploaded.

**Authentication (backend only).** The API verifies access tokens from an external OpenID Connect
provider and maps them to rows in `users` with a `student` or `teacher` role. The mod has no sign-in
yet. Details: `backend/AUTHENTICATION.md`.

## Conventions

- Start with `git status --short`. Keep changes focused: no speculative refactors, dependencies or features.
- Accounting logic stays out of Minecraft classes and out of UI callbacks. Grade by stable `Account`
  identifiers and `BigDecimal`, never by display text.
- Accounting content must be real accounting.
- Do not change MineOS appearance, navigation, or the computer block's model, textures or
  registration unless asked.
- No HTTP on the render thread; apply results on the client thread and drop stale ones; no polling.
- Database changes go in a new Alembic revision. Never edit an applied one.
- Report compilation and tests separately from interactive GUI checks, and never claim a visual
  check that was not run.
- Update `AGENTS.md` when architecture, entry points, commands or constraints change.

## Commands

Mod, PowerShell from `minecraft-mod/` (needs Java 25; see `AGENTS.md` for this machine's `JAVA_HOME`):

```powershell
./gradlew.bat build test --console=plain   # compiles, validates the curriculum, runs JUnit
./gradlew.bat validateCurriculum
./gradlew.bat runClient                    # interactive testing only
```

Backend, PowerShell from `backend/` (Docker Desktop running):

```powershell
docker compose up -d --wait                # PostgreSQL on 127.0.0.1:5433
.\.venv\Scripts\alembic upgrade head
.\.venv\Scripts\mineledger import-curriculum
.\.venv\Scripts\python -m pytest           # uses the mineledger_test database only
.\.venv\Scripts\uvicorn mineledger_api.main:app --reload   # http://localhost:8000/docs
```

A change to the curriculum JSON or to anything in `shared/fixtures/` affects both suites; run both.

## Compatibility constraints

- Versions are pinned in `minecraft-mod/gradle.properties`. Minecraft 26.3 UI APIs differ from older
  releases, so inspect the installed classes before trusting examples from elsewhere.
- Curriculum IDs are permanent. Save files and database rows refer to them, and the importer refuses
  to drop or retype one.
- The Java loader and the Python port must accept and reject the same files, and both graders must
  agree. The shared fixtures enforce this; change both sides together.
- Changing a backend response shape incompatibly means bumping `API_VERSION` in `backend/.../api.py`,
  teaching the mod's compatibility check about it, and regenerating `shared/fixtures/api_responses/`.
- Save files (`progress/<uuid>.json`, schema 1) are never reset silently; corrupt or unsupported
  files are preserved.
- `runClient` uses a fixed development username and UUID. Do not randomize them, or testing picks a
  different save file.

## Security constraints

- A Minecraft UUID or username never proves identity to the backend.
- Roles live in the database and change only through the operator CLI. Never add an HTTP endpoint that
  writes a role, and never trust a role claim from a token or request.
- Tokens must be asymmetric-signed and are never logged. The backend stores no passwords.
- Nothing shipped in the mod jar may be a secret. `config.json` holds no credentials.
- Answer keys and explanations stay out of every question-retrieval response; tests check this.
- Secrets, including the database URL for any real deployment, come from the environment.
- These need a new, explicit request: mod-side sign-in, backend progress or XP sync, authoritative
  grading, LLM grading, AI question generation.
