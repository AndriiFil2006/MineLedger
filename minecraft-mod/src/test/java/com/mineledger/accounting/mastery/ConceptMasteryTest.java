package com.mineledger.accounting.mastery;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import com.mineledger.accounting.mastery.GradingResult.Status;

public class ConceptMasteryTest {
    private final AccountingConcept concept = InventoryCreditAssignment.ASSIGNMENT.concepts().getFirst();
    private final Assignment assignment = new Assignment("inventory_test", "Inventory test", List.of(concept));
    private final MultipleChoiceGrader grader = new MultipleChoiceGrader();

    private ConceptProgressionService service() { return new ConceptProgressionService(assignment); }

    private ConceptProgressionService.Submission correct(ConceptProgressionService service, int index) {
        MultipleChoiceQuestion question = concept.questions().get(index);
        return service.submit(question.id(), question.correctOptionId());
    }

    private ConceptProgressionService.Submission incorrect(ConceptProgressionService service, int index) {
        MultipleChoiceQuestion question = concept.questions().get(index);
        return service.submit(question.id(), question.options().stream()
                .filter(option -> !option.id().equals(question.correctOptionId())).findFirst().orElseThrow().id());
    }

    @Test
    public void manuallyVerifiedAnswersAndCalculations() {
        assertEquals(1, assignment.concepts().size());
        assertEquals(2, concept.requiredCorrectAnswers());
        assertEquals(3, concept.questions().size());
        List<String> ids = List.of("shovels.inventory_payable", "books.inventory_payable", "lanterns.inventory_payable");
        List<String> entries = List.of("Debit Inventory 60; Credit Accounts Payable 60.",
                "Debit Inventory 80; Credit Accounts Payable 80.", "Debit Inventory 86; Credit Accounts Payable 86.");
        assertEquals(60, 4 * 15);
        assertEquals(80, 10 * 9 - 10);
        assertEquals(86, 5 * 16 + 6);
        for (int i = 0; i < 3; i++) {
            MultipleChoiceQuestion question = concept.questions().get(i);
            assertEquals(4, question.options().size());
            assertEquals(ids.get(i), question.correctOptionId());
            assertEquals(entries.get(i), question.options().stream().filter(option -> option.id().equals(question.correctOptionId()))
                    .findFirst().orElseThrow().text());
            assertTrue(grader.grade(question, ids.get(i)).correct());
        }
    }

    @Test
    public void incorrectAnswersReceiveAccountingExplanation() {
        MultipleChoiceQuestion question = concept.questions().getFirst();
        GradingResult result = grader.grade(question, "shovels.cash");
        assertEquals(Status.INCORRECT, result.status());
        assertEquals(question.explanation(), result.explanation());
        assertTrue(result.valid());
        assertFalse(result.correct());
    }

    @Test
    public void invalidAndForeignOptionIdsDoNotConsumeQuestions() {
        ConceptProgressionService service = service();
        MultipleChoiceQuestion question = concept.questions().getFirst();
        for (String id : new String[] {null, "", "unknown", "books.inventory_payable"}) {
            assertEquals(Status.INVALID_OPTION, service.submit(question.id(), id).grading().status());
        }
        assertEquals(0, service.progress().concept(concept.id()).credits());
        assertEquals(question, service.nextQuestion().orElseThrow());
        assertEquals(0, service.xp());
    }

    @Test
    public void gradingUsesIdsRegardlessOfOptionOrder() {
        MultipleChoiceQuestion original = concept.questions().getFirst();
        var options = new ArrayList<>(original.options());
        Collections.reverse(options);
        MultipleChoiceQuestion reordered = new MultipleChoiceQuestion(original.id(), original.conceptId(), original.prompt(),
                options, original.correctOptionId(), original.explanation());
        assertTrue(grader.grade(reordered, "shovels.inventory_payable").correct());
        assertFalse(grader.grade(reordered, "shovels.cash").correct());
    }

    @Test
    public void twoDistinctCorrectQuestionsCompleteConceptAndAssignment() {
        ConceptProgressionService service = service();
        assertFalse(service.completed());
        assertTrue(correct(service, 0).creditEarned());
        assertEquals(1, service.progress().concept(concept.id()).credits());
        assertEquals(0, service.xp());
        assertFalse(service.completed());
        assertEquals(20, correct(service, 1).xpAwarded());
        assertTrue(service.progress().concept(concept.id()).completed(concept));
        assertTrue(service.completed());
        assertEquals(1, service.completedConcepts());
        assertEquals(20, service.xp());
        assertTrue(service.nextQuestion().isEmpty());
    }

    @Test
    public void duplicateSubmissionsNeverEarnAdditionalCreditsOrXp() {
        ConceptProgressionService service = service();
        correct(service, 0);
        for (int i = 0; i < 5; i++) {
            var result = correct(service, 0);
            assertFalse(result.creditEarned());
            assertEquals(0, result.xpAwarded());
        }
        assertEquals(1, service.progress().concept(concept.id()).credits());
        assertEquals(0, service.xp());
        correct(service, 1);
        for (int i = 0; i < 5; i++) assertEquals(0, correct(service, 1).xpAwarded());
        correct(service, 2);
        assertEquals(2, service.progress().concept(concept.id()).credits());
        assertEquals(20, service.xp());
    }

    @Test
    public void incorrectAnswersDoNotResetCreditsOrCompletion() {
        ConceptProgressionService service = service();
        correct(service, 0);
        incorrect(service, 1);
        assertEquals(1, service.progress().concept(concept.id()).credits());
        correct(service, 2);
        assertTrue(service.completed());
        assertEquals(0, incorrect(service, 0).xpAwarded());
        assertTrue(service.completed());
        assertEquals(2, service.progress().concept(concept.id()).credits());
    }

    @Test
    public void selectorSkipsCreditedQuestionsAndDoesNotImmediatelyRepeatErrors() {
        ConceptProgressionService service = service();
        incorrect(service, 0);
        assertEquals(concept.questions().get(1), service.nextQuestion().orElseThrow());
        correct(service, 1);
        assertEquals(concept.questions().get(2), service.nextQuestion().orElseThrow());
        incorrect(service, 2);
        assertFalse(service.completed());
        assertEquals(1, service.progress().concept(concept.id()).credits());
        assertEquals(concept.questions().getFirst(), service.nextQuestion().orElseThrow());
        correct(service, 0);
        assertTrue(service.completed());
        assertEquals(20, service.xp());
    }

    @Test
    public void unlimitedIncorrectAttemptsAlwaysOfferAnotherQuestionUntilMastery() {
        ConceptProgressionService service = service();
        String previousId = null;
        for (int i = 0; i < 30; i++) {
            MultipleChoiceQuestion next = service.nextQuestion().orElseThrow();
            assertEquals(concept.questions().get(i % 3).id(), next.id());
            assertNotEquals(previousId, next.id());
            service.submit(next.id(), next.options().stream().filter(option -> !option.id().equals(next.correctOptionId()))
                    .findFirst().orElseThrow().id());
            previousId = next.id();
        }
        assertFalse(service.completed());
        assertEquals(0, service.xp());
        correct(service, 0);
        correct(service, 1);
        assertTrue(service.completed());
        assertEquals(20, service.xp());
    }

    @Test
    public void insufficientDistinctQuestionsExhaustEvenAfterCorrectAnswer() {
        AccountingConcept shortConcept = new AccountingConcept(concept.id(), concept.title(), concept.description(),
                List.of(concept.questions().getFirst()));
        ConceptProgressionService service = new ConceptProgressionService(new Assignment("short", "Short bank", List.of(shortConcept)));
        correct(service, 0);
        assertTrue(service.nextQuestion().isEmpty());
        assertFalse(service.completed());
        assertTrue(service.nextQuestion().isEmpty());
        assertEquals(1, service.progress().concept(concept.id()).credits());
        assertEquals(0, service.xp());
    }

    @Test
    public void navigationReopensSameSessionAssignmentAndProgress() {
        LearningSession session = new LearningSession();
        ConceptProgressionService firstScreen = session.open(assignment);
        correct(firstScreen, 0);
        // Screens may be destroyed/recreated when returning from MineOS; only the session is retained.
        ConceptProgressionService reopenedScreen = session.open(assignment);
        assertSame(firstScreen, reopenedScreen);
        assertEquals(1, reopenedScreen.progress().concept(concept.id()).credits());
        assertEquals(concept.questions().get(1), reopenedScreen.nextQuestion().orElseThrow());
        correct(reopenedScreen, 1);
        assertEquals(20, session.open(assignment).xp());
        assertTrue(session.open(assignment).completed());
        assertEquals(0, new LearningSession().open(assignment).xp());
    }

    @Test
    public void nextConceptUnlocksOnlyAfterCurrentConceptCompletes() {
        // A one-question synthetic concept also tests configurable completion requirements.
        MultipleChoiceQuestion secondQuestion = new MultipleChoiceQuestion("second.question", "second", "Fixture prompt",
                concept.questions().getFirst().options(), "shovels.inventory_payable", "Fixture explanation");
        AccountingConcept second = new AccountingConcept("second", "Second fixture", "Fixture", 1, List.of(secondQuestion));
        ConceptProgressionService service = new ConceptProgressionService(new Assignment("ordered", "Ordered fixture", List.of(concept, second)));
        assertThrows(IllegalStateException.class, () -> service.submit(secondQuestion.id(), secondQuestion.correctOptionId()));
        correct(service, 0);
        assertEquals(concept, service.currentConcept().orElseThrow());
        correct(service, 1);
        assertEquals(second, service.currentConcept().orElseThrow());
        assertFalse(service.completed());
        assertEquals(secondQuestion, service.nextQuestion().orElseThrow());
        assertEquals(20, service.submit(secondQuestion.id(), secondQuestion.correctOptionId()).xpAwarded());
        assertTrue(service.completed());
        assertEquals(40, service.xp());
    }

    @Test
    public void invalidQuestionDefinitionsAreRejected() {
        MultipleChoiceQuestion question = concept.questions().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new MultipleChoiceQuestion("id", concept.id(), "Prompt",
                question.options().subList(0, 3), question.correctOptionId(), "Explanation"));
        assertThrows(IllegalArgumentException.class, () -> new MultipleChoiceQuestion("id", concept.id(), "Prompt",
                question.options(), "missing-key", "Explanation"));
        assertThrows(IllegalArgumentException.class, () -> new MultipleChoiceQuestion("id", concept.id(), "Prompt",
                List.of(question.options().getFirst(), question.options().getFirst(), question.options().get(2), question.options().get(3)),
                question.correctOptionId(), "Explanation"));
        assertThrows(IllegalArgumentException.class, () -> new Assignment("id", "Assignment", List.of(concept, concept)));
    }

    @Test
    public void progressSnapshotsCannotBeMutatedAndOldSnapshotsRemainUnchanged() {
        ConceptProgressionService service = service();
        AssignmentProgress before = service.progress();
        correct(service, 0);
        assertEquals(0, before.concept(concept.id()).credits());
        assertThrows(UnsupportedOperationException.class, () -> service.progress().concepts().clear());
        assertThrows(UnsupportedOperationException.class, () -> service.progress().concept(concept.id()).correctQuestionIds().clear());
    }

    @Test
    public void unknownQuestionIsRejected() {
        ConceptProgressionService service = service();
        assertThrows(IllegalArgumentException.class, () -> service.submit("missing", "shovels.inventory_payable"));
    }

    @Test
    public void immediateRetryCanEarnCreditButNeverTwiceForSameQuestion() {
        ConceptProgressionService service = service();
        for (int i = 0; i < 10; i++) incorrect(service, 0);
        assertEquals(0, service.progress().concept(concept.id()).credits());
        assertTrue(correct(service, 0).creditEarned());
        assertFalse(correct(service, 0).creditEarned());
        for (int i = 0; i < 10; i++) incorrect(service, 1);
        assertEquals(1, service.progress().concept(concept.id()).credits());
        assertEquals(20, correct(service, 1).xpAwarded());
    }

    @Test
    public void onlyRemainingUncreditedQuestionCanBeRetriedUntilCorrect() {
        MultipleChoiceQuestion question = concept.questions().getFirst();
        AccountingConcept single = new AccountingConcept(concept.id(), concept.title(), concept.description(), 1, List.of(question));
        ConceptProgressionService service = new ConceptProgressionService(new Assignment("single", "Single", List.of(single)));
        for (int i = 0; i < 5; i++) {
            incorrect(service, 0);
            assertEquals(question, service.nextQuestion().orElseThrow());
        }
        correct(service, 0);
        assertTrue(service.completed());
    }

    @Test
    public void twoPlayableConceptsUnlockAndAwardFortyXpWithVerifiedPaymentAnswers() {
        Assignment full = InventoryCreditAssignment.ASSIGNMENT;
        assertEquals(2, full.concepts().size());
        AccountingConcept payments = full.concepts().get(1);
        assertEquals(SupplierPaymentConcept.CONCEPT_ID, payments.id());
        assertEquals(3, payments.questions().size());
        assertEquals(2, payments.requiredCorrectAnswers());
        List<String> correctIds = List.of("settlement.payable_cash", "partial.payable_cash", "invoices.payable_cash");
        List<String> texts = List.of("Debit Accounts Payable 60; Credit Cash 60.",
                "Debit Accounts Payable 30; Credit Cash 30.", "Debit Accounts Payable 36; Credit Cash 36.");
        assertEquals(50, 80 - 30);
        assertEquals(36, 20 + 16);
        for (int i = 0; i < 3; i++) {
            MultipleChoiceQuestion question = payments.questions().get(i);
            assertEquals(correctIds.get(i), question.correctOptionId());
            assertEquals(texts.get(i), question.options().stream().filter(option -> option.id().equals(question.correctOptionId()))
                    .findFirst().orElseThrow().text());
            assertTrue(grader.grade(question, correctIds.get(i)).correct());
        }
        LearningSession session = new LearningSession();
        ConceptProgressionService service = session.open(full);
        assertThrows(IllegalStateException.class, () -> service.submit(payments.questions().getFirst().id(), correctIds.getFirst()));
        correct(service, 0);
        correct(service, 1);
        assertEquals(1, service.completedConcepts());
        assertEquals(20, service.xp());
        assertFalse(service.completed());
        assertEquals(payments, service.currentConcept().orElseThrow());
        for (int i = 0; i < 2; i++) {
            MultipleChoiceQuestion question = payments.questions().get(i);
            service.submit(question.id(), correctIds.get(i));
        }
        assertTrue(session.open(full).completed());
        assertEquals(2, service.completedConcepts());
        assertEquals(40, service.xp());
        assertEquals(0, service.submit(payments.questions().getFirst().id(), correctIds.getFirst()).xpAwarded());
        assertEquals(40, service.xp());
    }
}
