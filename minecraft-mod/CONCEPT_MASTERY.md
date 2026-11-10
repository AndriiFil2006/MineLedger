# Multiple-choice concept mastery

## Architecture

The independent `com.mineledger.accounting.mastery` package contains:

- Immutable `Assignment`, `AccountingConcept`, `MultipleChoiceQuestion` and `AnswerOption` definitions.
  Question construction validates four distinct option IDs and exactly one answer key. Assignment
  construction checks unique concept IDs and unique question IDs across its bank.
- `MultipleChoiceGrader` grades one stable option ID, checks membership, and returns `GradingResult`.
  Invalid input does not consume a question or change progress. Valid incorrect answers include the
  accounting explanation. No screen compares answer keys.
- Immutable `ConceptProgress` and `AssignmentProgress` snapshots. `ConceptProgressionService` records
  unique correct question IDs, retains credits after mistakes, locks later concepts until the current
  one is completed, and derives 20 XP for each completed concept. A submission reports whether a new
  credit was earned and whether completion newly awarded 20 XP. Completed concepts cannot regress.
- `QuestionSelector` prefers unseen, uncredited questions, then rotates all missed questions in
  deterministic bank order until mastery. There is no attempt limit. After feedback, Continue moves
  to the next eligible question; missed questions return later. Credits are never cleared or duplicated.
  Only a bank with no remaining distinct uncredited questions reports exhaustion.
- `LearningSession` owns services by assignment ID. `JournalSession.mastery()` returns the current
  local profile's restored services; new MineLedger screens reuse them. `ProfileLearningProgress`
  saves changes through `ProgressRepository` to local versioned JSON. See `LOCAL_PROGRESS.md`.

`InventoryCreditAssignment` defines Shop Accounting Foundations with two playable concepts and
three fixed questions each: Recording Inventory Purchases on Credit and Paying Suppliers. The latter
is authored in `SupplierPaymentConcept`. Each concept awards 20 XP, for 40 assignment XP total.
The GUI uses a compact centered window laid out on a 600-unit logical canvas. Its header shows MineLedger,
the assignment title, concept index and Exit Assignment. `ConceptProgressStripWidget` shows completed
concept count and compact mastery bars (empty, half, full for 0/2, 1/2, 2/2). The current bar has a red
outline and underline; completed bars fill dark red. Hover shows the concept title and credits. Bars
wrap into balanced rows rather than shrinking below a readable minimum width.

`AssignmentTextPanel` measures wrapped question and feedback cards. Four compact `AnswerOptionButton`
radio choices sit between them. `AssignmentStyle` contains the learning window's white/red palette.
The fixed bottom row contains XP and Submit Answer or Continue as appropriate; there is no Try Again.
The entire window, including text, renders at 85% size, or smaller when necessary to fit its measured
height and width inside the viewport. There is no scrolling container or scrollbar. Rendering and
mouse input use matching transforms, while keyboard focus stays in logical coordinates. Global
Minecraft GUI scale is unchanged. `ScaledAppScreen` now shares this rendering/input transform with
the MineOS desktop, MineLedger navigation pages and journal exercises. Explanations are not truncated. Selection and submission state
survive resize/re-layout. Extremely small windows necessarily make the scaled text smaller.
The existing journal-entry dropdown exercises remain independently available, with their
original XP rules and tests. Progress displays the two activities separately.

## Manual Minecraft checks

1. With Java 25, run `./gradlew.bat runClient` from `minecraft-mod`. Enter a world, right-click a
   MineLedger Computer, open MineLedger, select Assignments, then Open Concept Mastery.
2. Confirm the assignment title, Recording Inventory Purchases on Credit, and 0/2 progress.
   Submit Answer must be disabled until an option is selected. Click each choice: exactly one
   radio marker and background should highlight. Answer text should wrap within the button.
3. Answer the shovel question with **Debit Inventory 60; Credit Accounts Payable 60**.
   Confirm Correct, an explanation, 1/2 progress and 0 XP. Use Continue.
4. Answer the book question with **Debit Inventory 80; Credit Accounts Payable 80**.
   Verify the explanation: 10 books x 9 emeralds - 10 trade discount = 80. Confirm 2/2,
   Concept Completed and +20 XP. Continue unlocks Paying Suppliers, with assignment progress 1/2.
5. In Paying Suppliers, answer the first two questions with **Debit Accounts Payable 60; Credit Cash 60**
   and **Debit Accounts Payable 30; Credit Cash 30**. Confirm Concept Completed and +20 XP.
   Continue shows Assignment Completed with 40 learning XP. The third payment question, if reached,
   records **Debit Accounts Payable 36; Credit Cash 36** for invoices totaling 20 + 16.
   Back to Assignments -> Progress should show 2/2 concepts, each at 2/2 questions, and 40 mastery XP.
   Return to MineOS and reopen: completion and XP must remain without awarding more XP.
   Original Open Journal Entries must still work.
6. Use a separate test profile for a fresh progress state. Answer the shovel question correctly,
   then answer books incorrectly. Confirm Incorrect and an explanation; mastery remains 1/2.
   Continue must show the lantern question, rather than repeating books immediately.
   Answer **Debit Inventory 86; Credit Accounts Payable 86** (5 x 16 + 6 delivery = 86).
   Confirm completion and 20 XP.
7. Use another fresh test profile and answer all three incorrectly, advancing after each.
   Continue should automatically rotate missed questions again, with 0/2 progress and no
   end-of-bank interruption. There should be no Try Again button.
   Earn one credit and keep missing the others: the credited question must be excluded and the
   prior credit retained. Answer a second distinct question correctly to unlock the next concept.
8. At a normal GUI viewport (for example 640x360 GUI units), inspect the centered question card,
   four compact choices, help/feedback panel and fixed action row. Check the strip at 0/2, 1/2 and
   2/2: empty, half-filled and dark-red/full; the displayed concept has a red underline. Hover each bar.
   Resize or increase GUI scale during selection, feedback, exhaustion and completion. The entire
   window should shrink to fit without a scrollbar, retaining every choice and the complete explanation.
   Click choices at the top and bottom and the Exit and Continue buttons after each resize to check
   that hit areas match the scaled visuals. Check Tab/Shift+Tab and Space/Enter as well as the mouse.
   Confirm no selection, credits or XP are
   lost when resizing or exiting to Assignments. Visual GUI checks still require an interactive client.

## Tests and limitations

### Recharge Practice

Completing all concepts unlocks Recharge Practice from the Assignments menu and the assignment
completion screen. This reuses the compact question UI with a Practice label and a +0 XP footer.
`RechargePractice` owns a disposable queue of all questions, including questions not needed for
mastery. It calls the existing grader, then Continue removes correctly reviewed questions or moves
missed ones to the back. There is no attempt limit. Finishing the queue shows Practice Complete;
Practice Again starts another round. Exit Assignment always returns to the app.

The queue holds no progress service, reward state or save callback. Permanent concept bars remain
fully mastered; practice feedback shows reviewed questions instead of awarding new mastery. Leaving
practice discards only the practice round, with no effect on persistent credits, XP or completion.

Manual check: finish both concepts, choose Recharge Practice, deliberately miss one question and
continue through the others. The missed question must return later. Correct all six to finish, then
use Practice Again. Progress must still show 40 mastery XP, and the saved JSON must remain unchanged.
Restart Minecraft: the completed assignment and Recharge option must still be available, while a new
practice round starts fresh. In-game visual verification remains a manual check.

### Automated checks

Run `./gradlew.bat build --console=plain` with Java 25. JUnit reports are in
`build/reports/tests/test/index.html`. Tests cover grading, invalid IDs, option reordering,
unique credits, monotonic completion, one-time XP, ordered unlocking, unlimited retries, fair rotation,
true bank exhaustion when insufficient distinct questions exist,
immutable snapshots, and reopening the same session service. Extra concepts used by unit tests
are synthetic fixtures, not playable content.

Two concepts and six authored questions ship. Progress, attempted-question history and XP
persist locally per profile across client restarts. Closing an exercise discards its unsubmitted selection
and onscreen feedback, but retains all submitted progress. Incorrect questions remain retryable
until correct. Local JSON persistence is implemented; remote generation, FastAPI, databases and
networking are not. UI prose is English. Automated tests do not replace interactive rendering and
navigation checks; no interactive Minecraft GUI verification was run for this change.
