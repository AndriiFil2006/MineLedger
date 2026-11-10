package com.mineledger.education;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import com.mineledger.accounting.mastery.AccountingConcept;
import com.mineledger.accounting.mastery.Assignment;
import com.mineledger.accounting.mastery.AssignmentProgress;
import com.mineledger.accounting.mastery.ConceptProgressionService;
import com.mineledger.accounting.mastery.InventoryCreditAssignment;
import com.mineledger.accounting.mastery.LearningSession;
import com.mineledger.accounting.mastery.MultipleChoiceQuestion;
import com.mineledger.accounting.mastery.RechargePractice;

public class AssignmentDirectoryTest {
    private final CourseCatalog catalog = Accounting101.CATALOG;
    private final Assignment playable = InventoryCreditAssignment.ASSIGNMENT;
    private final AssignmentDirectory directory = new AssignmentDirectory(catalog);

    private void complete(ConceptProgressionService service) {
        for (var concept : service.assignment().concepts()) {
            for (int i = 0; i < concept.requiredCorrectAnswers(); i++) {
                var question = concept.questions().get(i);
                service.submit(question.id(), question.correctOptionId());
            }
        }
    }

    @Test
    public void groupsFollowCatalogCourseUnitAndAssignmentOrder() {
        var groups = directory.groups(new LearningSession());
        assertEquals(3, groups.size());
        assertEquals(List.of(1, 2, 3), groups.stream().map(group -> group.unit().number()).toList());
        for (var group : groups) {
            assertEquals(Accounting101.COURSE, group.course());
            assertEquals(group.unit().assignments(), group.entries().stream().map(AssignmentDirectory.Entry::assignment).toList());
        }
        assertEquals(8, groups.stream().mapToInt(group -> group.entries().size()).sum());
        assertEquals(7, groups.stream().flatMap(group -> group.entries().stream())
                .filter(entry -> entry.action() == AssignmentDirectory.Action.COMING_SOON).count());
    }

    @Test
    public void startContinueAndRechargeResolveSelectedAssignmentId() {
        var session = new LearningSession();
        var start = directory.select(playable.id(), session).orElseThrow();
        assertEquals(playable.id(), start.service().assignment().id());
        assertEquals(AssignmentDirectory.Action.START, start.action());
        assertFalse(start.recharge());
        var question = playable.concepts().getFirst().questions().getFirst();
        start.service().submit(question.id(), question.correctOptionId());
        var resumed = directory.select(playable.id(), session).orElseThrow();
        assertSame(start.service(), resumed.service());
        assertEquals(AssignmentDirectory.Action.CONTINUE, resumed.action());
        complete(start.service());
        var recharge = directory.select(playable.id(), session).orElseThrow();
        assertEquals(AssignmentDirectory.Action.RECHARGE, recharge.action());
        assertTrue(recharge.recharge());
        var entry = directory.entries(Accounting101.COURSE.units().get(1), session).getFirst();
        assertEquals(2, entry.completedConcepts());
        assertEquals(2, entry.totalConcepts());
        assertEquals(AssignmentDirectory.Action.RECHARGE, entry.action());
    }

    @Test
    public void unknownAndUnavailableIdsNeverLaunchOrSaveProgress() {
        var writes = new AtomicInteger();
        var session = new LearningSession(Map.of(), writes::incrementAndGet);
        for (var group : directory.groups(session)) {
            for (var entry : group.entries()) {
                if (entry.action() != AssignmentDirectory.Action.COMING_SOON) continue;
                assertTrue(directory.select(entry.assignment().id(), session).isEmpty());
                assertEquals(0, entry.totalConcepts());
            }
        }
        assertTrue(directory.select("unknown", session).isEmpty());
        assertEquals(0, writes.get());
    }

    @Test
    public void coursesAndAssignmentsUseTheSameServiceWithNoOpeningRewards() {
        var writes = new AtomicInteger();
        var session = new LearningSession(Map.of(), writes::incrementAndGet);
        var courseRoute = catalog.launch(playable.id(), session).orElseThrow();
        var assignmentRoute = directory.select(playable.id(), session).orElseThrow();
        assertSame(courseRoute, assignmentRoute.service());
        assertEquals(0, writes.get());
        var question = playable.concepts().getFirst().questions().getFirst();
        courseRoute.submit(question.id(), question.correctOptionId());
        var before = courseRoute.progress();
        int saved = writes.get();
        for (int i = 0; i < 5; i++) {
            directory.groups(session);
            directory.entries(Accounting101.COURSE.units().get(1), session);
            directory.select(playable.id(), session);
        }
        assertSame(before, courseRoute.progress());
        assertEquals(saved, writes.get());
        assertEquals(0, courseRoute.xp());
    }

    @Test
    public void restoredPartialProgressListsContinueWithoutChangingCreditsOrXp() {
        var original = new ConceptProgressionService(playable);
        var question = playable.concepts().getFirst().questions().getFirst();
        original.submit(question.id(), question.correctOptionId());
        AssignmentProgress before = original.progress();
        var writes = new AtomicInteger();
        var restored = new LearningSession(Map.of(playable.id(), before), writes::incrementAndGet);
        var selected = directory.select(playable.id(), restored).orElseThrow();
        assertEquals(AssignmentDirectory.Action.CONTINUE, selected.action());
        assertEquals(before, selected.service().progress());
        assertEquals(1, selected.service().progress().concept(question.conceptId()).credits());
        assertEquals(0, selected.service().xp());
        assertEquals(0, writes.get());
    }

    @Test
    public void rechargeSelectionNeverChangesMasteryXpOrSaves() {
        var writes = new AtomicInteger();
        var session = new LearningSession(Map.of(), writes::incrementAndGet);
        var service = session.open(playable);
        complete(service);
        var before = service.progress();
        int count = writes.get();
        var selected = directory.select(playable.id(), session).orElseThrow();
        assertTrue(selected.recharge());
        var practice = new RechargePractice(selected.service());
        while (!practice.completed()) {
            practice.submit(practice.currentQuestion().orElseThrow().correctOptionId());
            practice.continuePractice();
        }
        assertSame(before, service.progress());
        assertEquals(40, service.xp());
        assertEquals(count, writes.get());
    }

    @Test
    public void newCatalogAssignmentAutomaticallyAppearsAndRoutesToItsOwnService() {
        // Synthetic fixture only; no new course content is shipped.
        var source = playable.concepts().getFirst();
        var questions = source.questions().stream().map(question -> new MultipleChoiceQuestion("future." + question.id(),
                "future.concept", question.prompt(), question.options(), question.correctOptionId(), question.explanation())).toList();
        var future = new Assignment("future.assignment", "future.unit", "Future fixture", "Test fixture",
                Assignment.Availability.AVAILABLE, List.of(new AccountingConcept("future.concept", "Fixture", "Test fixture", questions)));
        var course = new Course("future.course", "Fixture course", "Test fixture", List.of(new Unit("future.unit", "future.course",
                1, "Fixture unit", "Test fixture", List.of(future))));
        var extended = new CourseCatalog(List.of(Accounting101.COURSE, course));
        var listing = new AssignmentDirectory(extended);
        var session = new LearningSession();
        assertEquals(4, listing.groups(session).size());
        var entry = listing.groups(session).getLast().entries().getFirst();
        assertEquals(future, entry.assignment());
        assertEquals(AssignmentDirectory.Action.START, entry.action());
        assertSame(session.open(future), listing.select(future.id(), session).orElseThrow().service());
        assertNotSame(session.open(playable), listing.select(future.id(), session).orElseThrow().service());
        var question = future.concepts().getFirst().questions().getFirst();
        listing.select(future.id(), session).orElseThrow().service().submit(question.id(), question.correctOptionId());
        assertEquals(AssignmentDirectory.Action.CONTINUE, listing.groups(session).getLast().entries().getFirst().action());
        assertTrue(session.open(playable).progress().concepts().isEmpty());
    }
}
