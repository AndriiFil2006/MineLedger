# Backend mode (Milestone 11B): Minecraft ↔ FastAPI, read-only

The mod can now read curriculum from the FastAPI backend in `../backend/`. Offline mode is the default and
is unchanged. Backend mode is a **read-only preview**: it browses courses, units, assignments and concepts
and shows question prompts and choices. It does not grade, award XP, change mastery, or read or write
progress. Grading, XP and progress remain local and offline until a later milestone.

## Switching modes

The mod reads `<game directory>/mineledger/config.json` once at client startup (development client:
`minecraft-mod/run/mineledger/config.json`). The file is created with offline defaults on first launch.

```json
{
  "mode": "offline",
  "backend_url": "http://127.0.0.1:8000",
  "connect_timeout_ms": 3000,
  "request_timeout_ms": 5000
}
```

- `mode`: `"offline"` (default) or `"backend"`. Restart the client after changing it.
- `backend_url`: `http://` or `https://` base URL. Credentials, query strings and fragments are rejected.
  Prefer `127.0.0.1` over `localhost` (see the note in `../backend/README.md`).
- Timeouts: whole milliseconds, 100 to 60000.
- Every setting is optional. Unknown settings are rejected so a typo cannot go unnoticed.
- An invalid file never crashes the game and is never rewritten: the mod logs a warning, runs offline,
  and the MineLedger title bar shows `Offline (config error, see log)`.
- The file holds no secrets. The mod never has database credentials; only the backend talks to PostgreSQL.

## What each mode does

| | Offline (default) | Backend |
| --- | --- | --- |
| Courses tab | Bundled JSON curriculum, Start / Continue / Recharge | Backend courses → units → assignments → concepts → question preview |
| Assignments tab | Local assignment list and Journal Entries lab | Explains that graded work is offline only |
| Progress tab | Local profile progress | The same local progress, labeled as offline progress |
| Grading, mastery, XP, saves | Local, as before | None. Nothing is graded and no file is written |
| Network | Never. No HTTP client is created | `GET` requests to the configured backend only |
| Title bar status | `Offline` | `Connecting...`, `Connected to backend`, or `Backend unavailable` |

The mode is fixed for the session. If the backend fails, backend mode shows the error with **Retry**; it
never falls back to bundled content.

## Why backend content is not hydrated into the local models

`Course`, `Assignment`, `AccountingConcept` and `MultipleChoiceQuestion` are fully nested and require an
answer key and explanation for every question. The API pages the hierarchy across endpoints and
deliberately withholds answer keys. Building local models from it would mean inventing keys, and would let
backend questions reach `ConceptProgressionService` and earn local XP. So `RemoteCurriculum` holds one
typed record per response shape, reusing `AnswerOption`, `Assignment.Availability` and `ConceptLesson`
where the shapes genuinely match, and nothing in `com.mineledger.backend` can reach grading or progress.
All IDs are the backend's (and therefore the curriculum JSON's) stable IDs, unchanged.

## Code map (`src/main/java/com/mineledger/backend/`, no Minecraft classes)

| Class | Role |
| --- | --- |
| `BackendConfig` | Parses and validates `config.json`; invalid means offline plus a reported problem |
| `CurriculumSource` | `Local` or `Remote`, selected once from the config; offline never builds a client |
| `CurriculumApi` | The seven asynchronous read operations |
| `BackendClient` | `java.net.http.HttpClient` implementation: two daemon worker threads, connect and request timeouts plus an overall deadline, no redirects, 4 MiB response limit |
| `BackendJson` | Strict JSON → `RemoteCurriculum` records; missing or mistyped fields are errors; unknown fields are ignored |
| `BackendException` | `UNREACHABLE`, `TIMEOUT`, `HTTP_STATUS`, `MALFORMED_RESPONSE`, with display-safe messages |
| `RemoteCurriculumBrowser` | Navigation state, cross-endpoint consistency checks, retry, and stale-response protection |

Client side: `client/BackendRuntime` loads the config at startup; `MineLedgerScreen` owns one
`RemoteCurriculumBrowser` per screen instance; `screen/RemoteCourseBrowser` draws it.

**Threading.** Screens only call `RemoteCurriculumBrowser`, which returns immediately. HTTP and JSON parsing
run on the `MineLedger-Backend-*` threads. Results are handed to `Minecraft::execute` and applied on the
client thread. Every load takes a ticket; a response is dropped if a newer load started, the user went
back, or the screen was removed (`MineLedgerScreen.removed()` closes the browser).

**Consistency.** A response must carry the ID that was requested and agree with its parent (a unit's
course, an assignment's unit, each concept's question list against the questions endpoint). An
assignment preview is assembled from the assignment, each of its concepts and its questions, and is
shown only if all of them load and agree.

## Tests

`src/test/java/com/mineledger/backend/`: configuration, JSON mapping, the real HTTP client against a
loopback test server (success, 404, 500, 503, redirects, malformed and non-UTF-8 bodies, timeouts, stalled
bodies, server down), and browser behavior (navigation, stale responses, retry, inconsistent content,
no progress changes). They use the real backend responses in `../shared/fixtures/api_responses/`;
`backend/tests/test_api_contract.py` fails if the API stops returning exactly those bodies.

## Manual verification (Windows PowerShell)

```powershell
# 1-4. Backend (Docker Desktop running)
cd D:\work\minecraft_mods\accounting\backend
docker compose up -d --wait
.\.venv\Scripts\alembic upgrade head
.\.venv\Scripts\mineledger import-curriculum
.\.venv\Scripts\uvicorn mineledger_api.main:app --port 8000     # keep this window open
# In a second window: should print status ok / database ok
Invoke-RestMethod http://127.0.0.1:8000/health

# 5. Enable backend mode for the development client
cd D:\work\minecraft_mods\accounting\minecraft-mod
New-Item -ItemType Directory -Force run\mineledger | Out-Null
Set-Content run\mineledger\config.json -Encoding ascii -Value '{ "mode": "backend", "backend_url": "http://127.0.0.1:8000" }'

# 6. Launch
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-25.0.5.0.3'
$env:GRADLE_USER_HOME = 'C:/Users/andri/.gradle'
./gradlew.bat runClient
```

7. In a world: right-click the computer → MineOS → MineLedger. The title bar shows `Connected to backend`.
   Courses lists **Accounting 101 — Financial Accounting** (3 units). Open it → **Unit 2: Recording
   Transactions** → **Assignment 2.1: Journal Entries** shows `Preview · Available · 2 concepts`; the other
   two are Coming Soon and disabled. Preview shows both concepts (the first with its lesson title) and
   **Preview Questions (6)**; step through all six with Next / Previous. No option is marked correct and
   nothing can be selected. The uvicorn window logs each `GET`.
8. Stop uvicorn (Ctrl+C) and click a unit or **Reload**: the status becomes `Backend unavailable` with
   "Cannot reach the backend" and **Retry**; no bundled content appears. Start uvicorn again and click
   Retry: the content loads and the status returns to `Connected to backend`. Opening MineLedger while the
   server is down shows the same error immediately, without freezing the game.
9. Close the game, then switch back:
   `Set-Content run\mineledger\config.json -Encoding ascii -Value '{ "mode": "offline" }'` and relaunch.
10. The title bar shows `Offline`. Assignment 2.1 shows the same progress as before the backend session;
    answer a mastery question, open the lesson, run Recharge Practice (no XP change), and check the
    Progress tab. `run\mineledger\progress\<uuid>.json` was not modified during the backend session.

## Known limitations

- Read-only: no grading, XP, mastery, lessons screen or journal lab in backend mode; no progress sync.
- The mode and URL are read at startup only; there is no settings screen.
- No authentication, and plain HTTP is intended for a backend on the same machine.
- No caching: each navigation step requests fresh data, and a re-import on the server between steps can be
  reported as inconsistent content until the course list is reloaded.
- The Assignments tab has no backend list; backend assignments are reached through Courses.
- A long assignment or question shrinks to fit the window, like the other MineLedger views.
