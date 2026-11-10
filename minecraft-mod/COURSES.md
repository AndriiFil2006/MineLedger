# Course organization

## Domain and sample catalog

`com.mineledger.education` contains immutable `Course` and `Unit` models, a validated `CourseCatalog`,
ID-based `CourseNavigation`, `CourseProgress` aggregation and the fixed `Accounting101` catalog.
The existing `accounting.mastery.Assignment` now includes its parent unit ID, description and
AVAILABLE/COMING_SOON availability. Its compatibility constructor remains for standalone fixtures.
Concept and question models, answer keys, grading, mastery thresholds and reward rules are unchanged.

Course -> ordered Units -> ordered Assignments -> ordered Concepts -> Questions:

```text
Accounting 101 — Financial Accounting (accounting101)
  Unit 1: Accounting Fundamentals (accounting101.fundamentals)
    1.1 The Accounting Equation                 Coming Soon
    1.2 Assets, Liabilities, and Equity          Coming Soon
    1.3 Debit and Credit Rules                  Coming Soon
  Unit 2: Recording Transactions (accounting101.recording_transactions)
    2.1 Journal Entries                         Available: inventory_fundamentals
    2.2 Posting to the Ledger                   Coming Soon
    2.3 Trial Balance                           Coming Soon
  Unit 3: Adjusting Entries (accounting101.adjusting_entries)
    3.1 Accruals                                Coming Soon
    3.2 Deferrals                               Coming Soon
```

The existing playable assignment is now titled Assignment 2.1: Journal Entries. Its two concepts,
six questions and all existing IDs remain unchanged. There are no new accounting questions.
The seven Coming Soon assignments contain no concepts or questions and cannot launch a progression
service. Catalog routing returns an empty result for unknown/unavailable IDs. Parent links, ordered
unit numbers and duplicate catalog IDs are validated when definitions are built.

## Navigation and progress

MineOS -> MineLedger -> Courses -> Accounting 101 -> Unit -> Assignment 2.1 -> existing exercise UI.
`CourseBrowser` presents native wrapped labels and buttons inside the existing scaled MineLedger
screen. No scrolling page or new exercise screen was introduced. Start/Continue/Completed labels
come from existing progress. Completed assignments also offer Recharge Practice. Returning from an
exercise retains the selected course/unit in the app. Back to Units/Back to Courses and Escape
navigate up the browser; the existing Back to Desktop button remains a direct exit.

Assignment progress = completed concepts / its configured concept count. Unit and course progress =
completed available assignments / total available assignments. There is no assumption of 30 concepts.
Units with only Coming Soon content display No available assignments instead of fake completion or
an inflated denominator. The Progress tab also shows the course's derived available-assignment count.
Unit order is organizational, not prerequisite gating: Unit 2 is accessible while Unit 1 is Coming Soon.
The Assignments tab lists all catalog assignments grouped by course/unit in a bounded scrolling
area. Only its list scrolls; tabs, heading, Practice / Lab and Back to Desktop remain visible.
Scroll position is retained when returning from an exercise. The independent legacy journal-entry
practice is a fixed Practice / Lab: Journal Entries action, excluded from course assignment counts.

`AssignmentDirectory` supplies the same entries and stable-ID selection to both routes.
`AssignmentRows` renders the same rows for the course unit browser and global list. Unstarted work
offers Start, work with submitted progress offers Continue, and completed work offers Recharge
Practice with no extra XP. Coming Soon actions are disabled and routing rejects them independently.
Adding available content to the catalog automatically adds its listing, progress and actions; the
Progress tab also iterates available catalog assignments rather than a fixed assignment.

## Save compatibility

No disk migration or schema bump is required. Existing schema-1 saves store IDs and reward state,
not assignment titles, course IDs, unit IDs or navigation positions. The key `inventory_fundamentals`
and all concept/question IDs are retained exactly. The new unit relationship is catalog metadata.
`JournalSession` supplies only available catalog assignments to the existing profile persistence
coordinator. Placeholders create no saved credits, rewards or artificial progress.

Opening a course, calculating progress, returning to MineOS and running Recharge do not trigger
reward grants or dirty saves. Unknown IDs in navigation return safely; unsupported IDs inside saves
retain the existing warning/read-only protection rather than silently discarding user data. Real user
save files were inspected read-only and were not migrated or rewritten by this milestone.

## Manual checks

1. Start Minecraft with the same profile UUID used for existing progress (`runClient` is pinned to
   the configured development UUID). Open the computer -> MineLedger -> Courses.
2. Select Accounting 101. Confirm the three units in order. Units 1 and 3 should say No available
   assignments. Inspect each: their assignments must say Coming Soon and must not open exercises.
3. Open Unit 2. Confirm Assignment 2.1: Journal Entries reflects your existing completed-concept
   count; 2.2 and 2.3 must say Coming Soon. Start/Continue should open the existing question UI.
4. Exit the exercise. Confirm Unit 2 is still selected. Back to Units, Back to Courses and Escape
   should traverse the hierarchy; Back to Desktop should still return directly to MineOS.
5. On a partial-progress profile, confirm saved credits and XP are unchanged by browsing. Restart
   Minecraft, return through Courses and continue the remaining concept from its saved state.
6. On a completed profile, confirm course and Unit 2 progress are 1/1 (not 1/8 or 1/3). Open Recharge
   from Unit 2, review questions, exit and restart. Completed concepts and 40 mastery XP must remain.
7. Resize the window/change GUI scale across each course/unit view. Content should auto-fit using
   the existing 85% zoom system. Check mouse and keyboard navigation, with no clipped controls.
8. Select the Assignments tab. Confirm all three units and eight assignments appear in catalog order,
   with seven Coming Soon entries. Scroll to Unit 2 and confirm Start, Continue or Recharge matches
   your saved state. Its assignment must match the course route's credits, completion and XP.
9. Open and exit that assignment; the list should retain its scroll position. Confirm the fixed
   Practice / Lab: Journal Entries button opens the older debit/credit exercise separately. Test
   scroll-wheel, scrollbar dragging and keyboard navigation after resizing or changing GUI scale.

## Validation and limitations

`./gradlew.bat build --console=plain` runs the existing tests plus `CourseHierarchyTest` and
`AssignmentDirectoryTest`.
Tests cover the tree, stable lookup/routing, variable concept counts, weighted available-assignment
aggregation, unavailable/unknown IDs, parent validation, navigation stability, old schema-1 partial
progress, post-reload completion/XP and Recharge leaving saved data unchanged. The old-save fixture
is `src/test/resources/progress/schema1-before-courses.json`.
Directory tests also cover grouping, Start/Continue IDs, disabled entries, same-service routing,
no opening rewards, restored credits and a future playable assignment appearing automatically.

Only one course and one available assignment ship. Course/unit selection is transient UI state;
learning progress remains persistent. No prerequisites, enrollment, content backend or new subjects
are implemented. The independent journal-practice activity is not included in course percentages.
Automated build/tests passed; interactive Minecraft navigation/rendering verification remains manual.
