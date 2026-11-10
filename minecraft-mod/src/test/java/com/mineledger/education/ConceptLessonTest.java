package com.mineledger.education;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import com.mineledger.accounting.mastery.AssignmentProgress;
import com.mineledger.accounting.mastery.ConceptProgressionService;
import com.mineledger.accounting.mastery.InventoryCreditAssignment;
import com.mineledger.accounting.mastery.RechargePractice;
import com.mineledger.accounting.mastery.SupplierPaymentConcept;
import com.mineledger.education.lesson.ConceptLesson;
import com.mineledger.education.lesson.ConceptLessonCatalog;
import com.mineledger.education.lesson.IntroConceptLessons;
import com.mineledger.presentation.PracticeQuestionState;

public class ConceptLessonTest {
    private final ConceptLesson lesson = IntroConceptLessons.INVENTORY_ON_CREDIT;

    @Test
    public void lookupUsesExactConceptIdAndNeverAnUnrelatedLesson() {
        assertSame(lesson, IntroConceptLessons.CATALOG.forConcept(InventoryCreditAssignment.CONCEPT_ID).orElseThrow());
        assertEquals(InventoryCreditAssignment.CONCEPT_ID, lesson.conceptId());
        assertTrue(IntroConceptLessons.CATALOG.forConcept(SupplierPaymentConcept.CONCEPT_ID).isEmpty());
        assertTrue(IntroConceptLessons.CATALOG.forConcept("missing").isEmpty());
        assertTrue(IntroConceptLessons.CATALOG.forConcept(null).isEmpty());
        var state = new PracticeQuestionState();
        state.show(InventoryCreditAssignment.ASSIGNMENT.concepts().get(1).questions().getFirst());
        assertTrue(state.lesson(id -> java.util.Optional.of(lesson)).isEmpty());
    }

    @Test
    public void workedExampleAndPerpetualInventoryLessonAreCorrect() {
        assertEquals(1, lesson.workedExamples().size());
        var example = lesson.workedExamples().getFirst();
        assertEquals(60, 4 * 15);
        assertEquals("4 × 15 = 60 emeralds.", example.calculation());
        assertEquals("Debit Inventory 60.", example.debitEntry());
        assertEquals("Credit Accounts Payable 60.", example.creditEntry());
        assertTrue(lesson.learningObjective().contains("perpetual inventory system"));
        assertTrue(lesson.keyRules().stream().anyMatch(rule -> rule.contains("Assets increase with debits")));
        assertTrue(lesson.keyRules().stream().anyMatch(rule -> rule.contains("Liabilities increase with credits")));
        assertTrue(lesson.commonMistakes().stream().anyMatch(mistake -> mistake.contains("Do not credit Cash")));
        assertTrue(lesson.commonMistakes().stream().anyMatch(mistake -> mistake.contains("Do not debit Equipment")));
    }

    @Test
    public void readingRetainsCurrentQuestionAndSelectedOptionBeforeSubmission() {
        var state = new PracticeQuestionState();
        var question = InventoryCreditAssignment.ASSIGNMENT.concepts().getFirst().questions().get(1);
        state.show(question);
        state.select(question.options().getFirst().id());
        var before = state.snapshot();
        for (int i = 0; i < 5; i++) assertSame(lesson, state.lesson(IntroConceptLessons.CATALOG).orElseThrow());
        assertSame(before, state.snapshot());
        assertEquals(question.id(), state.question().id());
        assertEquals(question.options().getFirst().id(), state.selectedOptionId());
        assertNull(state.submission());
    }

    @Test
    public void readingAfterSubmissionPreservesFeedbackAndCannotGrantXpOrWriteProgress() {
        AtomicInteger writes = new AtomicInteger();
        var assignment = InventoryCreditAssignment.ASSIGNMENT;
        var service = new ConceptProgressionService(assignment, new AssignmentProgress(assignment.id(), Map.of()), writes::incrementAndGet);
        var state = new PracticeQuestionState();
        var first = assignment.concepts().getFirst().questions().getFirst();
        service.submit(first.id(), first.correctOptionId());
        var question = service.nextQuestion().orElseThrow();
        state.show(question);
        state.select(question.correctOptionId());
        var result = service.submit(question.id(), state.selectedOptionId());
        state.recordSubmission(result);
        assertEquals(20, result.xpAwarded());
        assertEquals(SupplierPaymentConcept.CONCEPT_ID, service.currentConcept().orElseThrow().id());
        var view = state.snapshot();
        var progress = service.progress();
        int saves = writes.get();
        for (int i = 0; i < 5; i++) assertSame(lesson, state.lesson(IntroConceptLessons.CATALOG).orElseThrow());
        assertSame(view, state.snapshot());
        assertSame(result, state.submission());
        assertSame(progress, service.progress());
        assertEquals(20, service.xp());
        assertEquals(saves, writes.get());
    }

    @Test
    public void readingDoesNotGrantInitialCreditOrAlterRetryHistory() {
        var service = new ConceptProgressionService(InventoryCreditAssignment.ASSIGNMENT);
        var state = new PracticeQuestionState();
        state.show(service.nextQuestion().orElseThrow());
        var progress = service.progress();
        state.lesson(IntroConceptLessons.CATALOG).orElseThrow();
        assertSame(progress, service.progress());
        assertEquals(0, service.xp());
        assertEquals(0, service.progress().concept(lesson.conceptId()).credits());
        assertSame(state.question(), service.nextQuestion().orElseThrow());
    }

    @Test
    public void rechargeQuestionSelectionAndGradedFeedbackSurviveLessonViewing() {
        var service = new ConceptProgressionService(InventoryCreditAssignment.ASSIGNMENT);
        for (var concept : service.assignment().concepts()) {
            for (int i = 0; i < concept.requiredCorrectAnswers(); i++) {
                var question = concept.questions().get(i);
                service.submit(question.id(), question.correctOptionId());
            }
        }
        var progress = service.progress();
        var practice = new RechargePractice(service);
        var state = new PracticeQuestionState();
        var question = practice.currentQuestion().orElseThrow();
        state.show(question);
        state.select(question.options().stream().filter(option -> !option.id().equals(question.correctOptionId())).findFirst().orElseThrow().id());
        state.recordSubmission(new ConceptProgressionService.Submission(practice.submit(state.selectedOptionId()), false, 0));
        var snapshot = state.snapshot();
        state.lesson(IntroConceptLessons.CATALOG).orElseThrow();
        assertSame(snapshot, state.snapshot());
        assertSame(question, practice.currentQuestion().orElseThrow());
        assertEquals(0, practice.reviewedQuestions());
        assertSame(progress, service.progress());
        assertEquals(40, service.xp());
        practice.continuePractice();
        assertNotEquals(question.id(), practice.currentQuestion().orElseThrow().id());
    }

    @Test
    public void emptyViewAndMissingContentAreHandledWithoutChangingState() {
        var state = new PracticeQuestionState();
        var before = state.snapshot();
        assertTrue(state.lesson(IntroConceptLessons.CATALOG).isEmpty());
        assertSame(before, state.snapshot());
        state.show(InventoryCreditAssignment.ASSIGNMENT.concepts().getFirst().questions().getFirst());
        before = state.snapshot();
        assertTrue(state.lesson(new ConceptLessonCatalog(List.of())).isEmpty());
        assertSame(before, state.snapshot());
    }

    @Test
    public void lessonCollectionsAreImmutableAndDuplicateMappingsAreRejected() {
        assertThrows(UnsupportedOperationException.class, () -> lesson.keyRules().clear());
        assertThrows(UnsupportedOperationException.class, () -> lesson.workedExamples().clear());
        assertThrows(IllegalArgumentException.class, () -> new ConceptLessonCatalog(List.of(lesson, lesson)));
        var sameConcept = new ConceptLesson("another-lesson", lesson.conceptId(), lesson.title(), lesson.learningObjective(),
                lesson.explanation(), lesson.keyRules(), lesson.workedExamples(), List.of(), "");
        assertThrows(IllegalArgumentException.class, () -> new ConceptLessonCatalog(List.of(lesson, sameConcept)));
        assertThrows(IllegalArgumentException.class, () -> new ConceptLesson("", lesson.conceptId(), lesson.title(), lesson.learningObjective(),
                lesson.explanation(), lesson.keyRules(), lesson.workedExamples(), List.of(), ""));
    }
}
