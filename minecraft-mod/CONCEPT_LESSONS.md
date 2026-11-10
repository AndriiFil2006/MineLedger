# Concept learning resources

## Content and lookup

`education.lesson.ConceptLesson` is an immutable, Minecraft-independent content record:

```text
id, conceptId, title, learningObjective, explanation,
keyRules: [text],
workedExamples: [{title, scenario, calculation, debitEntry, creditEntry, explanation}],
commonMistakes: [text], takeaway: text
```

Common mistakes may be an empty list and the optional takeaway may be empty text. A lesson requires
at least one rule and worked example. Collections are copied; IDs/text and duplicate mappings are
validated. There is no reflection-based deserialization, networking or executable lesson content.

`ConceptLessonSource` is the lookup boundary (`forConcept(conceptId) -> Optional<ConceptLesson>`).
`ConceptLessonCatalog` is its local implementation. Another provider can supply the same records
in a future milestone without coupling a backend to Minecraft rendering; no backend is implemented.
Add future lessons to the catalog by concept ID, never question order. Duplicate lesson IDs or
multiple lessons for the same concept are rejected to avoid ambiguous resources.
Content edits now belong in curriculum JSON; the loader constructs the catalog from those definitions.

The curriculum JSON's `lessons` table supplies exactly one original lesson, resolved through the
validated concept `lesson_id` reference. `IntroConceptLessons` is now a compatibility lookup only
(see `CURRICULUM_ARCHITECTURE.md`):

- ID: `lesson.inventory.purchases_on_credit.perpetual`
- Concept: `inventory.purchases_on_credit`
- Topic: Recording Inventory Purchases on Credit, under the perpetual inventory system.
- Verified example: 4 diamond shovels x 15 emeralds = 60; debit Inventory 60, credit Accounts Payable 60.
- Inventory is an asset; Accounts Payable is a liability. Asset increases are debits and liability
  increases are credits. Cash is unchanged because the supplier has not been paid. Shovels held
  for resale are inventory, rather than equipment retained for business use.

## UI and state ownership

The existing exercise adds Learn / Review Concept beside Submit Answer or Continue. The button
remains available before and after submission and in Recharge. For concepts without content it
is disabled and labeled Lesson Unavailable. A lookup must match the displayed question's concept,
including when that concept was just completed and the progress service has advanced to another.

`ConceptLessonScreen` uses the existing white/red styling and local zoom. Wrapped objective,
explanation, rules, worked example, common mistakes and takeaway cards occupy a bounded scroll
panel. Header and Back to Practice stay fixed. Escape also returns to practice. Resizing retains
the lesson's scroll position.

The lesson stores the exact `ConceptAssignmentScreen` as its return target, not a newly created
exercise. `presentation.PracticeQuestionState` keeps the question, selection and structured
submission/feedback snapshot across that detour. Reinitializing the native screen does not replace
an existing question. Only Continue clears it and advances through the existing progression or
Recharge service. Grading, mastery rules and the exercise's layout are otherwise unchanged.

Reading only looks up immutable content. It grants no mastery, rewards or XP, does not advance the
practice queue and does not dirty/save profile data. No persistence schema changes are necessary.
Lesson visits and reading position are transient; no lesson-completion tracking is introduced.

## Manual checks

1. Open Assignment 2.1 in mastery, or its Recharge action if already completed. On an inventory
   question, select an option without submitting, then choose Learn / Review Concept.
2. Read all sections. Confirm the 60-emerald entry and the Cash/Equipment explanations. Scroll the
   content, resize/change GUI scale, and check the header and Back to Practice remain visible.
3. Return via Back to Practice and via Escape. The exact question and selected option must remain.
   Submit, open the lesson again and return: feedback, selected option and Continue must remain.
4. Complete the second inventory question but open the lesson before Continue. It must still show
   the inventory lesson even though Paying Suppliers is now unlocked. Returning must retain the
   original completion feedback without awarding another 20 XP.
5. On Paying Suppliers, confirm Lesson Unavailable is disabled; no inventory lesson should open.
6. Repeat before/after a Recharge submission. Reading must not advance the queue or change the
   reviewed count; only Continue does so. Check mastery XP and the saved JSON remain unchanged.

## Validation and limitations

Run the Java 25 Gradle wrapper build. `ConceptLessonTest` covers exact/missing lookup, provider
association checks, verified example/rules, state snapshots before/after submission, no progress/save
callback/XP effects, Recharge queue preservation, immutable content and ambiguous catalog rejection.
The existing grader, course, persistence and practice tests continue to run.

Only one English lesson ships. There is no supplier-payment lesson yet, no reading rewards, lesson
history, networking or new accounting questions. Automated tests validate data/state and progression
effects; actual native screen navigation/rendering still needs the interactive checks above.
