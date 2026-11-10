# MineLedger agent guide

## Start here; keep exploration small

- This repository contains the Fabric mod in `minecraft-mod/`. Run Gradle there.
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
Courses is a placeholder. Journal Entries is functional: exactly three fixed Minecraft scenarios,
one debit and one credit sharing an amount, deterministic feedback, retries, completion score,
and at most 30 learning XP per client process. Account selectors are expandable dropdown lists
built from native buttons, not cycle buttons. The form scrolls at smaller GUI sizes.
Shop Accounting Foundations adds multiple-choice mastery: credit purchases and paying suppliers,
three questions each, two distinct correct questions per concept, 20 XP once per concept (40 total).
Missed questions can be retried immediately or automatically rotate until mastered; earned credits
never repeat. Original journal practice remains available.

- Preserve MineOS's existing appearance and navigation unless the user requests a change.
- Do not alter computer geometry, textures, blockstates, or registration unless specifically requested.
- Keep accounting logic independent of Minecraft classes; keep all Screen/rendering code in `src/client`.
- Grade using stable `Account` identifiers and `BigDecimal`, never account display text.
  Correct answers and validation belong in the domain, not UI callbacks.
- Progress is intentionally memory-only: journal practice awards 10 XP per unique correct question;
  concept mastery awards 20 XP once per completed concept (two distinct correct questions by default).
  Both are separate from Minecraft XP and reset when the client exits.
- Do not add persistence, networking, backend/HTTP/LLM integration, a course engine, or multiline
  journal entries without a new user request. Accounting principles remain real accounting.

## File map (paths below are relative to minecraft-mod/)

| Task | Read first |
| --- | --- |
| Assignment UI, dropdowns, sizing | `src/client/java/com/mineledger/client/screen/JournalEntryScreen.java` |
| Multiple-choice UI / wrapped answer buttons | `src/client/java/com/mineledger/client/screen/ConceptAssignmentScreen.java`, `AnswerOptionButton.java` in the same directory |
| Mastery models, selection, progression, session store | `src/main/java/com/mineledger/accounting/mastery/` (`ConceptProgressionService`, `QuestionSelector`, `LearningSession`); content in `InventoryCreditAssignment.java` and `SupplierPaymentConcept.java` |
| Mastery tests / manual checks | `src/test/java/com/mineledger/accounting/mastery/ConceptMasteryTest.java`, `CONCEPT_MASTERY.md` |
| Courses/Assignments/Progress tabs | `src/client/java/com/mineledger/client/screen/MineLedgerScreen.java` |
| Desktop/app icon | `src/client/java/com/mineledger/client/screen/MineOsDesktopScreen.java`, `MineLedgerAppIcon.java` in the same directory |
| Right-click client entry | `src/client/java/com/mineledger/client/MineLedgerClient.java` |
| Session progress lifetime | `src/client/java/com/mineledger/client/JournalSession.java` |
| Models, grader, XP deduplication | `src/main/java/com/mineledger/accounting/` (`AccountingQuestion`, `AccountingAnswer`, `AccountingResult`, `Account`, `AccountingGrader`, `JournalProgress`) |
| Fixed questions and explanations | `src/main/java/com/mineledger/accounting/IntroJournalAssignment.java` |
| Grading/progress tests | `src/test/java/com/mineledger/accounting/AccountingGraderTest.java` |
| Account labels / translations | `src/main/resources/assets/mineledger/lang/en_us.json` |
| Mod entry / protected blocks | `src/main/java/com/mineledger/MineLedger.java`, `block/` in the same package |
| Versions and build | `gradle.properties`, `build.gradle`, `src/main/resources/fabric.mod.json` |
| Manual acceptance steps and scoring details | `JOURNAL_ASSIGNMENT.md` |

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
Respect sandbox permissions for cache access and downloads; do not bypass approval restrictions.
JUnit 4.13.2 is the only test dependency. Both journal and mastery tests run as part of `build`.
Reports: `build/reports/tests/test/index.html`. Output mod: `build/libs/mineledger-1.0.0.jar`.
Run relevant checks once after code changes; do not rebuild for documentation-only edits or repeat
successful checks without a reason. For version-specific UI APIs, inspect installed classes/sources
with Java 25 `javap`/`jar` before relying on examples from other Minecraft releases.
Distinguish compilation/tests from interactive GUI verification; never claim a visual check was run
unless it was. To test manually: computer -> MineOS -> MineLedger -> Assignments -> Open Journal Entries.
