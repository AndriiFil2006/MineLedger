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
  deterministic bank order until mastery. There is no attempt limit. Try Again also lets students
  immediately reattempt the displayed question. Credits are never cleared or duplicated.
  Only a bank with no remaining distinct uncredited questions reports exhaustion.
- `LearningSession` owns services by assignment ID. The client retains it as `JournalSession.MASTERY`;
  creating new MineLedger screens from MineOS reuses the same progress. All state is process memory.

`InventoryCreditAssignment` defines Shop Accounting Foundations with two playable concepts and
three fixed questions each: Recording Inventory Purchases on Credit and Paying Suppliers. The latter
is authored in `SupplierPaymentConcept`. Each concept awards 20 XP, for 40 assignment XP total.
The GUI uses native scrolling layouts and buttons, including a wrapped `AnswerOptionButton`.
`ConceptAssignmentScreen` displays selection, grading feedback, concept completion and assignment
completion. The existing journal-entry dropdown exercises remain independently available, with their
original XP rules and tests. Progress displays the two activities separately.

## Manual Minecraft checks

1. With Java 25, run `./gradlew.bat runClient` from `minecraft-mod`. Enter a world, right-click a
   MineLedger Computer, open MineLedger, select Assignments, then Open Concept Mastery.
2. Confirm the assignment title, Recording Inventory Purchases on Credit, and 0/2 progress.
   Submit Answer must be disabled until an option is selected. Click each choice: exactly one
   radio marker and background should highlight. Answer text should wrap within the button.
3. Answer the shovel question with **Debit Inventory 60; Credit Accounts Payable 60**.
   Confirm Correct, an explanation, 1/2 progress and 0 XP. Use Next Question.
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
6. Restart the Minecraft client for a fresh progress session. Answer the shovel question correctly,
   then answer books incorrectly. Confirm Incorrect and an explanation; mastery remains 1/2.
   Next Question must show the lantern question, rather than repeating books immediately.
   Answer **Debit Inventory 86; Credit Accounts Payable 86** (5 x 16 + 6 delivery = 86).
   Confirm completion and 20 XP.
7. Restart for another fresh session and answer all three incorrectly, advancing after each.
   Next Question should automatically rotate missed questions again, with 0/2 progress and no
   end-of-bank interruption. Try Again should allow unlimited immediate retries of the same question.
   Earn one credit and keep missing the others: the credited question must be excluded and the
   prior credit retained. Answer a second distinct question correctly to unlock the next concept.
8. Resize the window or change GUI scale during selection, feedback, exhaustion and completion.
   Scroll to reach every option, Submit and Continue; the fixed Back to Assignments footer must
   stay separate. Check Tab/Shift+Tab and Space/Enter as well as the mouse.

## Tests and limitations

Run `./gradlew.bat build --console=plain` with Java 25. JUnit reports are in
`build/reports/tests/test/index.html`. Tests cover grading, invalid IDs, option reordering,
unique credits, monotonic completion, one-time XP, ordered unlocking, unlimited retries, fair rotation,
true bank exhaustion when insufficient distinct questions exist,
immutable snapshots, and reopening the same session service. Extra concepts used by unit tests
are synthetic fixtures, not playable content.

Two concepts and six authored questions ship. Progress, attempted-question history and XP
reset when the Minecraft process closes. Closing an exercise discards its unsubmitted selection
and onscreen feedback, but retains all submitted progress. Incorrect questions remain retryable
until correct. No persistence, remote generation, FastAPI, database or networking
is implemented. UI prose is English. Automated tests do not replace interactive rendering and
navigation checks; no interactive Minecraft GUI verification was run for this change.
