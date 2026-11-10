package com.mineledger.education;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.mineledger.accounting.IntroJournalAssignment;
import com.mineledger.accounting.mastery.AccountingConcept;
import com.mineledger.accounting.mastery.Assignment;
import com.mineledger.accounting.mastery.ConceptProgressionService;
import com.mineledger.accounting.mastery.InventoryCreditAssignment;
import com.mineledger.accounting.mastery.LearningSession;
import com.mineledger.accounting.mastery.MultipleChoiceQuestion;
import com.mineledger.accounting.mastery.RechargePractice;
import com.mineledger.progress.JsonProgressRepository;
import com.mineledger.progress.ProfileLearningProgress;

public class CourseHierarchyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final CourseCatalog catalog = Accounting101.CATALOG;
    private final Assignment playable = InventoryCreditAssignment.ASSIGNMENT;

    private void complete(ConceptProgressionService service) {
        for (var concept : service.assignment().concepts()) {
            for (int i = 0; i < concept.requiredCorrectAnswers(); i++) {
                var question = concept.questions().get(i);
                service.submit(question.id(), question.correctOptionId());
            }
        }
    }

    @Test
    public void sampleHasOneCourseThreeOrderedUnitsAndEightAssignments() {
        assertEquals(1, catalog.courses().size());
        assertEquals("Accounting 101 — Financial Accounting", Accounting101.COURSE.title());
        assertEquals(List.of(1, 2, 3), Accounting101.COURSE.units().stream().map(Unit::number).toList());
        assertEquals(List.of(3, 3, 2), Accounting101.COURSE.units().stream().map(unit -> unit.assignments().size()).toList());
        assertEquals(8, Accounting101.COURSE.units().stream().mapToInt(unit -> unit.assignments().size()).sum());
        assertEquals(List.of(playable), catalog.availableAssignments());
        for (var unit : Accounting101.COURSE.units()) {
            assertEquals(Accounting101.COURSE_ID, unit.courseId());
            for (var assignment : unit.assignments()) assertEquals(unit.id(), assignment.unitId());
        }
    }

    @Test
    public void stableLookupAndRoutingPreserveOriginalAssignmentAndQuestions() {
        var session = new LearningSession();
        assertSame(Accounting101.COURSE, catalog.course(Accounting101.COURSE_ID).orElseThrow());
        assertEquals("Recording Transactions", catalog.unit(Accounting101.UNIT_2_ID).orElseThrow().title());
        assertSame(playable, catalog.assignment("inventory_fundamentals").orElseThrow());
        assertSame(playable, catalog.unit(Accounting101.UNIT_2_ID).orElseThrow().assignments().getFirst());
        assertEquals("Assignment 2.1: Journal Entries", playable.title());
        assertSame(session.open(playable), catalog.launch(playable.id(), session).orElseThrow());
        assertEquals(2, playable.concepts().size());
        assertEquals(List.of("inventory.shovels", "inventory.books_discount", "inventory.lanterns_delivery",
                "payables.shovels_settlement", "payables.books_partial", "payables.two_invoices"),
                playable.concepts().stream().flatMap(concept -> concept.questions().stream()).map(MultipleChoiceQuestion::id).toList());
    }

    @Test
    public void comingSoonHasNoExercisesCannotLaunchAndIsNotFalselyCompleted() {
        var session = new LearningSession();
        var progress = new CourseProgress(session);
        int placeholders = 0;
        for (var unit : Accounting101.COURSE.units()) {
            for (var assignment : unit.assignments()) {
                if (assignment.available()) continue;
                placeholders++;
                assertTrue(assignment.concepts().isEmpty());
                assertTrue(catalog.launch(assignment.id(), session).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> session.open(assignment));
                assertEquals(new CourseProgress.Count(0, 0), progress.assignment(assignment));
            }
        }
        assertEquals(7, placeholders);
        assertEquals(0, progress.unit(Accounting101.COURSE.units().getFirst()).percent());
        assertEquals("No available assignments", progress.unit(Accounting101.COURSE.units().getFirst()).assignmentsLabel());
    }

    @Test
    public void aggregatesUseAvailableAssignmentCountsNotPlaceholderCounts() {
        var session = new LearningSession();
        var progress = new CourseProgress(session);
        assertEquals(new CourseProgress.Count(0, 1), progress.course(Accounting101.COURSE));
        var service = session.open(playable);
        var first = playable.concepts().getFirst();
        for (int i = 0; i < 2; i++) service.submit(first.questions().get(i).id(), first.questions().get(i).correctOptionId());
        assertEquals(new CourseProgress.Count(1, 2), progress.assignment(playable));
        assertEquals(new CourseProgress.Count(0, 1), progress.unit(Accounting101.COURSE.units().get(1)));
        complete(service);
        assertEquals(new CourseProgress.Count(1, 1), progress.course(Accounting101.COURSE));
        assertEquals(100, progress.course(Accounting101.COURSE).percent());
    }

    @Test
    public void differentConceptCountsAndMultipleUnitsAggregateWithoutAssumingThirtyConcepts() {
        var source = playable.concepts().getFirst();
        var questions = source.questions().stream().map(question -> new MultipleChoiceQuestion("fixture." + question.id(),
                "fixture.concept", question.prompt(), question.options(), question.correctOptionId(), question.explanation())).toList();
        var sibling = new Assignment("fixture.assignment", "fixture.unit", "Fixture", "Fixture description",
                Assignment.Availability.AVAILABLE, List.of(new AccountingConcept("fixture.concept", "Fixture", "Fixture description", questions)));
        var unit = new Unit("fixture.unit", Accounting101.COURSE_ID, 4, "Fixture unit", "Fixture", List.of(sibling));
        var course = new Course(Accounting101.COURSE_ID, "Fixture course", "Fixture", List.of(Accounting101.COURSE.units().get(1), unit));
        new CourseCatalog(List.of(course));
        var session = new LearningSession();
        var progress = new CourseProgress(session);
        assertEquals(2, progress.assignment(playable).total());
        assertEquals(1, progress.assignment(sibling).total());
        complete(session.open(playable));
        assertEquals(new CourseProgress.Count(1, 2), progress.course(course));
        assertEquals(50, progress.course(course).percent());
        assertEquals(new CourseProgress.Count(0, 1), progress.unit(unit));
    }

    @Test
    public void unknownNavigationIdsAreSafeAndBackKeepsCorrectHierarchy() {
        var navigation = new CourseNavigation(catalog);
        assertFalse(navigation.openUnit(Accounting101.UNIT_2_ID));
        assertFalse(navigation.openCourse("missing"));
        assertTrue(navigation.openCourse(Accounting101.COURSE_ID));
        assertTrue(navigation.openUnit(Accounting101.UNIT_2_ID));
        assertFalse(navigation.openUnit("missing"));
        assertEquals(Accounting101.UNIT_2_ID, navigation.unit().orElseThrow().id());
        assertTrue(navigation.back());
        assertTrue(navigation.unit().isEmpty());
        assertTrue(navigation.course().isPresent());
        assertTrue(navigation.back());
        assertFalse(navigation.back());
        assertTrue(catalog.assignment("missing").isEmpty());
        assertTrue(catalog.launch("missing", new LearningSession()).isEmpty());
    }

    @Test
    public void invalidParentsOrderingAndDuplicateIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Unit("wrong-parent", Accounting101.COURSE_ID,
                1, "Unit", "Description", List.of(playable)));
        assertThrows(IllegalArgumentException.class, () -> new Course("wrong-parent", "Course", "Description", Accounting101.COURSE.units()));
        assertThrows(IllegalArgumentException.class, () -> new CourseCatalog(List.of(Accounting101.COURSE, Accounting101.COURSE)));
        assertThrows(IllegalArgumentException.class, () -> new Course(Accounting101.COURSE_ID, "Course", "Description",
                List.of(Accounting101.COURSE.units().get(1), Accounting101.COURSE.units().getFirst())));
        assertThrows(IllegalArgumentException.class, () -> new Assignment("empty", Accounting101.UNIT_1_ID, "Empty", "Description",
                Assignment.Availability.AVAILABLE, List.of()));
    }

    @Test
    public void oldSchemaOneSaveKeepsPartialCreditsRewardsAndRechargeAcrossCourseNavigation() throws Exception {
        UUID profile = UUID.fromString("11111111-2222-3333-4444-555555555555");
        var repository = new JsonProgressRepository(temporary.getRoot().toPath());
        Files.createDirectories(repository.file(profile).getParent());
        String fixture;
        try (var input = getClass().getResourceAsStream("/progress/schema1-before-courses.json")) {
            assertNotNull(input);
            fixture = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Files.writeString(repository.file(profile), fixture);
        var warnings = new java.util.ArrayList<String>();
        Set<String> journalIds = IntroJournalAssignment.QUESTIONS.stream().map(question -> question.id())
                .collect(java.util.stream.Collectors.toSet());
        var learning = new ProfileLearningProgress(profile, repository, catalog.availableAssignments(), journalIds, warnings::add);
        var service = catalog.launch("inventory_fundamentals", learning.mastery()).orElseThrow();
        var directory = new AssignmentDirectory(catalog);
        assertSame(service, directory.select("inventory_fundamentals", learning.mastery()).orElseThrow().service());
        assertEquals(AssignmentDirectory.Action.CONTINUE,
                directory.select("inventory_fundamentals", learning.mastery()).orElseThrow().action());
        assertEquals(20, service.xp());
        assertEquals(1, service.progress().concept("payables.cash_payments").credits());
        assertEquals("payables.books_partial", service.nextQuestion().orElseThrow().id());
        var before = learning.snapshot();
        var navigator = new CourseNavigation(catalog);
        for (int i = 0; i < 3; i++) {
            navigator.openCourse(Accounting101.COURSE_ID);
            navigator.openUnit(Accounting101.UNIT_2_ID);
            assertSame(service, catalog.launch(playable.id(), learning.mastery()).orElseThrow());
            new CourseProgress(learning.mastery()).course(Accounting101.COURSE);
            directory.groups(learning.mastery());
            navigator.back();
            navigator.back();
        }
        learning.save();
        assertEquals(before, learning.snapshot());
        assertEquals(fixture, Files.readString(repository.file(profile)));
        complete(service);
        assertEquals(40, service.xp());
        var reopened = new ProfileLearningProgress(profile, new JsonProgressRepository(temporary.getRoot().toPath()),
                catalog.availableAssignments(), journalIds, warnings::add);
        var completed = catalog.launch(playable.id(), reopened.mastery()).orElseThrow();
        assertTrue(completed.completed());
        assertTrue(directory.select(playable.id(), reopened.mastery()).orElseThrow().recharge());
        var persisted = Files.readString(repository.file(profile));
        var practice = new RechargePractice(completed);
        while (!practice.completed()) {
            practice.submit(practice.currentQuestion().orElseThrow().correctOptionId());
            practice.continuePractice();
        }
        reopened.save();
        assertEquals(persisted, Files.readString(repository.file(profile)));
        assertEquals(40, completed.xp());
        assertTrue(warnings.isEmpty());
    }
}
