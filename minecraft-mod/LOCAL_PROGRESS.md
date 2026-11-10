# Local profile progress (schema 1)

## Location and scope

`<Minecraft game directory>/mineledger/progress/<profile-uuid>.json`

For the default Gradle development runtime, the game directory is `minecraft-mod/run/`.
`runClient` now passes a stable username and UUID from `mineledger_dev_username` and
`mineledger_dev_uuid` in `gradle.properties`. Its default UUID reuses the existing development save
with 20 XP. Earlier random-profile save files are preserved. Changing the configured UUID selects
a different progress file, so keep it unchanged when testing persistence across launches.
Installed launchers may use `.minecraft` or a separate instance directory. The UUID comes from
Minecraft's local launcher profile (`User.getProfileId()`), with a deterministic offline-name UUID
fallback only if the profile UUID is unavailable. Names never become filenames. Different UUIDs
use independent files. The same local profile shares learning progress across worlds and servers.
This is editable, local MVP learning data, not trusted multiplayer or assessment storage.

## Architecture and lifecycle

- `ProgressRepository` abstracts load/save. `JsonProgressRepository` uses Minecraft's existing Gson;
  there is no additional dependency and no polymorphic/reflection-based deserialization.
- `ProfileLearningProgress` restores existing domain services, validates saved IDs and rewards against
  installed content, and saves immutable snapshots after meaningful service changes. No screens grade
  answers or read/write files while rendering. `JournalSession` lazily opens the relevant profile
  when the app is used and caches profile states independently during the client process.
- `ConceptProgressionService` accepts validated restored progress and a change callback. Completion,
  the active concept and assignment completion are derived from distinct credits in concept order.
  Saved rewarded-concept IDs and awarded XP must agree with this derivation. Loading does not award
  anything; replaying credited questions cannot grant credits or XP again.
- `JournalProgress` also restores its unique credited question IDs and keeps its original 10 XP rule.
  Concept mastery retains its 20 XP per completed concept rule. Profile total XP includes both.
- Valid answer submissions save changes to credits or question-selection history. Duplicate submissions
  with no state change, idle frames and clean shutdowns do not rewrite the file. Dirty progress gets
  another save attempt on client shutdown/profile changes after a transient I/O failure.
- Screen selections, a journal attempt's score and displayed feedback are transient. Saved learning
  credits, completed concepts, XP, attempted-question IDs and last attempted question survive restart.
- Recharge Practice is deliberately transient and does not use save callbacks. It unlocks for a
  completed assignment, reviews the full bank and never changes saved credits, completion or XP.

## Data format

Course organization is catalog metadata, not persisted progress. The existing assignment key
`inventory_fundamentals` is attached to Accounting 101, Unit 2, Assignment 2.1: Journal Entries.
No schema change or file rewrite is needed to preserve older progress; see `COURSES.md`.

Example after crediting only the first inventory question (other unchanged concepts are implicit):

```json
{
  "schemaVersion": 1,
  "profileId": "11111111-2222-3333-4444-555555555555",
  "totalAwardedXp": 0,
  "journalQuestionIds": [],
  "assignments": {
    "inventory_fundamentals": {
      "activeConceptId": "inventory.purchases_on_credit",
      "awardedXp": 0,
      "rewardedConceptIds": [],
      "concepts": {
        "inventory.purchases_on_credit": {
          "correctQuestionIds": ["inventory.shovels"],
          "attemptedQuestionIds": ["inventory.shovels"],
          "lastAttemptedQuestionId": "inventory.shovels"
        }
      }
    }
  }
}
```

Concept completion is `distinct correct IDs >= requiredCorrectAnswers`; assignment completion is
all concepts completed. `activeConceptId` becomes null after assignment completion. Rewarded IDs
record exactly the completed concepts; each contributes 20 to assignment XP. Correct journal IDs
contribute 10 each to the profile total. No screen indices or question positions are persisted.

## File safety and failures

- UUID-only filenames, a fixed game-directory subfolder and refusal of symbolic-link storage paths.
- Strict JSON, duplicate-field/ID checks, bounded file size/depth, required fields and integer values.
  Unknown saved assignments/questions, invalid credit order or inconsistent rewards are not silently dropped.
- Write a sibling temporary file, flush it, then atomically replace the save. On filesystems without
  atomic replacement, retain a last-good `.json.bak` before replacement. Clean up temporary files.
- Re-read before replacing a file; reject unsupported/corrupt files and detected external changes.
  There is no automatic merge. Run one Minecraft client at a time for a profile: this is not a
  cross-process transaction/locking system.
- A missing file starts fresh without writing an empty file. A malformed, old or future-schema file
  stays untouched. Loading/validation failure makes saving read-only for that profile until restart;
  practice remains available in memory. Progress shows the warning and the client log records it.
- Schema 1 is the only supported schema. Unsupported schemas are preserved for a future explicit
  migration, rather than downgraded or reset. No reset button or automatic reset was added.
- Transient write failures keep progress in memory and retry on later meaningful changes/shutdown.
  A forced process termination or persistent disk failure can still prevent the latest changes saving.
  Writes are synchronous and small; no background writer, cloud sync or backend is introduced.

## Manual complete-restart test

1. Use the same launcher profile and game directory for both launches. This project's `runClient`
   launch now has a fixed development UUID; other IDE configurations must use the same UUID too.
   Use a separate test profile for fresh-state tests; restarting no longer clears existing progress.
2. Computer -> MineOS -> MineLedger -> Assignments -> Open Concept Mastery. Correctly answer one
   inventory question. Confirm 1/2, a half-filled concept bar and 0 mastery XP. Open Progress and
   check the storage status. Verify the UUID JSON file exists in the runtime location above.
3. Close Minecraft completely, relaunch with the same profile and reopen MineLedger. Confirm 1/2
   is retained and the credited question is not offered again.
4. Correctly answer a second distinct inventory question. Confirm its full bar and 20 XP. Restart
   again: inventory stays completed, Paying Suppliers is active and XP remains 20.
5. Complete two supplier questions. Confirm 2/2 completed concepts and 40 mastery XP. Restart and
   reopen repeatedly: completion and XP must remain unchanged. Complete/repeat a journal exercise
   as well; its credited question must not award another 10 XP after restarting.
6. Switch to another local profile with a different UUID. It should have separate progress; switching
   back must retain the first profile's data. Returning through MineOS never resets either profile.
7. For an isolated failure test, close Minecraft and back up the test profile's file. Put malformed JSON
   or a different schemaVersion in that copy's place. Reopen: Minecraft must stay usable, Progress/log
   must warn, and answering questions must not overwrite the damaged file. Exit and restore the backup
   before resuming normal testing. Do not edit your only copy of real progress.

Automated JUnit tests cover round trips, 1/2 mastery, completion, credited IDs, XP deduplication,
question history, journal practice, missing/corrupt files, schema handling, profile separation,
reward validation, external edits, temporary write failures and avoiding redundant writes.
The Gradle build/tests passed; no interactive complete-client-restart test was run by the agent.
