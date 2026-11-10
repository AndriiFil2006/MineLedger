# Data-driven curriculum, schema 1

## Audit and migration

Previously, `Accounting101`, `InventoryCreditAssignment`, `SupplierPaymentConcept`,
`IntroJournalAssignment` and `IntroConceptLessons` constructed all curated content in Java.
Courses owned ordered units, units owned ordered assignments, assignments owned ordered concepts,
and concepts owned questions. Lessons were indexed by concept ID. The standalone journal lab was
separate from course mastery. Grading used stable option/account IDs, never option positions.
Recharge reviewed the original multiple-choice bank without mutating progress.

The migration exported the compiled objects before removing literals. Three SHA-256 fingerprints
capture the entire old course graph, standalone journal bank and lesson. Tests compare the loaded
objects to those fingerprints, including all prompts, answers, options, explanations and relationships.
No accounting corrections or new questions were introduced.

Migrated: **1 course, 3 units, 8 assignments (1 available, 7 Coming Soon), 2 concepts,
6 multiple-choice questions, 3 journal-entry questions, 1 lesson, and 1 separate practice lab**.
All pre-existing entity/option/account identifiers are preserved. `intro_journal_practice` is the
new explicit container ID for the existing lab; its three saved question IDs are unchanged.

## Resource location and layout

The authoritative content is UTF-8 JSON in:

`src/main/resources/data/mineledger/curriculum/accounting_101.json`

`data/<namespace>/` is Minecraft's non-rendering data convention. The bundle is packaged in the
mod jar and read through the classloader, not from `assets/` and not from player storage. This
milestone does not implement datapack overrides or resource reloads: edits require a rebuild/restart.
One normalized bundle avoids a separate file per question. It can already contain multiple courses
and hundreds of questions. A manifest for multiple bundles can be added later if warranted.

There is no parallel Java question bank. The old public content classes are lookup-only compatibility
handles into `CurriculumContent`; existing callers and tests resolve the same shared objects.
`CurriculumContent` loads once; the client initializer forces validation before registering UI entry
callbacks. Missing or invalid required resources fail initialization with source/entity/field details.
There is no silent empty-bank or hardcoded-content fallback.

## JSON contract

The root has `schema_version: 1` and arrays `courses`, `units`, `assignments`, `concepts`, `questions`,
`lessons`, `labs`. Each entity has a nonblank unique `id`. Definitions are normalized; relationships
are stable string references. Array positions define presentation order only, never identity.

| Entity | Fields besides id |
| --- | --- |
| Course | `title`, `description`, ordered `unit_ids` |
| Unit | `parent_course_id`, positive `order`, `title`, `description`, ordered `assignment_ids` |
| Assignment | `parent_unit_id`, `title`, `description`, `availability`, ordered `concept_ids` |
| Concept | `assignment_id`, `title`, `description`, positive `required_correct_answers`, optional/null `lesson_id`, ordered `question_ids` |
| Multiple-choice question | `concept_id`, `type: "multiple_choice"`, `prompt`, four `answer_options: [{id,text}]`, `correct_answer_id`, `explanation`, optional string `difficulty` |
| Journal-entry question | `lab_id`, `type: "journal_entry"`, `prompt`, `debit_accounts`, `credit_accounts`, `correct_debit_account`, `correct_credit_account`, string decimal `correct_amount`, `explanation`, optional string `difficulty` |
| Lesson | `concept_id`, `title`, `learning_objective`, `explanation`, `accounting_rules`, `worked_examples`, optional `common_mistakes`, optional `takeaway` |
| Standalone journal lab | `title`, ordered `question_ids` |

Lesson worked examples have `title`, `scenario`, `calculation`, `debit_entry`, `credit_entry`, and
`explanation`. Missing common mistakes become an empty list; missing takeaway becomes empty text.
Option IDs are scoped to their question and must be unique within it. Account IDs match the existing
`Account` enum names. A journal answer still has exactly one debit and one credit sharing an amount.
No essay, compound journal, generated-question or other future question type is accepted.

`availability` is exactly `available` or `coming_soon`. Available assignments require concepts;
Coming Soon assignments have none. Concepts must have at least as many distinct supported questions
as their required correct-answer count. There is no assumption of 30 concepts or questions.

## Loading and validation boundary

- `CurriculumRepository` provides a resolved immutable `Curriculum`.
- `JsonCurriculumRepository` reads either the bundled resource or a supplied file, with strict UTF-8
  decoding and a 16 MiB limit. It never reads or writes player progress.
- `CurriculumLoader` performs strict parsing and resolves tables into existing domain models. It
  rejects duplicate fields and IDs, unknown fields/types, malformed required values, fractional order
  or mastery counts, duplicate/multiply-owned references, missing references, incorrect parents,
  invalid answer keys, ambiguous options, undersized banks, and unreferenced content.
- Existing domain constructors provide the existing accounting/option invariants. A multiple-choice
  answer key identifies exactly one of four unique options. A journal key must remain answerable by
  the existing positive currency input format. No reflection or polymorphic deserialization is used.
- The resolved object retains optional difficulty metadata without changing grading. Lesson lookup
  uses the validated concept association; the GUI never copies lesson content into questions.

## Player progress compatibility

Curriculum schema 1 and progress schema 1 are independent formats. Progress remains under the
runtime game directory's `mineledger/progress/<profile-uuid>.json`. It is not bundled curriculum.

The assignment ID remains `inventory_fundamentals`; concepts remain `inventory.purchases_on_credit`
and `payables.cash_payments`. All six mastery question IDs, three journal IDs, lesson ID, course/unit
IDs and 24 option IDs are retained. Saved credits/reward IDs therefore resolve without disk migration.
No saves are rewritten when loading curriculum. Existing tests load pre-hierarchy schema-1 progress,
retain partial credits, complete it without duplicate XP, and run Recharge without modifying the save.
Unknown stored IDs retain the existing warning/read-only protection instead of silently resetting.

## Future Python/PostgreSQL/API path

Python can load this exact file with its standard UTF-8 JSON reader. Import the normalized tables
using text IDs as keys and parent IDs as foreign keys. Reference arrays become ordered associations
with `sort_order`; never replace stable IDs with array offsets. Store question options under their
question ID plus option ID. Validate references, answer membership and uniqueness before committing
the import in one database transaction. Keep learner credits/submissions/rewards in separate tables.

A future REST-backed `CurriculumRepository` can hydrate the same typed models from prefetched DTOs.
Do not put blocking HTTP calls in rendering callbacks. When the backend becomes authoritative,
submission/grading and concept/reward decisions currently made by `MultipleChoiceGrader` and
`ConceptProgressionService` should become server endpoint operations; `ProfileLearningProgress`'s
local repository will become a progress client/cache. Native screens should continue presenting
state and IDs. Recharge must remain a non-rewarding practice operation on the authoritative server.
No API client, database, server or networking was introduced here.

Bump `schema_version` for incompatible field/relationship/type changes and implement an explicit
migration/import path before accepting a new version. Schema version is not a license to rename
entities: preserve IDs for unchanged concepts/questions. A future separate content revision should
track edits independently of schema changes. Changes to required mastery counts or retired IDs need
an explicit learner-progress migration policy, not silent credit deletion. Update migration
fingerprints only after intentional reviewed content edits, never to hide an accidental answer change.

## Validation commands

From `minecraft-mod/`, with Java 25:

```powershell
./gradlew.bat validateCurriculum
./gradlew.bat validateCurriculum --args="src/main/resources/data/mineledger/curriculum/accounting_101.json"
./gradlew.bat build
```

`check`/`build` include the validator and JUnit suite. `ValidateCurriculum.main` also works directly
with just the compiled main classes, resources, and existing Gson jar on a Java classpath; it does
not launch Minecraft. Invalid content reports a nonzero process exit, suitable for pre-import CI.
Tests cover parsing, schema/IDs/references, answer membership, bank size, lessons, both question
types, orphan detection and exact pre-migration fingerprints, plus existing grading/save/Recharge
regressions. No dependencies or Java/Minecraft/Fabric/Gradle versions changed.

## Manual Minecraft checks and limitations

1. Rebuild, then launch using the same profile UUID and runtime directory as before.
2. Confirm Accounting 101's units and all assignment statuses match the previous version.
3. Continue Assignment 2.1 from existing partial progress, or inspect its existing completed bars/XP.
4. Check question prompts/options and grading, the inventory lesson, and the standalone Practice/Lab.
5. Run Recharge and verify saved mastery and XP stay unchanged. Restart and confirm persistence.

For a validation failure test, edit a copy of the bundle and run the file validator with a missing
reference or invalid answer ID. Do not alter player saves. Invalid bundled content intentionally
fails initialization. There is no hot reload, backend, AI, additional content, localization system
or trusted server-side assessment yet. In-game visual/restart testing remains manual.
