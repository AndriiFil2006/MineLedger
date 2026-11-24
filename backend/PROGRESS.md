# Server-authoritative student progress (Milestone 13B)

Signed-in students can submit multiple-choice answers to the backend, which grades them, records
them, and owns mastery, concept completion and XP. The Minecraft mod does not use any of this yet;
its local practice, local XP and local Recharge are unchanged.

## 1. What the client may say, and what the server decides

A submission carries three things: the question ID (path), the chosen option ID (body) and an
idempotency key (header). The request body rejects every other field.

| Decided by the server | Source |
| --- | --- |
| Who the student is | The verified access token (`StudentUser`, see `AUTHENTICATION.md`) |
| The correct answer and the explanation | `questions` in PostgreSQL |
| Whether the question may be answered now | Assignment availability and concept order |
| Whether a credit is earned | `question_credits` |
| Whether the concept is completed, and the XP | `concept_completions` and `CONCEPT_COMPLETION_XP` (20) |

The rules are the ones the Java client applies locally (`ConceptProgressionService`):

- A **credit** is one distinct question answered correctly. Answering it again earns nothing.
- A **concept completes** when its credits reach `required_correct_answers` (2 of 3 in the bundled
  curriculum) and pays 20 XP once. A mistake never removes a credit.
- Concepts **unlock in order** within an assignment. A completed concept can still be answered, for
  nothing.
- An **invalid option** is recorded but does not count as an attempt at the question.

## 2. Tables (migration `0003`)

| Table | Key | Purpose |
| --- | --- | --- |
| `question_attempts` | `id` UUID; unique `(user_id, idempotency_key)` | Append-only history of every processed submission, graded or Recharge. Also the idempotency record |
| `question_credits` | primary key `(user_id, question_id)` | One row per distinct question a student has answered correctly |
| `concept_completions` | primary key `(user_id, concept_id)` | One row per completed concept, with the XP paid |

Every row references `users.id`. Questions and concepts are referenced by their stable curriculum IDs
through foreign keys, so curriculum that progress depends on cannot be deleted.

**Assignment progress has no table.** Status, completed-concept count, the current concept and XP per
assignment are computed from the two reward tables on each request, so they cannot drift from them.
Total XP is the sum of `concept_completions.xp_awarded`.

Integrity enforced by the database itself, independent of application code:

- One credit per student per question, and one completion per student per concept (primary keys).
- One attempt per student per idempotency key (unique constraint).
- One attempt can pay for at most one credit and one completion (unique `attempt_id`).
- Check constraints on `question_attempts`: a credit needs a graded, correct attempt; a completion
  needs a credit; XP needs a completion; XP is never negative. A rewarded Recharge row cannot be stored.

The amount of XP is stored with each completion, so changing `CONCEPT_COMPLETION_XP` later affects only
future completions. Downgrading past `0003` drops these three tables and the progress in them.

## 3. Endpoints

All need a student's bearer token. Teachers get 403. None accepts a user ID.

| Method and path | Purpose |
| --- | --- |
| `POST /api/v1/questions/{id}/submissions` | Official submission. Header `Idempotency-Key`, body `{"selected_option_id": "..."}` |
| `POST /api/v1/questions/{id}/recharge-submissions` | Recharge Practice. Same header and body |
| `GET /api/v1/progress` | Total XP, completed concept IDs, and progress in every available assignment |
| `GET /api/v1/progress/assignments/{id}` | One assignment, concept by concept (`correct_answers` of `required_correct_answers`: 0/2, 1/2, 2/2) |
| `GET /api/v1/progress/attempts` | The caller's attempts, newest first. `limit` (1–100, default 50), `offset`, optional `question_id` |

A submission returns the verdict (`status`, `correct`, `valid`, `feedback`), what this attempt earned
(`credit_earned`, `concept_completed`, `xp_awarded`), `replayed`, `total_xp`, and the assignment's
updated progress.

| Status | Meaning |
| --- | --- |
| 200 | Processed and recorded. An invalid option is a 200 with `status: "invalid_option"` |
| 401 / 403 / 503 | Not signed in / disabled or not a student / authentication not configured |
| 404 | Unknown question (or assignment) |
| 409 | Assignment not available, concept locked, or Recharge not unlocked. The message is the same whether the answer was right or wrong |
| 422 | Malformed request, a journal-entry question, a missing or malformed idempotency key, or a key reused for a different submission |

Refused requests (4xx) record nothing and do not consume the idempotency key.

### Idempotency

Send a fresh random `Idempotency-Key` (16–128 characters of `A-Z a-z 0-9 _ -`; a UUID is ideal) with
each new answer. If the response is lost, send the **same key with the same request**: the stored
result is returned with `replayed: true`, and nothing is recorded or rewarded again. A replay reports
the original verdict and rewards, with current progress.

The same key with a different question, option or endpoint is rejected with 422. Keys are scoped to
the user and are kept for as long as the attempt row exists.

### Concurrency

Each submission runs in one transaction that first locks the student's `users` row
(`SELECT ... FOR UPDATE`). One student's submissions therefore run one at a time, and each sees the
credits of the one before; different students do not wait for each other. If that lock were ever
bypassed, the constraints in section 2 still make a duplicate credit, completion or XP award fail.

### Recharge

Recharge grades the answer and returns the explanation. It is recorded with `mode: "recharge"` and can
never create a credit or a completion; the code path does not write those tables and the database
would refuse the row. It opens only after the student has completed the question's assignment. That
matches the mod's local rule and means Recharge cannot be used to look up answers before earning
official credit.

## 4. Trying it through FastAPI

Authentication needs an identity provider. For local testing there is a throwaway one (never for
deployment): it signs tokens with a key that exists only while it runs.

PowerShell, from `backend/`, with Docker Desktop running:

```powershell
docker compose up -d --wait
.\.venv\Scripts\alembic upgrade head
.\.venv\Scripts\mineledger import-curriculum
```

Terminal 1, leave it running. It prints two settings and one access token per name:

```powershell
.\.venv\Scripts\python -m tests.dev_idp alex sam
```

Terminal 2:

```powershell
$env:MINELEDGER_AUTH_ISSUER   = 'http://127.0.0.1:9011'
$env:MINELEDGER_AUTH_AUDIENCE = 'https://api.mineledger.test'
.\.venv\Scripts\uvicorn mineledger_api.main:app --port 8000
```

Terminal 3. Paste Alex's token, then submit:

```powershell
$token = '<access token for alex>'
$api   = 'http://127.0.0.1:8000/api/v1'
function Submit($question, $option, $key = [guid]::NewGuid().ToString(), $kind = 'submissions') {
    Invoke-RestMethod -Method Post "$api/questions/$question/$kind" `
        -Headers @{ Authorization = "Bearer $token"; 'Idempotency-Key' = $key } `
        -ContentType 'application/json' -Body (@{ selected_option_id = $option } | ConvertTo-Json)
}

Submit inventory.shovels shovels.cash                          # incorrect, 0/2
Submit inventory.shovels shovels.inventory_payable             # correct, credit_earned, 1/2
Submit inventory.shovels shovels.inventory_payable             # correct again, no new credit
Submit inventory.books_discount books.inventory_payable        # concept_completed, xp_awarded 20
Submit payables.shovels_settlement settlement.payable_cash
Submit payables.books_partial partial.payable_cash             # assignment completed, total_xp 40
Submit inventory.lanterns_delivery lanterns.inventory_payable -kind recharge-submissions   # no rewards

Invoke-RestMethod "$api/progress" -Headers @{ Authorization = "Bearer $token" } | ConvertTo-Json -Depth 6
Invoke-RestMethod "$api/progress/attempts?limit=5" -Headers @{ Authorization = "Bearer $token" } | ConvertTo-Json -Depth 4
```

To see idempotency, pass the same key twice (`Submit inventory.shovels shovels.cash -key 'my-retry-key-000001'`):
the second response has `replayed: true`. Change the option with that key and you get 422.
`Invoke-RestMethod` raises on 4xx; the message is in the error's response body.

Swagger at <http://127.0.0.1:8000/docs> works too: **Authorize** with the token, then fill in
`Idempotency-Key` on the submission endpoints. Restart uvicorn and `GET /progress` returns the same
progress. Alex's rows stay in the development database; `mineledger list-users` shows the account.

Automated: `.\.venv\Scripts\python -m pytest tests/test_progress.py`.

## 5. Supabase compatibility

Nothing here has been connected to Supabase. The schema and code use only standard PostgreSQL: no
extensions, UUIDs generated in Python, ordinary constraints, row locks and transactions. Migrations
`0001`–`0003` are plain Alembic operations and need no changes.

What moving to Supabase will need:

- **Connection string.** Set `MINELEDGER_DATABASE_URL` to the Supabase URL with the
  `postgresql+psycopg://` scheme and `?sslmode=require`.
- **Which connection.** Use the direct connection or the session-mode pooler for the API, Alembic
  and the importer. The transaction-mode pooler (port 6543) does not support prepared statements,
  which psycopg uses automatically; using it would need `prepare_threshold=None` on the engine. The
  row lock and the importer's advisory lock are both transaction-scoped and work in either mode.
- **Close the Data API.** Supabase exposes tables in the `public` schema through its auto-generated
  REST API, and row-level security is not enabled on tables created by migrations. Left as is, the
  `questions` answer keys and every student's progress would be readable with the project's public
  key. Before any data goes there, either disable the Data API for the project or enable row-level
  security on every table with no policies (the backend connects as the table owner and is unaffected).
  This is the one step that must not be skipped.
- **Authentication stays in FastAPI.** Supabase Auth is not used and `users` remains the only user
  table.
- `docker-compose.yml` and `docker/initdb/` are local conveniences only; nothing in the application
  depends on them. Tests need a database whose name ends in `_test`.

## 6. What Minecraft integration will need

None of this is built. In rough order:

1. **Sign-in** (design in `AUTHENTICATION.md`, section 6): a chosen identity provider, the device
   authorization flow, token storage and refresh, and HTTPS for every authenticated call.
2. **POST support in `BackendClient`**, which is GET-only today, with the bearer token and the
   `Idempotency-Key` header, still off the render thread.
3. **A pending-submission record.** The client must generate the key before sending, keep it with the
   chosen option until the server answers, and retry with the same key after a timeout. A timeout
   must never be treated as "not processed", and the answer must not be graded locally while a
   submission is in doubt.
4. **An online assignment screen** driven by server state: question content from
   `GET /assignments/{id}/questions`, the next question chosen from `credited_question_ids` and
   `attempted_question_ids`, feedback and progress bars from the submission response. Server content
   must still never be converted into local mastery models, since it has no answer keys.
5. **Separate XP displays.** Server XP is official; local XP stays local practice. There is no
   migration of local progress to the server, and none should happen silently.
6. **Offline behavior.** Decide what a signed-in student sees when the server is unreachable. Local
   practice can continue, but it cannot earn official credit later.
7. **Handling 409.** A locked concept or unavailable assignment means the client's view is stale; it
   should reload progress rather than retry.
8. **An API version signal.** `/meta` still reports `api_version` 1 because nothing existing changed.
   The client will need a way to tell that a server supports submissions.

## 7. Limitations

- **The public grading endpoint is an answer oracle.** `POST /api/v1/questions/{id}/grade` is
  unchanged, unauthenticated and stateless, so anyone can try all four options and read the
  explanation, then submit the right one officially. It must be removed, put behind authentication
  or limited to Recharge-eligible students before real students use official submissions. It was
  left alone here because the instruction was not to break it.
- **Official submissions can also be brute-forced**, in at most four attempts per question, because
  there is no attempt limit (the Java client has none either). Every attempt is recorded, so it is
  visible; nothing currently acts on it.
- **Completed concepts reveal answers.** After completion, a student can answer the concept's
  remaining question freely. This matches the local client.
- **No rate limiting** in the application.
- **One identity provider is still not chosen**; everything was tested with the local stand-in.
- **Multiple choice only.** Journal-entry questions cannot be submitted.
- **No teacher access** to student progress, and no classes or rosters.
- **No deletion or reset of a student's progress**, and no retention policy for attempts.
- **Removing a question or concept from the curriculum** is already refused by the importer; with
  progress rows referencing them, the database now refuses it as well. Retiring content will need an
  explicit migration.
- **Attempt history uses limit/offset**, which is fine at this size and would want a cursor for very
  long histories.
