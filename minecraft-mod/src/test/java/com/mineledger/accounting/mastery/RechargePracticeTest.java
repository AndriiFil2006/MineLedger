package com.mineledger.accounting.mastery;

import static org.junit.Assert.*;
import org.junit.Test;

public class RechargePracticeTest {
    private ConceptProgressionService completedAssignment() {
        var service = new ConceptProgressionService(InventoryCreditAssignment.ASSIGNMENT);
        for (var concept : service.assignment().concepts()) {
            for (int i = 0; i < concept.requiredCorrectAnswers(); i++) {
                var question = concept.questions().get(i);
                service.submit(question.id(), question.correctOptionId());
            }
        }
        return service;
    }

    @Test
    public void rechargeIsAvailableOnlyAfterAssignmentCompletion() {
        assertThrows(IllegalStateException.class,
                () -> new RechargePractice(new ConceptProgressionService(InventoryCreditAssignment.ASSIGNMENT)));
        assertTrue(new RechargePractice(completedAssignment()).currentQuestion().isPresent());
    }

    @Test
    public void reviewsEveryQuestionIncludingThoseNotRequiredForMastery() {
        var service = completedAssignment();
        var before = service.progress();
        var practice = new RechargePractice(service);
        assertEquals(6, practice.totalQuestions());
        var seen = new java.util.HashSet<String>();
        while (!practice.completed()) {
            var question = practice.currentQuestion().orElseThrow();
            assertTrue(seen.add(question.id()));
            assertTrue(practice.submit(question.correctOptionId()).correct());
            practice.continuePractice();
        }
        assertEquals(6, practice.reviewedQuestions());
        assertTrue(practice.currentQuestion().isEmpty());
        assertSame(before, service.progress());
        assertEquals(40, service.xp());
    }

    @Test
    public void missedQuestionReturnsLaterUntilCorrect() {
        var practice = new RechargePractice(completedAssignment());
        var first = practice.currentQuestion().orElseThrow();
        String wrong = first.options().stream().filter(option -> !option.id().equals(first.correctOptionId())).findFirst().orElseThrow().id();
        assertFalse(practice.submit(wrong).correct());
        practice.continuePractice();
        assertEquals(0, practice.reviewedQuestions());
        assertNotEquals(first.id(), practice.currentQuestion().orElseThrow().id());
        for (int i = 0; i < 5; i++) {
            var question = practice.currentQuestion().orElseThrow();
            practice.submit(question.correctOptionId());
            practice.continuePractice();
        }
        assertEquals(first, practice.currentQuestion().orElseThrow());
        for (int i = 0; i < 10; i++) {
            practice.submit(wrong);
            practice.continuePractice();
            assertEquals(first, practice.currentQuestion().orElseThrow());
        }
        practice.submit(first.correctOptionId());
        practice.continuePractice();
        assertTrue(practice.completed());
    }

    @Test
    public void invalidAndDuplicateSubmissionsDoNotAdvancePractice() {
        var practice = new RechargePractice(completedAssignment());
        var first = practice.currentQuestion().orElseThrow();
        assertFalse(practice.submit("not-an-option").valid());
        assertFalse(practice.submit(null).valid());
        assertThrows(IllegalStateException.class, practice::continuePractice);
        var result = practice.submit(first.correctOptionId());
        assertSame(result, practice.submit("another-option"));
        assertEquals(0, practice.reviewedQuestions());
        practice.continuePractice();
        assertEquals(1, practice.reviewedQuestions());
        assertThrows(IllegalStateException.class, practice::continuePractice);
    }

    @Test
    public void repeatedPracticeRoundsNeverChangeMasteryOrAwardAdditionalXp() {
        var service = completedAssignment();
        var before = service.progress();
        for (int round = 0; round < 3; round++) {
            var practice = new RechargePractice(service);
            while (!practice.completed()) {
                practice.submit(practice.currentQuestion().orElseThrow().correctOptionId());
                practice.continuePractice();
            }
            assertSame(before, service.progress());
            assertEquals(40, service.xp());
            assertTrue(service.completed());
        }
    }
}
