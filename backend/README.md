# MineLedger API (Milestone 11A: backend foundation)

A FastAPI + PostgreSQL service that imports the curated MineLedger curriculum and serves it over REST,
with stateless, deterministic multiple-choice grading. It runs on its own; **the Minecraft mod does not
talk to it yet**.

- **Source of truth:** `../minecraft-mod/src/main/resources/data/mineledger/curriculum/accounting_101.json`.
  The database holds imported copies of it. No questions are defined in Python.
- **In scope:** curriculum tables, an idempotent importer, read-only curriculum endpoints, and
  `POST /api/v1/questions/{id}/grade`.
- **Not in scope yet:** authentication, learner progress, XP, sync, AI generation, Minecraft HTTP integration.
- **Stack:** Python 3.12 or 3.13 (verified on 3.13.0), FastAPI, Pydantic v2, SQLAlchemy 2.x, Alembic,
  psycopg 3, pytest, and PostgreSQL 17 in Docker. `requirements-lock.txt` records the exact versions that were tested.

## Setup (Windows PowerShell, from `backend/`)

### 1. Python virtual environment

```powershell
py -3.13 -m venv .venv
.\.venv\Scripts\Activate.ps1          # if blocked: Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
python -m pip install --upgrade pip
pip install -e ".[dev]"               # reproducible: pip install -c requirements-lock.txt -e ".[dev]"
```

### 2. Start PostgreSQL

Start Docker Desktop first, then:

```powershell
docker compose up -d --wait           # postgres:17 on localhost:5433, user/password mineledger
docker compose ps                     # STATUS should show (healthy)
```

When the volume is first created, an init script also creates the `mineledger_test` database used by
pytest. Other useful commands:

- `docker compose stop` stops the server and keeps the data.
- `docker compose down -v` deletes the data volume, including the imported curriculum.

Settings come from environment variables, and the defaults match `docker-compose.yml`. See `.env.example`.
To point at a different server:

```powershell
$env:MINELEDGER_DATABASE_URL = 'postgresql+psycopg://user:pass@host:5432/mineledger'
```

### 3. Apply migrations

```powershell
alembic upgrade head                  # creates the curriculum tables
alembic current                       # shows 0001 (head)
alembic check                         # "No new upgrade operations detected." = models match migrations
```

### 4. Import the curriculum

```powershell
mineledger validate-curriculum                 # validates only, no database needed
mineledger import-curriculum --dry-run         # runs every check, then rolls back
mineledger import-curriculum                   # imports bundle "accounting_101"
mineledger import-curriculum                   # again: "no changes; database already matches the file"
```

Options: `--file path\to\bundle.json`, `--bundle-id <id>` (default: the file name), `--database-url <url>`.

Exit codes:

| Code | Meaning |
| --- | --- |
| 0 | OK |
| 1 | Database error |
| 2 | Invalid curriculum |
| 3 | Import conflict |

Codes 1–3 always mean nothing was written.

### 5. Start FastAPI

```powershell
uvicorn mineledger_api.main:app --reload --port 8000
```

### 6. Try the endpoints in Swagger

Open <http://localhost:8000/docs>. Choose an endpoint, then **Try it out** → **Execute**. For example:

- `GET /api/v1/assignments/{assignment_id}/questions` with `inventory_fundamentals`.
- `POST /api/v1/questions/{question_id}/grade` with `inventory.shovels` and the body
  `{"selected_option_id": "shovels.inventory_payable"}`. This returns `correct`. Try `shovels.cash`
  (`incorrect`) and `"nope"` (`invalid_option`).

ReDoc is at `/redoc`. The raw schema is at `/openapi.json`.

### 7. Run the tests

```powershell
python -m pytest          # PostgreSQL must be running; uses the separate mineledger_test database
```

The suite migrates the test database down to base and back up to head, and truncates tables between
tests. It refuses to run against a database whose name doesn't end in `_test`. To use another server:

```powershell
$env:MINELEDGER_TEST_DATABASE_URL = '...'
```

The Java side of the grading parity check runs in the Minecraft build (`./gradlew.bat build` in
`../minecraft-mod`).

## API

| Method and path | Returns |
| --- | --- |
| `GET /health` | `{"status":"ok","database":"ok"}`, or 503 with `degraded`/`unavailable` if the database is down |
| `GET /api/v1/courses` | Course summaries with ordered `unit_ids` |
| `GET /api/v1/courses/{id}` | Course with its ordered units (number, title, `assignment_ids`) |
| `GET /api/v1/units/{id}` | Unit with ordered assignments (availability, `concept_ids`) |
| `GET /api/v1/assignments/{id}` | Assignment with concepts (required correct answers, question count, `lesson_id`) |
| `GET /api/v1/concepts/{id}` | Concept with ordered `question_ids` and its full lesson, or `null` |
| `GET /api/v1/assignments/{id}/questions` | Multiple-choice questions in concept order, with options as `{id, text}`; `[]` for Coming Soon |
| `POST /api/v1/questions/{id}/grade` | Body `{"selected_option_id": "..."}`. Returns `status` (`correct`, `incorrect` or `invalid_option`), `correct`, `valid` and `feedback` |

Error behavior:

- Unknown IDs return 404 with `{"detail": "<Type> not found: <id>"}`.
- A malformed body, or grading a `journal_entry` question, returns 422.
- A missing, null, unknown or foreign option ID returns 200 with `invalid_option`, exactly like the Java client.

**Hidden answer keys.** Question retrieval never includes the correct option, an `is_correct` flag,
journal answer keys, or question explanations. The explanation is only returned as grading feedback for a
valid selection. Tests check this recursively for every retrieval endpoint and for the OpenAPI schemas.

**Stateless grading.** Grading reads one question and writes nothing: no attempts, credits or XP.
Because it's stateless and unauthenticated, a client could try all four options. That's acceptable
here; authoritative, rewarded grading belongs to a later milestone with learner state.

## Design notes

### Tables

| Table | Notes |
| --- | --- |
| `curriculum_bundles` | One row per imported file: schema version, SHA-256, source, `imported_at` |
| `courses`, `units`, `assignments`, `concepts` | Text-ID primary keys, parent foreign keys and `sort_order` |
| `questions` | Both types; a check constraint enforces owner and answer-key shape per type |
| `answer_options` | Primary key `(question_id, option_id)` |
| `journal_labs`, `journal_account_choices` | The standalone journal lab and its account choices |
| `concept_lessons`, `lesson_worked_examples` | One lesson per concept |

How stable IDs and relationships are kept:

- JSON IDs are the primary keys, unchanged.
- Reference arrays become `sort_order` columns (deferrable unique constraints allow reordering).
- Every curated row carries its owning `bundle_id`.
- A question's `correct_option_id` has a deferred composite foreign key to its own options, so the
  database itself rejects a key that points at another question's option.
- A concept's `lesson_id` is derived from the unique `concept_lessons.concept_id`.

### Validation

`mineledger_api/curriculum.py` ports `CurriculumLoader.java` and the Java domain constructors rule by
rule. It checks:

- duplicate fields and IDs, unknown fields, types and integers;
- references, ownership and parent links, and orphans;
- each question has four unique options (normalized like Java) and exactly one correct option;
- bank size versus required correct answers;
- availability, unit order and lesson rules;
- journal accounts and amounts, using Java `BigDecimal` grammar and the 2-decimal and 32-character limits.

The supported account list is checked against the Java `Account` enum.

### Importer (`mineledger_api/importer.py`)

It runs in one transaction, guarded by an advisory lock:

- Rows are matched by ID. A repeated import is a no-op, and an edit updates only the changed rows.
- New IDs are inserted.
- Changed answer keys are reported as warnings.
- It refuses, and writes nothing, if:
  - an ID belongs to another bundle;
  - a previously imported entity or option is missing from the file;
  - a question's type changes.
- Retiring IDs needs an explicit learner-progress migration policy, not a silent delete.
- Only ID-less owned details (lesson worked examples, journal account choice lists) are replaced.

### Grading parity

`../shared/fixtures/multiple_choice_grading.json` lists correct, incorrect and invalid cases, covering
every bundled option plus null, empty, unknown, foreign, wrong-case, padded, answer-text and position
inputs. Three things run it:

- the Python grader (`tests/test_grading_parity.py`);
- the HTTP endpoint (the same test file);
- the Java `MultipleChoiceGrader` (`minecraft-mod/src/test/.../SharedGradingFixtureTest.java`).

Both sides also check that every bundled option has a case. Both sides also feed deliberately broken
graders through the fixture (case-insensitive, trimming, unknown-as-incorrect, and others) and require
each one to be caught.

### Validation parity

`../shared/fixtures/curriculum_validation_cases.json` describes 48 files: 6 valid controls and edge
cases, and 42 invalid ones. They're expressed as small edits to the real curriculum, plus raw malformed
files. Python (`tests/test_curriculum_validation.py`) and the mod's real loading path
(`SharedCurriculumValidationTest.java`) must both accept or reject each file, for the expected reason.

### Concurrency and dry run

`tests/test_import_safety.py` checks that:

- two simultaneous imports are serialized by the advisory lock (PostgreSQL reports the second one waiting);
- dry run catches deferred-constraint violations.

Control tests switch each protection off and show the failure it prevents.

### Layout

| Path | Contents |
| --- | --- |
| `src/mineledger_api/` | App code: `models`, `curriculum`, `importer`, `grading`, `schemas`, `api`, `cli` |
| `migrations/` | Alembic environment and versions |
| `tests/` | pytest suite |
| `docker/initdb/` | Creates the test database |
