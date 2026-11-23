package com.mineledger.backend;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mineledger.accounting.IntroJournalAssignment;
import com.mineledger.accounting.mastery.Assignment;
import com.mineledger.backend.BackendException.Kind;
import com.mineledger.backend.FakeBackend.FakeApi;
import com.mineledger.backend.FakeBackend.OwnerThread;
import com.mineledger.backend.RemoteCurriculum.*;
import com.mineledger.backend.RemoteCurriculumBrowser.Connection;
import com.mineledger.backend.RemoteCurriculumBrowser.View;
import com.mineledger.curriculum.CurriculumContent;
import com.mineledger.education.Accounting101;
import com.mineledger.progress.JsonProgressRepository;
import com.mineledger.progress.ProfileLearningProgress;

/** Navigation, failure and stale-response behavior with responses and the "client thread" under test control. */
public class RemoteCurriculumBrowserTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private final FakeApi api = new FakeApi();
    private final OwnerThread owner = new OwnerThread();
    private final RemoteCurriculumBrowser browser = new RemoteCurriculumBrowser(api, owner);
    private int notifications;

    { browser.setListener(() -> notifications++); }

    private void settle() {
        while (api.respond() | owner.drain()) { }
    }

    private void openTo(View view) {
        browser.open();
        settle();
        if (view == View.COURSES) return;
        browser.openCourse("accounting101");
        settle();
        if (view == View.COURSE) return;
        browser.openUnit("accounting101.recording_transactions");
        settle();
        if (view == View.UNIT) return;
        browser.openAssignment("inventory_fundamentals");
        settle();
        assertEquals(Optional.empty(), browser.error());
    }

    private static String edited(String path, java.util.function.Consumer<JsonElement> edit) {
        JsonElement root = JsonParser.parseString(ApiFixtures.body(path));
        edit.accept(root);
        return root.toString();
    }

    private static BackendException unreachable() { return FakeBackend.unreachable(); }

    @Test
    public void connectsWithAHealthCheckThenListsBackendCourses() {
        assertEquals(Connection.CONNECTING, browser.connection());
        browser.open();
        assertTrue(browser.loading());
        assertEquals(Connection.CONNECTING, browser.connection());
        assertEquals(List.of(ApiFixtures.HEALTH), api.calls);
        assertEquals("Opening is silent; the screen is already building", 0, notifications);
        settle();
        assertEquals(List.of(ApiFixtures.HEALTH, ApiFixtures.COURSES), api.calls);
        assertEquals(Connection.CONNECTED, browser.connection());
        assertFalse(browser.loading());
        assertEquals(View.COURSES, browser.view());
        assertEquals(List.of("accounting101"), browser.courses().stream().map(CourseSummary::id).toList());
        assertEquals(1, notifications);
        browser.open();
        assertEquals("Reopening an open browser requests nothing", 2, api.calls.size());
    }

    @Test
    public void browsesCourseUnitAssignmentConceptsAndQuestionsWithTheLocalStableIds() {
        openTo(View.ASSIGNMENT);
        assertEquals(List.of(ApiFixtures.HEALTH, ApiFixtures.COURSES, ApiFixtures.COURSE, ApiFixtures.UNIT, ApiFixtures.ASSIGNMENT,
                ApiFixtures.CONCEPT_1, ApiFixtures.CONCEPT_2, ApiFixtures.QUESTIONS), api.calls);
        assertEquals(View.ASSIGNMENT, browser.view());

        // The backend imported the bundled curriculum, so every ID (and the text shown) must match it exactly.
        var local = Accounting101.COURSE;
        var course = browser.course().orElseThrow();
        assertEquals(local.id(), course.id());
        assertEquals(local.title(), course.title());
        assertEquals(local.units().stream().map(unit -> unit.id()).toList(), course.units().stream().map(UnitSummary::id).toList());
        for (int i = 0; i < local.units().size(); i++) {
            assertEquals(local.units().get(i).number(), course.units().get(i).number());
            assertEquals(local.units().get(i).assignments().stream().map(Assignment::id).toList(), course.units().get(i).assignmentIds());
        }
        var localUnit = local.units().get(1);
        var unit = browser.unit().orElseThrow();
        assertEquals(localUnit.id(), unit.id());
        for (int i = 0; i < localUnit.assignments().size(); i++) {
            assertEquals(localUnit.assignments().get(i).id(), unit.assignments().get(i).id());
            assertEquals(localUnit.assignments().get(i).title(), unit.assignments().get(i).title());
            assertEquals(localUnit.assignments().get(i).availability(), unit.assignments().get(i).availability());
        }
        var localAssignment = Accounting101.CATALOG.assignment("inventory_fundamentals").orElseThrow();
        var preview = browser.assignment().orElseThrow();
        assertEquals(localAssignment.id(), preview.assignment().id());
        assertEquals(localAssignment.concepts().stream().map(concept -> concept.id()).toList(),
                preview.concepts().stream().map(ConceptDetail::id).toList());
        var localQuestions = localAssignment.concepts().stream().flatMap(concept -> concept.questions().stream()).toList();
        assertEquals(6, preview.questions().size());
        for (int i = 0; i < localQuestions.size(); i++) {
            var expected = localQuestions.get(i);
            var actual = preview.questions().get(i);
            assertEquals(expected.id(), actual.id());
            assertEquals(expected.conceptId(), actual.conceptId());
            assertEquals(expected.prompt(), actual.prompt());
            assertEquals(expected.options(), actual.options());
            assertFalse("No answer key or explanation reaches the preview", actual.toString().contains(expected.explanation()));
        }
        assertEquals(CurriculumContent.get().lessons().forConcept("inventory.purchases_on_credit"), preview.concepts().get(0).lesson());

        browser.showQuestion(0);
        assertEquals(View.QUESTION, browser.view());
        browser.showQuestion(5);
        assertEquals(5, browser.questionIndex());
        browser.showQuestion(6);
        browser.showQuestion(-1);
        assertEquals("Out-of-range indexes are ignored", 5, browser.questionIndex());

        assertTrue(browser.back());
        assertEquals(View.ASSIGNMENT, browser.view());
        assertTrue(browser.back());
        assertEquals(View.UNIT, browser.view());
        assertTrue(browser.back());
        assertEquals(View.COURSE, browser.view());
        assertTrue(browser.back());
        assertEquals(View.COURSES, browser.view());
        assertFalse(browser.back());
        assertEquals("Going back never refetches", 8, api.calls.size());
    }

    @Test
    public void responsesChangeNothingUntilTheOwnerThreadRunsThem() {
        browser.open();
        api.respond();
        assertTrue("Completed on a background thread, not yet applied", browser.loading());
        assertTrue(browser.courses().isEmpty());
        assertEquals(Connection.CONNECTING, browser.connection());
        assertEquals(0, notifications);
        owner.drain();
        assertFalse(browser.loading());
        assertEquals(1, browser.courses().size());
        assertEquals(1, notifications);
    }

    @Test
    public void aClosedScreenIsNeverUpdatedByLateResponses() {
        browser.open();
        browser.close();
        settle();
        assertTrue(browser.courses().isEmpty());
        assertEquals(Connection.CONNECTING, browser.connection());
        assertEquals(Optional.empty(), browser.error());
        assertEquals(0, notifications);
    }

    @Test
    public void aResponseAlreadyQueuedForTheClientThreadIsDroppedIfTheScreenClosesFirst() {
        openTo(View.COURSES);
        browser.openCourse("accounting101");
        int before = notifications;
        api.respond(); // the delivery task is now waiting on the owner thread
        browser.close();
        owner.drain();
        assertTrue(browser.course().isEmpty());
        assertEquals(before, notifications);
    }

    @Test
    public void aLateFailureAfterCloseIsAlsoIgnored() {
        openTo(View.COURSES);
        api.failEverything = unreachable();
        browser.openCourse("accounting101");
        int before = notifications;
        browser.close();
        settle();
        assertEquals(Optional.empty(), browser.error());
        assertEquals(Connection.CONNECTED, browser.connection());
        assertEquals(before, notifications);
    }

    @Test
    public void reopeningAfterAnInterruptedFirstLoadStartsAgain() {
        browser.open();
        browser.close();
        settle();
        browser.open();
        assertTrue(browser.loading());
        settle();
        assertEquals(1, browser.courses().size());
        assertEquals(Connection.CONNECTED, browser.connection());
    }

    @Test
    public void cancellingALoadDiscardsItsResponse() {
        openTo(View.COURSES);
        browser.openCourse("accounting101");
        assertTrue(browser.loading());
        assertTrue(browser.back());
        assertFalse(browser.loading());
        int before = notifications;
        settle();
        assertEquals(View.COURSES, browser.view());
        assertTrue(browser.course().isEmpty());
        assertEquals(before, notifications);
    }

    @Test
    public void onlyTheNewestRequestIsApplied() {
        openTo(View.COURSES);
        browser.openCourse("accounting101"); // abandoned below
        browser.back();
        api.bodies.put(ApiFixtures.COURSES, "[]");
        browser.reloadCourses(); // newest request: the backend now has no courses
        int before = notifications;
        settle(); // the abandoned course response arrives alongside the reload
        assertEquals(View.COURSES, browser.view());
        assertTrue("The stale course detail was not applied", browser.course().isEmpty());
        assertTrue(browser.courses().isEmpty());
        assertEquals(Optional.empty(), browser.error());
        assertEquals(Connection.CONNECTED, browser.connection());
        assertEquals(before + 1, notifications);
    }

    @Test
    public void navigationIsIgnoredWhileALoadIsPending() {
        openTo(View.COURSE);
        browser.openUnit("accounting101.recording_transactions");
        int calls = api.calls.size();
        browser.openUnit("accounting101.fundamentals");
        browser.openAssignment("inventory_fundamentals");
        browser.showQuestion(0);
        assertEquals(calls, api.calls.size());
        settle();
        assertEquals("accounting101.recording_transactions", browser.unit().orElseThrow().id());
    }

    @Test
    public void anOfflineBackendIsReportedAndNeverReplacedByLocalContent() {
        api.failEverything = unreachable();
        browser.open();
        settle();
        assertEquals(Connection.UNAVAILABLE, browser.connection());
        assertEquals(Optional.of("Cannot reach the backend. Is the server running?"), browser.error());
        assertTrue(browser.courses().isEmpty());
        assertTrue(browser.course().isEmpty());
        assertTrue(browser.canRetry());
        assertFalse("There is nothing to go back to", browser.canGoBack());
        assertFalse(browser.back());
        assertEquals(List.of(ApiFixtures.HEALTH), api.calls);
        assertEquals(1, notifications);

        browser.retry(); // still down
        assertEquals(Connection.CONNECTING, browser.connection());
        assertEquals(Optional.empty(), browser.error());
        settle();
        assertEquals(Connection.UNAVAILABLE, browser.connection());

        api.failEverything = null; // the server came back
        browser.retry();
        settle();
        assertEquals(Connection.CONNECTED, browser.connection());
        assertEquals(Optional.empty(), browser.error());
        assertFalse(browser.canRetry());
        assertEquals(1, browser.courses().size());
    }

    @Test
    public void losingTheBackendMidSessionKeepsWhatWasLoadedAndOffersRetry() {
        openTo(View.COURSE);
        api.failEverything = BackendException.from(new java.util.concurrent.TimeoutException());
        browser.openUnit("accounting101.recording_transactions");
        settle();
        assertEquals(Connection.UNAVAILABLE, browser.connection());
        assertEquals(Optional.of("The backend did not respond in time."), browser.error());
        assertTrue(browser.unit().isEmpty());
        assertTrue(browser.canRetry());
        assertTrue(browser.canGoBack());

        api.failEverything = null;
        browser.retry();
        settle();
        assertEquals(Connection.CONNECTED, browser.connection());
        assertEquals("accounting101.recording_transactions", browser.unit().orElseThrow().id());

        api.failEverything = unreachable();
        browser.openAssignment("inventory_fundamentals");
        settle();
        assertTrue(browser.back()); // dismiss the error
        assertEquals(View.UNIT, browser.view());
        assertEquals(Optional.empty(), browser.error());
        assertEquals("Dismissing an error does not pretend the backend is back", Connection.UNAVAILABLE, browser.connection());
    }

    @Test
    public void failuresAreReportedOnceOnTheOwnerThreadAndNeverForStaleResponses() {
        List<BackendException> reported = new ArrayList<>();
        browser.setFailureListener(reported::add);
        openTo(View.COURSES);
        api.failEverything = unreachable();
        browser.openCourse("accounting101");
        api.respond();
        assertTrue("Not reported from the background thread", reported.isEmpty());
        owner.drain();
        assertEquals(1, reported.size());
        assertEquals(Kind.UNREACHABLE, reported.getFirst().kind());

        browser.retry();
        browser.back(); // abandon the retry
        settle();
        assertEquals("An abandoned load reports nothing", 1, reported.size());
        browser.openCourse("accounting101");
        browser.close();
        settle();
        assertEquals("A closed screen reports nothing", 1, reported.size());
    }

    @Test
    public void notFoundIsAnErrorButTheBackendIsStillConnected() {
        openTo(View.COURSES);
        api.bodies.remove(ApiFixtures.COURSE);
        browser.openCourse("accounting101");
        settle();
        assertEquals(Connection.CONNECTED, browser.connection());
        assertEquals(Optional.of("The backend answered HTTP 404"), browser.error());
        assertTrue(browser.course().isEmpty());
        assertTrue(browser.back());
        assertEquals(View.COURSES, browser.view());
    }

    @Test
    public void serverErrorsAndDegradedHealthMeanUnavailable() {
        openTo(View.COURSES);
        api.failures.put(ApiFixtures.COURSE, new BackendException(Kind.HTTP_STATUS, 500, "The backend answered HTTP 500", null));
        browser.openCourse("accounting101");
        settle();
        assertEquals(Connection.UNAVAILABLE, browser.connection());
        assertEquals(Optional.of("The backend answered HTTP 500"), browser.error());

        FakeApi degraded = new FakeApi();
        degraded.bodies.put(ApiFixtures.HEALTH, "{\"status\":\"degraded\",\"database\":\"unavailable\"}");
        RemoteCurriculumBrowser other = new RemoteCurriculumBrowser(degraded, Runnable::run);
        other.open();
        degraded.respond();
        assertEquals(Connection.UNAVAILABLE, other.connection());
        assertTrue(other.error().orElseThrow().contains("degraded"));
        assertEquals("Courses are not requested from an unhealthy backend", List.of(ApiFixtures.HEALTH), degraded.calls);
    }

    @Test
    public void malformedOrPartialAssignmentContentIsRefusedWhole() {
        for (String broken : List.of(ApiFixtures.ASSIGNMENT, ApiFixtures.CONCEPT_1, ApiFixtures.CONCEPT_2, ApiFixtures.QUESTIONS)) {
            FakeApi fake = new FakeApi();
            RemoteCurriculumBrowser local = new RemoteCurriculumBrowser(fake, Runnable::run);
            local.open();
            fake.respond();
            local.openCourse("accounting101");
            fake.respond();
            local.openUnit("accounting101.recording_transactions");
            fake.respond();
            fake.bodies.put(broken, "{\"id\": \"truncated");
            local.openAssignment("inventory_fundamentals");
            fake.respond();
            assertTrue(broken, local.error().orElseThrow().startsWith("Unexpected backend response"));
            assertTrue(broken + ": no partly loaded assignment is shown", local.assignment().isEmpty());
            assertEquals(View.UNIT, local.view());
            assertEquals(Connection.UNAVAILABLE, local.connection());

            fake.bodies.put(broken, ApiFixtures.body(broken));
            local.retry();
            fake.respond();
            assertEquals(broken, 6, local.assignment().orElseThrow().questions().size());
        }
    }

    @Test
    public void contentThatDisagreesAcrossEndpointsIsRejected() {
        record Case(String name, String path, String body, View reach) { }
        List<Case> cases = List.of(
                new Case("course answers with another ID", ApiFixtures.COURSE,
                        edited(ApiFixtures.COURSE, root -> root.getAsJsonObject().addProperty("id", "accounting102")), View.COURSES),
                new Case("course units differ from the list", ApiFixtures.COURSE,
                        edited(ApiFixtures.COURSE, root -> root.getAsJsonObject().getAsJsonArray("units").remove(2)), View.COURSES),
                new Case("unit belongs to another course", ApiFixtures.UNIT,
                        edited(ApiFixtures.UNIT, root -> root.getAsJsonObject().addProperty("course_id", "other")), View.COURSE),
                new Case("unit assignments reordered", ApiFixtures.UNIT, edited(ApiFixtures.UNIT, root -> {
                    var list = root.getAsJsonObject().getAsJsonArray("assignments");
                    list.add(list.remove(0));
                }), View.COURSE),
                new Case("assignment belongs to another unit", ApiFixtures.ASSIGNMENT,
                        edited(ApiFixtures.ASSIGNMENT, root -> root.getAsJsonObject().addProperty("unit_id", "accounting101.fundamentals")), View.UNIT),
                new Case("assignment answers with another ID", ApiFixtures.ASSIGNMENT,
                        edited(ApiFixtures.ASSIGNMENT, root -> root.getAsJsonObject().addProperty("id", "other")), View.UNIT),
                new Case("concept belongs to another assignment", ApiFixtures.CONCEPT_2,
                        edited(ApiFixtures.CONCEPT_2, root -> root.getAsJsonObject().addProperty("assignment_id", "other")), View.UNIT),
                new Case("concept answers with another ID", ApiFixtures.CONCEPT_2, ApiFixtures.body(ApiFixtures.CONCEPT_1), View.UNIT),
                new Case("concept lost its lesson", ApiFixtures.CONCEPT_1,
                        edited(ApiFixtures.CONCEPT_1, root -> root.getAsJsonObject().add("lesson", null)), View.UNIT),
                new Case("a question is missing", ApiFixtures.QUESTIONS,
                        edited(ApiFixtures.QUESTIONS, root -> root.getAsJsonObject().getAsJsonArray("questions").remove(5)), View.UNIT),
                new Case("an extra question appears", ApiFixtures.CONCEPT_1,
                        edited(ApiFixtures.CONCEPT_1, root -> root.getAsJsonObject().getAsJsonArray("question_ids").add("inventory.extra")), View.UNIT),
                new Case("a question names the wrong concept", ApiFixtures.QUESTIONS, edited(ApiFixtures.QUESTIONS, root -> root.getAsJsonObject()
                        .getAsJsonArray("questions").get(0).getAsJsonObject().addProperty("concept_id", "payables.cash_payments")), View.UNIT),
                new Case("questions belong to another assignment", ApiFixtures.QUESTIONS,
                        edited(ApiFixtures.QUESTIONS, root -> root.getAsJsonObject().addProperty("assignment_id", "other")), View.UNIT),
                new Case("questions are out of concept order", ApiFixtures.QUESTIONS, edited(ApiFixtures.QUESTIONS, root -> {
                    var list = root.getAsJsonObject().getAsJsonArray("questions");
                    list.add(list.remove(0));
                }), View.UNIT));
        for (Case item : cases) {
            FakeApi fake = new FakeApi();
            RemoteCurriculumBrowser local = new RemoteCurriculumBrowser(fake, Runnable::run);
            fake.bodies.put(item.path(), item.body());
            local.open();
            fake.respond();
            local.openCourse("accounting101");
            fake.respond();
            local.openUnit("accounting101.recording_transactions");
            fake.respond();
            local.openAssignment("inventory_fundamentals");
            fake.respond();
            assertTrue(item.name() + ": " + local.error(), local.error().orElseThrow().contains("inconsistent content"));
            assertEquals(item.name(), item.reach(), local.view());
            assertTrue(item.name(), local.assignment().isEmpty());
        }
    }

    @Test
    public void unknownOrUnavailableTargetsAreNeverRequested() {
        openTo(View.COURSES);
        int calls = api.calls.size();
        browser.openCourse("nope");
        browser.openUnit("accounting101.recording_transactions"); // no course is open
        browser.openAssignment("inventory_fundamentals");
        browser.showQuestion(0);
        browser.retry(); // nothing failed
        assertEquals(calls, api.calls.size());
        assertFalse(browser.loading());

        browser.openCourse("accounting101");
        settle();
        browser.openUnit("accounting101.recording_transactions");
        settle();
        calls = api.calls.size();
        browser.openAssignment("accounting101.posting_ledger"); // Coming Soon
        browser.openAssignment("accounting101.accruals"); // another unit's assignment
        browser.openAssignment("nope");
        assertEquals("Coming Soon and foreign assignments have nothing to preview", calls, api.calls.size());
        assertEquals(View.UNIT, browser.view());
    }

    @Test
    public void anEmptyBackendShowsNoCoursesRatherThanBundledOnes() {
        api.bodies.put(ApiFixtures.COURSES, "[]");
        browser.open();
        settle();
        assertEquals(Connection.CONNECTED, browser.connection());
        assertEquals(Optional.empty(), browser.error());
        assertTrue(browser.courses().isEmpty());

        api.bodies.put(ApiFixtures.COURSES, ApiFixtures.body(ApiFixtures.COURSES));
        browser.reloadCourses();
        settle();
        assertEquals(1, browser.courses().size());
    }

    @Test
    public void aThrowingApiCallBecomesAnErrorNotACrash() {
        CurriculumApi broken = (CurriculumApi) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {CurriculumApi.class}, (proxy, method, arguments) -> { throw new IllegalStateException("boom"); });
        RemoteCurriculumBrowser local = new RemoteCurriculumBrowser(broken, Runnable::run);
        local.open();
        assertEquals(Connection.UNAVAILABLE, local.connection());
        assertTrue(local.error().isPresent());
        assertFalse(local.loading());
    }

    @Test
    public void browsingTheBackendNeverTouchesLocalProgressMasteryOrXp() throws Exception {
        UUID profile = UUID.fromString("11111111-2222-3333-4444-555555555555");
        Set<String> journalIds = IntroJournalAssignment.QUESTIONS.stream().map(question -> question.id()).collect(Collectors.toSet());
        List<String> warnings = new ArrayList<>();
        var gameDirectory = temporary.getRoot().toPath();
        var learning = new ProfileLearningProgress(profile, new JsonProgressRepository(gameDirectory),
                Accounting101.CATALOG.availableAssignments(), journalIds, warnings::add);
        var before = learning.snapshot();

        openTo(View.ASSIGNMENT);
        for (int i = 0; i < browser.assignment().orElseThrow().questions().size(); i++) browser.showQuestion(i);
        while (browser.back()) { }
        browser.close();

        assertEquals(before, learning.snapshot());
        try (var files = Files.walk(gameDirectory)) {
            assertEquals("No progress or other file was written", List.of(), files.filter(Files::isRegularFile).toList());
        }
        var service = learning.mastery().open(Accounting101.CATALOG.assignment("inventory_fundamentals").orElseThrow());
        assertEquals(0, service.xp());
        assertEquals(0, service.completedConcepts());
        assertEquals(0, learning.journal().xp());
        assertTrue(warnings.isEmpty());

        // Structural guarantee: the backend package cannot reach grading, mastery or progress storage at all.
        for (Class<?> type : List.of(RemoteCurriculumBrowser.class, RemoteCurriculumBrowser.AssignmentPreview.class, CurriculumApi.class,
                BackendClient.class, ConnectionManager.class, Question.class, AssignmentQuestions.class)) {
            List<Class<?>> referenced = new ArrayList<>();
            for (var field : type.getDeclaredFields()) referenced.add(field.getType());
            for (var method : type.getDeclaredMethods()) {
                referenced.add(method.getReturnType());
                referenced.addAll(List.of(method.getParameterTypes()));
            }
            for (var constructor : type.getDeclaredConstructors()) referenced.addAll(List.of(constructor.getParameterTypes()));
            for (Class<?> used : referenced) {
                String name = used.getName();
                assertFalse(type.getSimpleName() + " must not use " + name, name.startsWith("com.mineledger.progress")
                        || name.contains("ConceptProgressionService") || name.contains("LearningSession")
                        || name.contains("Grader") || name.contains("MultipleChoiceQuestion") || name.contains("JournalProgress"));
            }
        }
    }
}
