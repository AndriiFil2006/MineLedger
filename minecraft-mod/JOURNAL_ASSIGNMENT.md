# Introductory Journal Entries

The `com.mineledger.accounting` package contains immutable question, answer and result records,
stable account identifiers, the three fixed sample questions, a deterministic grader and in-memory
progress. It has no Minecraft imports. Each answer has one debit account, one credit account and
one amount that applies to both lines. Decimal currency input accepts up to two decimal places.

`JournalEntryScreen` presents one question at a time using account dropdown lists built from native
buttons, an edit box and a measured auto-fitting layout. Opening a dropdown reveals every available account
below its header and moves subsequent controls down; choosing an account collapses the list.
Only one list is open at a time. Like MineOS, the MineLedger tabs and concept mastery, this window
uses `ScaledAppScreen` for 85% zoom, shrinking further when needed to fit without a scrollbar.
It delegates validation to the domain layer. Invalid input can be corrected;
valid incorrect answers display an explanation and offer Try Again or Next Question. The completion
score counts questions answered correctly during that attempt, including retries. Reopening starts
a new attempt. Unique correct question IDs award 10 learning XP each through `JournalSession`.
This is separate from Minecraft experience. Credited question IDs and profile XP persist across
worlds and complete restarts through the local JSON repository described in `LOCAL_PROGRESS.md`.
An individual exercise attempt's form and score remain transient.

## Build and tests

Use Java 25 and run `./gradlew.bat build test` from `minecraft-mod`.
The only test dependency is JUnit 4.13.2. Reports are in `build/reports/tests/test/index.html`.

## Manual acceptance check

1. Run `./gradlew.bat runClient` with Java 25. Enter a creative world and place a MineLedger Computer
   (or use `/give @s mineledger:computer`). Right-click it, open MineLedger, select Assignments and
   click Open Journal Entries.
2. On question 1, submit a blank amount, nonnumeric input, zero, a negative number and the same
   account on both sides with a positive amount. Each must show input feedback and award no XP.
3. Use different but incorrect accounts or an incorrect positive amount. Submit; check the Incorrect
   status and accounting explanation. Try Again permits correction; Next Question permits moving on.
4. Complete the questions using the following entries, clicking Submit Answer then Next Question:

   | Question | Debit | Credit | Emeralds |
   | --- | --- | --- | --- |
   | Steve's shovels purchased on credit | Inventory | Accounts Payable | 60 |
   | Alex's cash advertising payment | Advertising Expense | Cash | 25 |
   | Shop's cash service sale | Cash | Service Revenue | 80 |

5. Check that completion shows 3/3 and 30/30 points. Back to Assignments returns to the existing app.
   Progress should show 3/3 unique correct questions and 30/30 learning XP.
6. Repeat the assignment. Profile XP must stay at 30. In a new attempt, skip one incorrect answer
   using Next Question; its attempt score should be 2/3 while already earned profile XP remains 30.
7. Resize the window and increase GUI scale during a question, after feedback and on completion.
   Confirm the window fits without scrolling and entered values survive resizing. Expand both account
   dropdowns and select options after resizing; test scaled button hit areas, Tab/Shift+Tab navigation
   and keyboard entry. Also check desktop launch/shutdown and all three MineLedger tabs at the same sizes.
8. Return to MineOS and reopen the app: profile totals should remain. Exit Minecraft completely and
   relaunch with the same profile: Progress should retain its totals, and replaying answers must not
   award duplicate XP. Verify that the computer model and desktop look unchanged.

Runtime GUI checks require an interactive Minecraft client; automated tests cover grading and XP,
not rendering. Courses remain a placeholder for this milestone. UI and question prose are English;
account names use language keys. Leaving an unfinished assignment discards that attempt's form,
but already awarded profile XP remains. See `LOCAL_PROGRESS.md` for file safety and recovery behavior.
