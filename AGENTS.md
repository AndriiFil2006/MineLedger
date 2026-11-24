# MineLedger agent guide

## Start here; keep exploration small

- This repository contains the Fabric mod in `minecraft-mod/` (run Gradle there) and the standalone
  FastAPI/PostgreSQL backend in `backend/` (see `backend/README.md`). `shared/fixtures/` holds test
  fixtures both run. Exclude `backend/.venv/` and `.pytest-tmp/` from searches.
- Use this map to open only files relevant to the request. Start with `git status --short`;
  preserve unrelated work. Do not dump the source tree or reread every source file for each task.
- Search narrowly with `rg` and explicit paths. Exclude generated `build/`, `.gradle/`, and
  `run/` content from routine searches. Inspect caches or logs only to resolve a specific issue.
- This guide is a navigation aid, not a replacement for checking the code being changed.
  Consult `JOURNAL_ASSIGNMENT.md` only when assignment behavior or manual checks are relevant.
- Keep changes focused; no speculative refactors, dependencies, or extra features.
- Update this guide briefly if architecture, entry points, commands, or constraints change.
  Keep detailed history and test logs out of this file.

## Current state and boundaries

Minecraft Java 26.3; Fabric Loader 0.20.1; Fabric API 0.165.2+26.3; Loom 1.19-SNAPSHOT;
Java 25. `gradle.properties` and `build.gradle` are authoritative if versions change.

A native computer opens MineOS, whose MineLedger app has Courses, Assignments, and Progress.
Courses organizes Accounting 101 into three units/eight assignments; only 2.1 Journal Entries is
playable (legacy ID `inventory_fundamentals`). Seven others are Coming Soon with no exercises.
Independent journal practice is functional: exactly three fixed Minecraft scenarios,
one debit and one credit sharing an amount, deterministic feedback, retries, completion score,
and at most 30 learning XP per local profile. Account selectors are expandable dropdown lists
built from native buttons, not cycle buttons. Every MineOS/app window uses local 85% zoom and
shrinks further to fit. Measured content replaces scrolling, including expanded journal dropdowns.
Assignment 2.1 retains the existing multiple-choice mastery: credit purchases and paying suppliers,
three questions each, two distinct correct questions per concept, 20 XP once per concept (40 total).
Missed mastery questions automatically rotate until mastered; feedback offers only Continue, with
no immediate Try Again button. Earned credits never repeat. Original journal practice remains available.
Completed mastery assignments unlock Recharge Practice: review the full question bank with missed
questions requeued, no mastery/XP changes and no save writes. Its practice queue is transient.
Learn / Review Concept opens one original perpetual-inventory lesson for `inventory.purchases_on_credit`,
including in Recharge. It returns to the same exercise state and never awards XP or writes progress.

- Preserve MineOS's existing appearance and navigation unless the user requests a change.
- Do not alter computer geometry, textures, blockstates, or registration unless specifically requested.
- Keep accounting logic independent of Minecraft classes; keep all Screen/rendering code in `src/client`.
- Grade using stable `Account` identifiers and `BigDecimal`, never account display text.
  Correct answers and validation belong in the domain, not UI callbacks.
- Progress is local-profile JSON-backed: journal practice awards 10 XP per unique correct question;
  concept mastery awards 20 XP once per completed concept (two distinct correct questions by default).
  Both are separate from Minecraft XP and survive client restarts. Storage: runtime game directory
  `mineledger/progress/<uuid>.json`, schema 1. Preserve corrupt/unsupported files; never reset silently.
- Backend (Milestone 11A) is approved and independent: it imports curriculum JSON into PostgreSQL,
  serves it read-only under `/api/v1`, and grades multiple choice statelessly. The JSON stays the
  source of truth for curated content.
- Backend authentication (Milestone 13A, backend only): the API is an OAuth 2.0 resource server that verifies
  OIDC access tokens (PyJWT, asymmetric algorithms only) and maps them to `users` rows with a `student` or
  `teacher` role. Roles live in the database and change only through the operator CLI; never add an HTTP
  endpoint that writes a role, never trust a role claim, and never treat a Minecraft UUID/username as proof
  of identity. Curriculum GETs stay public. `MINELEDGER_ENV` is `development` (default: answers this
  computer only, public stateless grader on) or `production` (start-up checks, grader off); never weaken either.
- Sign-in from the game (Milestone 13C): Settings has Sign In / Sign Out. `SignInManager` runs Authorization
  Code with PKCE in the system browser against the provider the backend publishes at `/api/v1/auth/config`
  (Auth0 in development), receives the redirect on 127.0.0.1 ports 47821-47823 (registered at Auth0; do not
  change them casually), and counts a sign-in only after `GET /api/v1/users/me` succeeds. No client secret,
  no password in game, no token in logs, `config.json`, messages or `toString`; the access token stays in
  memory and only the refresh token is stored (`mineledger/session.json`). Tokens go only over https or to
  this computer. The auth package must stay free of Minecraft, progress and accounting classes, and must
  never touch local progress or XP. Sign-in is available only while Online; offline behavior is unchanged.
  The mod still does not submit answers or read server progress.
- Server-authoritative progress (Milestone 13B, backend only): authenticated students submit an option ID
  with an `Idempotency-Key`; the server grades, records the attempt, and owns credits, concept completion
  and XP (20 per concept, same rules as Java `ConceptProgressionService`, including concept order). Never
  accept a user ID, verdict, XP or completion from a client. Each submission locks the student's `users`
  row; primary keys and unique/check constraints back that up, so keep both. Assignment progress is derived,
  not stored. Recharge submissions are recorded but must never write credits or completions. The mod does
  not call any of this, and local progress is never migrated or uploaded.
- Connection modes (Milestones 11B/12): MineLedger Settings chooses Automatic (`auto`, default), Online
  Required (`backend`) or Offline Practice (`offline`), saved in runtime `mineledger/config.json` and applied
  without a restart. `ConnectionManager` owns the live state. Online is a view-only curriculum browser and
  question preview over async HTTP (GET only). It must never grade, award XP or touch mastery/progress, and
  backend content must not be converted into local mastery models (the API hides answer keys).
  Automatic falls back to offline practice only when deciding fresh (app opened, retry from offline); a
  failure while backend content is on screen becomes Connection lost and waits for the student. Online
  Required never shows local content without the explicit Switch to Offline Practice. Offline preference
  makes no network calls. Assignment/journal screens must stay independent of connection state.
  No HTTP on the render thread; apply results on the client thread and drop stale ones; no polling.
  Local XP is labeled local/practice XP and is never uploaded. Do not add backend grading, mod-side auth, learner
  progress/XP sync, or AI generation without a new user request.
- Do not add LLM grading, a course engine, or multiline journal entries without a new user request.
  Accounting principles remain real accounting.

## File map (paths below are relative to minecraft-mod/)

Curriculum is now JSON-driven: `src/main/resources/data/mineledger/curriculum/accounting_101.json`
is the sole content source. `curriculum/` in the main Java package contains loading/repository/validation
code. Old content classes are stable-ID lookup handles only; do not reintroduce Java question/lesson
literals. Read `CURRICULUM_ARCHITECTURE.md` for schema, references, backend import and migration policy.
`./gradlew.bat validateCurriculum` validates bundled data (or `--args="path.json"`); `build` includes it.

| Task | Read first |
| --- | --- |
| Assignment UI, dropdowns, sizing | `src/client/java/com/mineledger/client/screen/JournalEntryScreen.java` |
| Multiple-choice UI | `src/client/java/com/mineledger/client/screen/ConceptAssignmentScreen.java`; 600-unit logical canvas scaled to 85% or smaller to fit, no scrollbar; transforms rendering and mouse input together, without changing global GUI scale |
| Concept lessons / return state | Lesson content is in curriculum JSON; `education/lesson/` contains typed models/catalog and compatibility lookup. Client `ConceptLessonScreen`; `presentation/PracticeQuestionState` preserves selection/feedback. See `CONCEPT_LESSONS.md` and `ConceptLessonTest`. |
| Assignment presentation widgets | Same screen package: `ConceptProgressStripWidget` (empty/half/full mastery bars), `AssignmentTextPanel` (measured cards), `AnswerOptionButton` (wrapped radio choices), `AssignmentStyle` (white/red palette) |
| Shared zoom / GUI lifecycle | `ScaledAppScreen` owns viewport fitting, both render hooks, and mouse transforms for all windows; subclasses implement `extractWindowBackground` and call `fitWindow` after layout. `AppScreen` clears orphaned focus; `src/main/java/com/mineledger/presentation/WindowViewport.java` supplies tested coordinate math. |
| Mastery models, selection, progression, session store | `src/main/java/com/mineledger/accounting/mastery/` (`ConceptProgressionService`, `QuestionSelector`, `LearningSession`); content in curriculum JSON, original content class names remain lookup-only handles |
| Mastery tests / manual checks | `src/test/java/com/mineledger/accounting/mastery/ConceptMasteryTest.java`, `CONCEPT_MASTERY.md` |
| Recharge Practice | `src/main/java/com/mineledger/accounting/mastery/RechargePractice.java`; existing `ConceptAssignmentScreen` has a practice mode; tests in `RechargePracticeTest` and `LocalProgressTest` |
| Bug audit / outstanding risks | `BUG_AUDIT.md`; coordinate tests in `src/test/java/com/mineledger/presentation/WindowViewportTest.java` |
| Courses/Assignments/Progress tabs | `src/client/java/com/mineledger/client/screen/MineLedgerScreen.java` |
| Course hierarchy / routing / counts | `src/main/java/com/mineledger/education/` (`Course`, `Unit`, `CourseCatalog`, `CourseNavigation`, `CourseProgress`, `Accounting101`); `CourseBrowser.java` in client screen package; `COURSES.md` and `CourseHierarchyTest` |
| Dynamic assignment listing / actions | `education/AssignmentDirectory` supplies grouped entries and ID-based Start/Continue/Recharge selection to both routes; client `AssignmentRows` renders shared rows. Assignments tab uses a bounded native scroll list with fixed Practice / Lab and desktop actions; tests in `AssignmentDirectoryTest`. No assignment-specific GUI routing. |
| Desktop/app icon | `src/client/java/com/mineledger/client/screen/MineOsDesktopScreen.java`, `MineLedgerAppIcon.java` in the same directory |
| Right-click client entry | `src/client/java/com/mineledger/client/MineLedgerClient.java` |
| Profile loading / autosave lifecycle | `src/client/java/com/mineledger/client/JournalSession.java`, `MineLedgerClient.java` (shutdown retry) |
| JSON persistence / format / tests | `src/main/java/com/mineledger/progress/` (`ProgressRepository`, `JsonProgressRepository`, `ProfileLearningProgress`, `ProfileProgress`); `LOCAL_PROGRESS.md`; `src/test/java/com/mineledger/progress/LocalProgressTest.java` |
| Models, grader, XP deduplication | `src/main/java/com/mineledger/accounting/` (`AccountingQuestion`, `AccountingAnswer`, `AccountingResult`, `Account`, `AccountingGrader`, `JournalProgress`) |
| Fixed questions and explanations | Curriculum JSON `questions` table; `IntroJournalAssignment.java` is a handle to the standalone lab, not another question bank |
| Grading/progress tests | `src/test/java/com/mineledger/accounting/AccountingGraderTest.java` |
| Account labels / translations | `src/main/resources/assets/mineledger/lang/en_us.json` |
| Mod entry / protected blocks | `src/main/java/com/mineledger/MineLedger.java`, `block/` in the same package |
| Versions and build | `gradle.properties`, `build.gradle`, `src/main/resources/fabric.mod.json` |
| Manual acceptance steps and scoring details | `JOURNAL_ASSIGNMENT.md` |
| Backend setup, API, importer rules (paths relative to repo root) | `backend/README.md`; code in `backend/src/mineledger_api/` (`models`, `curriculum` = Python port of `CurriculumLoader`, `importer`, `grading`, `schemas`, `api`, `cli`); migrations in `backend/migrations/versions/` |
| Backend auth, roles, user CLI (paths relative to repo root) | `backend/AUTHENTICATION.md`; `backend/src/mineledger_api/auth.py` (`TokenVerifier`, `CurrentUser`/`StudentUser`/`TeacherUser` dependencies, `/users/me`), `users.py` (provisioning, role changes), `models.User`, migration `0002`; tests in `backend/tests/test_auth.py` (loopback fake identity provider, no real provider needed) |
| Game sign-in, tokens, loopback callback | `backend/AUTHENTICATION.md` (Auth0 setup in section 4, manual test in section 8); `src/main/java/com/mineledger/backend/auth/` (`SignInManager` state machine, `OidcHttpClient`, `LoopbackCallbackServer`, `Pkce`, `IdToken`, `RefreshTokenStore`, `AccountApi`); client `BackendRuntime.account()` and the Account section of Settings in `MineLedgerScreen`; tests in `src/test/java/com/mineledger/backend/auth/` (`SignInFlowTest` runs the whole flow over real HTTP against a stand-in provider) |
| Backend submissions, mastery, XP, Recharge, progress API | `backend/PROGRESS.md`; `backend/src/mineledger_api/progress.py` (rules, transaction, derived state), `progress_api.py` (routes), `models.QuestionAttempt`/`QuestionCredit`/`ConceptCompletion`, migration `0003`; tests in `backend/tests/test_progress.py`; `python -m tests.dev_idp` is a local-only identity provider for manual testing |
| Connection modes, Settings tab, HTTP client, remote browsing | `BACKEND_MODE.md`; `src/main/java/com/mineledger/backend/` (`BackendConfig` parse/migrate/save, `ConnectionManager` state machine and compatibility check, `BackendClient`, `BackendJson`, `RemoteCurriculum` DTOs, `RemoteCurriculumBrowser`); client `BackendRuntime`, `screen/RemoteCourseBrowser`, connection/Settings code in `MineLedgerScreen`; tests in `src/test/java/com/mineledger/backend/` (`FakeBackend` doubles and a loopback test server, no running backend needed) |
| Java client / API response contract | `shared/fixtures/api_responses/` (real GET bodies + `manifest.json`), checked by `backend/tests/test_api_contract.py` and parsed by the Java backend tests; regenerate from a running backend after intentional API or curriculum changes |
| Java/Python grading parity | `shared/fixtures/multiple_choice_grading.json`, run by `minecraft-mod/.../mastery/SharedGradingFixtureTest.java` and `backend/tests/test_grading_parity.py` |
| Java/Python curriculum validation parity | `shared/fixtures/curriculum_validation_cases.json`, run by `minecraft-mod/.../curriculum/SharedCurriculumValidationTest.java` and `backend/tests/test_curriculum_validation.py` |

## Build and verification

PowerShell, from `minecraft-mod/`:

```powershell
# Local machine: Java on PATH may be newer; use the installed Java 25 explicitly.
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-25.0.5.0.3'
$env:GRADLE_USER_HOME = 'C:/Users/andri/.gradle'
./gradlew.bat build test --console=plain
# Launch only when interactive testing is needed:
./gradlew.bat runClient
```

These machine paths are conveniences; use the available Java 25 and Gradle cache elsewhere.
`runClient` pins its offline username/UUID using `mineledger_dev_username`/`mineledger_dev_uuid`
in `gradle.properties`; do not randomize them or restart testing will select a different save file.
Respect sandbox permissions for cache access and downloads; do not bypass approval restrictions.
JUnit 4.13.2 is the only test dependency. Both journal and mastery tests run as part of `build`.
Reports: `build/reports/tests/test/index.html`. Output mod: `build/libs/mineledger-1.0.0.jar`.
Run relevant checks once after code changes; do not rebuild for documentation-only edits or repeat
successful checks without a reason. For version-specific UI APIs, inspect installed classes/sources
with Java 25 `javap`/`jar` before relying on examples from other Minecraft releases.
Distinguish compilation/tests from interactive GUI verification; never claim a visual check was run
unless it was. To test manually: computer -> MineOS -> MineLedger -> Assignments -> Open Journal Entries.

Backend, PowerShell from `backend/` (Python 3.13 venv in `.venv`; Docker Desktop must be running):

```powershell
docker compose up -d --wait            # PostgreSQL 17 on 127.0.0.1:5433 (+ mineledger_test DB)
.\.venv\Scripts\alembic upgrade head
.\.venv\Scripts\mineledger import-curriculum
.\.venv\Scripts\python -m pytest       # needs PostgreSQL; uses mineledger_test only
.\.venv\Scripts\uvicorn mineledger_api.main:app --reload   # Swagger: http://localhost:8000/docs
```

Backend settings are `MINELEDGER_*` environment variables, with a git-ignored `backend/.env` filling in any
that are unset (the developer's Auth0 values live there). pytest ignores both the file and any
`MINELEDGER_AUTH_*` variables. Never commit `backend/.env` or put its values in tracked files.

Editing curriculum JSON or the shared grading fixture affects both the Gradle build and the pytest suite;
run both. Add schema changes as new Alembic revisions, never by editing `0001`.
