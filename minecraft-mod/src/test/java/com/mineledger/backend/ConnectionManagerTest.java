package com.mineledger.backend;

import static org.junit.Assert.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.sun.net.httpserver.HttpServer;
import com.mineledger.accounting.IntroJournalAssignment;
import com.mineledger.accounting.mastery.Assignment;
import com.mineledger.accounting.mastery.RechargePractice;
import com.mineledger.backend.BackendConfig.Mode;
import com.mineledger.backend.BackendException.Kind;
import com.mineledger.backend.ConnectionManager.Content;
import com.mineledger.backend.ConnectionManager.OfflineReason;
import com.mineledger.backend.ConnectionManager.State;
import com.mineledger.backend.FakeBackend.FakeApi;
import com.mineledger.backend.FakeBackend.OwnerThread;
import com.mineledger.backend.RemoteCurriculumBrowser.View;
import com.mineledger.education.Accounting101;
import com.mineledger.presentation.PracticeQuestionState;
import com.mineledger.progress.JsonProgressRepository;
import com.mineledger.progress.ProfileLearningProgress;

/** Mode selection, fallback, outage and reconnection with the backend and the "client thread" under test control. */
public class ConnectionManagerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private final FakeApi api = new FakeApi();
    private final OwnerThread owner = new OwnerThread();
    private final List<Mode> saved = new ArrayList<>();
    private int apiCreations;
    private int notifications;
    private long now = 1_000_000;
    private String saveFailure;

    private ConnectionManager manager(Mode preference) {
        ConnectionManager manager = new ConnectionManager(preference, () -> {
            apiCreations++;
            return api;
        }, owner, () -> now, chosen -> {
            saved.add(chosen);
            return Optional.ofNullable(saveFailure);
        });
        manager.addListener(() -> notifications++);
        return manager;
    }

    private void settle() {
        while (api.respond() | owner.drain()) { }
    }

    private ConnectionManager opened(Mode preference) {
        ConnectionManager manager = manager(preference);
        manager.appOpened();
        settle();
        return manager;
    }

    private static final List<String> PROBE = List.of(ApiFixtures.HEALTH, ApiFixtures.META, ApiFixtures.COURSES);

    // 1
    @Test
    public void automaticModeUsesTheBackendWhenItIsHealthyAndCompatible() {
        ConnectionManager manager = manager(Mode.AUTO);
        assertEquals(State.CHECKING, manager.state());
        assertEquals("Nothing is shown before the first decision", Content.NONE, manager.content());
        assertEquals("No request before the app is opened", 0, apiCreations);

        manager.appOpened();
        assertTrue(manager.checking());
        assertEquals(State.CHECKING, manager.state());
        assertEquals(List.of(ApiFixtures.HEALTH), api.calls);
        settle();
        assertEquals("Health alone is not enough: version and courses are confirmed too", PROBE, api.calls);
        assertEquals(State.ONLINE, manager.state());
        assertEquals(Content.REMOTE, manager.content());
        assertFalse(manager.checking());
        assertTrue(manager.problem().isEmpty());
        assertTrue(manager.offlineReason().isEmpty());
        assertEquals(1, notifications);
        assertEquals(1, apiCreations);
    }

    // 2
    @Test
    public void automaticModeFallsBackToOfflinePracticeWhenTheBackendIsUnavailable() {
        api.failEverything = FakeBackend.unreachable();
        ConnectionManager manager = opened(Mode.AUTO);
        assertEquals(State.OFFLINE_PRACTICE, manager.state());
        assertEquals(Content.LOCAL, manager.content());
        assertEquals(Optional.of(OfflineReason.FALLBACK), manager.offlineReason());
        assertEquals(Kind.UNREACHABLE, manager.problem().orElseThrow().kind());
        assertEquals("The saved preference stays Automatic", Mode.AUTO, manager.preference());
        assertTrue(saved.isEmpty());
        assertTrue(manager.canRetry());
    }

    // 3
    @Test
    public void onlineRequiredNeverFallsBackByItself() {
        api.failEverything = FakeBackend.unreachable();
        ConnectionManager manager = opened(Mode.BACKEND);
        assertEquals(State.UNAVAILABLE, manager.state());
        assertEquals("No local content without an explicit choice", Content.NONE, manager.content());
        assertEquals("Cannot reach the backend. Is the server running?", manager.problem().orElseThrow().getMessage());
        assertTrue(manager.offlineReason().isEmpty());

        manager.retry();
        settle();
        now += ConnectionManager.RECHECK_AFTER_MILLIS;
        manager.appOpened();
        settle();
        assertEquals(State.UNAVAILABLE, manager.state());
        assertEquals(Content.NONE, manager.content());

        assertTrue(manager.canSwitchToOfflinePractice());
        manager.switchToOfflinePractice(); // the explicit action
        assertEquals(State.OFFLINE_PRACTICE, manager.state());
        assertEquals(Content.LOCAL, manager.content());
        assertEquals(Optional.of(OfflineReason.SWITCHED), manager.offlineReason());
        assertEquals("Switching for now does not rewrite the saved preference", Mode.BACKEND, manager.preference());
        assertTrue(saved.isEmpty());
    }

    // 4
    @Test
    public void explicitOfflineModeNeverRequestsBackendData() {
        ConnectionManager manager = manager(Mode.OFFLINE);
        assertEquals(State.OFFLINE_PRACTICE, manager.state());
        assertEquals(Content.LOCAL, manager.content());
        assertEquals(Optional.of(OfflineReason.CHOSEN), manager.offlineReason());
        for (int i = 0; i < 3; i++) {
            manager.appOpened();
            manager.retry();
            manager.switchToOfflinePractice();
            manager.reportRequestFailure(FakeBackend.unreachable());
            now += ConnectionManager.RECHECK_AFTER_MILLIS * 2;
            settle();
        }
        assertFalse(manager.canRetry());
        assertEquals("The HTTP client is never even created", 0, apiCreations);
        assertTrue(api.calls.isEmpty());
        assertEquals(State.OFFLINE_PRACTICE, manager.state());
        assertEquals(0, notifications);
    }

    // 5
    @Test
    public void aTimeoutIsReportedAsATimeout() {
        api.failEverything = FakeBackend.timeout();
        ConnectionManager auto = opened(Mode.AUTO);
        assertEquals(Kind.TIMEOUT, auto.problem().orElseThrow().kind());
        assertEquals(State.OFFLINE_PRACTICE, auto.state());
        ConnectionManager required = opened(Mode.BACKEND);
        assertEquals(Kind.TIMEOUT, required.problem().orElseThrow().kind());
        assertEquals(State.UNAVAILABLE, required.state());
    }

    // 5, with the real HTTP client: a server that accepts the connection and never answers
    @Test
    public void aRealServerThatNeverAnswersFallsBackWithoutBlockingTheCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        LinkedBlockingQueue<Runnable> clientThread = new LinkedBlockingQueue<>();
        BackendConfig config = new BackendConfig(Mode.AUTO, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofMillis(1000), Duration.ofMillis(1500));
        try (BackendClient client = new BackendClient(config)) {
            ConnectionManager manager = new ConnectionManager(Mode.AUTO, () -> client, clientThread::add,
                    System::currentTimeMillis, chosen -> Optional.empty());
            long started = System.nanoTime();
            manager.appOpened();
            assertTrue("Opening the app returns long before the 1.5 s timeout", (System.nanoTime() - started) / 1_000_000 < 1000);
            assertEquals(State.CHECKING, manager.state());
            Runnable delivery = clientThread.poll(10, TimeUnit.SECONDS);
            assertNotNull("The result is handed to the client thread", delivery);
            assertEquals("...and changes nothing until that thread runs it", State.CHECKING, manager.state());
            delivery.run();
            assertEquals(State.OFFLINE_PRACTICE, manager.state());
            assertEquals(Kind.TIMEOUT, manager.problem().orElseThrow().kind());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    // 6
    @Test
    public void malformedResponsesAreReportedAsMalformed() {
        for (String path : PROBE) {
            for (String body : List.of("{\"status\": ", "<html>Welcome to nginx</html>", "[1, 2", "{}")) {
                FakeApi broken = new FakeApi();
                broken.bodies.put(path, body);
                ConnectionManager manager = new ConnectionManager(Mode.AUTO, () -> broken, Runnable::run, () -> now, chosen -> Optional.empty());
                manager.appOpened();
                broken.respond();
                assertEquals(path + " " + body, Kind.MALFORMED_RESPONSE, manager.problem().orElseThrow().kind());
                assertEquals(State.OFFLINE_PRACTICE, manager.state());
                assertEquals("Incomplete remote data is never shown", Content.LOCAL, manager.content());
            }
        }
    }

    // 7
    @Test
    public void incompatibleOrEmptyBackendsAreNotUsed() {
        record Case(String name, String path, String body) { }
        List<Case> cases = List.of(
                new Case("newer API", ApiFixtures.META, "{\"api_version\":2,\"curriculum_schema_versions\":[1],\"course_count\":1}"),
                new Case("older API", ApiFixtures.META, "{\"api_version\":0,\"curriculum_schema_versions\":[1],\"course_count\":1}"),
                new Case("unknown curriculum schema", ApiFixtures.META, "{\"api_version\":1,\"curriculum_schema_versions\":[2],\"course_count\":1}"),
                new Case("mixed curriculum schemas", ApiFixtures.META, "{\"api_version\":1,\"curriculum_schema_versions\":[1,2],\"course_count\":1}"),
                new Case("nothing imported", ApiFixtures.META, "{\"api_version\":1,\"curriculum_schema_versions\":[],\"course_count\":0}"),
                new Case("courses missing despite the description", ApiFixtures.COURSES, "[]"),
                new Case("course count disagrees", ApiFixtures.META, "{\"api_version\":1,\"curriculum_schema_versions\":[1],\"course_count\":3}"),
                new Case("database down but HTTP 200", ApiFixtures.HEALTH, "{\"status\":\"degraded\",\"database\":\"unavailable\"}"),
                new Case("server without the description endpoint", ApiFixtures.META, null));
        for (Case item : cases) {
            for (Mode preference : List.of(Mode.AUTO, Mode.BACKEND)) {
                FakeApi other = new FakeApi();
                if (item.body() == null) other.bodies.remove(item.path());
                else other.bodies.put(item.path(), item.body());
                ConnectionManager manager = new ConnectionManager(preference, () -> other, Runnable::run, () -> now, chosen -> Optional.empty());
                manager.appOpened();
                other.respond();
                assertEquals(item.name(), Kind.INCOMPATIBLE, manager.problem().orElseThrow().kind());
                assertNotEquals(item.name(), Content.REMOTE, manager.content());
                assertEquals(item.name(), preference == Mode.AUTO ? State.OFFLINE_PRACTICE : State.UNAVAILABLE, manager.state());
            }
        }
        // Compatibility is checked before content: an incompatible server is never asked for its courses.
        FakeApi newer = new FakeApi();
        newer.bodies.put(ApiFixtures.META, "{\"api_version\":2,\"curriculum_schema_versions\":[1],\"course_count\":1}");
        ConnectionManager manager = new ConnectionManager(Mode.AUTO, () -> newer, Runnable::run, () -> now, chosen -> Optional.empty());
        manager.appOpened();
        newer.respond();
        assertEquals(List.of(ApiFixtures.HEALTH, ApiFixtures.META), newer.calls);
    }

    // 8
    @Test
    public void losingTheBackendWhileAScreenIsOpenKeepsThatScreenAndWaitsForTheStudent() {
        ConnectionManager manager = opened(Mode.AUTO);
        RemoteCurriculumBrowser browser = new RemoteCurriculumBrowser(manager.api(), owner);
        browser.setFailureListener(manager::reportRequestFailure);
        browser.open();
        settle();
        browser.openCourse("accounting101");
        settle();
        browser.openUnit("accounting101.recording_transactions");
        settle();
        browser.openAssignment("inventory_fundamentals");
        settle();
        browser.showQuestion(3);
        var shown = browser.assignment().orElseThrow().questions().get(3);

        api.failEverything = FakeBackend.unreachable(); // the server goes down
        browser.back();
        browser.back();
        browser.openAssignment("inventory_fundamentals"); // the next thing the student clicks fails
        settle();
        assertEquals(State.CONNECTION_LOST, manager.state());
        assertEquals("Automatic mode does not yank the screen to offline content", Content.REMOTE, manager.content());
        assertEquals(Kind.UNREACHABLE, manager.problem().orElseThrow().kind());
        assertEquals(View.UNIT, browser.view());
        assertEquals("accounting101.recording_transactions", browser.unit().orElseThrow().id());
        assertTrue(browser.error().isPresent());

        manager.retry(); // still down
        assertTrue(manager.checking());
        assertEquals(State.CONNECTION_LOST, manager.state());
        settle();
        assertEquals("A failed retry is still not an automatic switch", State.CONNECTION_LOST, manager.state());
        assertEquals(Content.REMOTE, manager.content());
        assertEquals("accounting101.recording_transactions", browser.unit().orElseThrow().id());

        api.failEverything = null; // back up
        manager.retry();
        settle();
        assertEquals(State.ONLINE, manager.state());
        browser.retry(); // what the screen does once the connection is back
        settle();
        assertEquals(View.ASSIGNMENT, browser.view());
        assertEquals("The same question content is there after recovery", shown, browser.assignment().orElseThrow().questions().get(3));
    }

    // 8
    @Test
    public void aQuestionOnScreenSurvivesAnOutageUntouched() {
        ConnectionManager manager = opened(Mode.BACKEND);
        RemoteCurriculumBrowser browser = new RemoteCurriculumBrowser(manager.api(), owner);
        browser.setFailureListener(manager::reportRequestFailure);
        browser.open();
        settle();
        browser.openCourse("accounting101");
        settle();
        browser.openUnit("accounting101.recording_transactions");
        settle();
        browser.openAssignment("inventory_fundamentals");
        settle();
        browser.showQuestion(4);
        var before = browser.assignment().orElseThrow();

        api.failEverything = FakeBackend.timeout();
        manager.reportRequestFailure(FakeBackend.timeout());
        manager.retry();
        settle();
        assertEquals(State.CONNECTION_LOST, manager.state());
        assertEquals(View.QUESTION, browser.view());
        assertEquals(4, browser.questionIndex());
        assertSame(before, browser.assignment().orElseThrow());
        browser.showQuestion(5); // already-loaded questions stay browsable
        assertEquals(5, browser.questionIndex());

        manager.switchToOfflinePractice(); // only this explicit action leaves backend content
        assertEquals(Content.LOCAL, manager.content());
        assertEquals(Optional.of(OfflineReason.SWITCHED), manager.offlineReason());
    }

    @Test
    public void requestFailuresThatDoNotMeanAnOutageAreNotConnectionLoss() {
        ConnectionManager manager = opened(Mode.AUTO);
        manager.reportRequestFailure(new BackendException(Kind.HTTP_STATUS, 404, "The backend answered HTTP 404", null));
        manager.reportRequestFailure(null);
        assertEquals(State.ONLINE, manager.state());
        manager.reportRequestFailure(new BackendException(Kind.HTTP_STATUS, 500, "The backend answered HTTP 500", null));
        assertEquals(State.CONNECTION_LOST, manager.state());
        manager.reportRequestFailure(FakeBackend.unreachable());
        assertEquals("Reported once", 2, notifications);
    }

    // 9
    @Test
    public void staleCheckResultsNeverChangeTheState() {
        ConnectionManager manager = manager(Mode.AUTO);
        manager.appOpened();
        manager.switchToOfflinePractice(); // the student did not wait
        int before = notifications;
        settle(); // the abandoned check now succeeds
        assertEquals(State.OFFLINE_PRACTICE, manager.state());
        assertEquals(Optional.of(OfflineReason.SWITCHED), manager.offlineReason());
        assertEquals(Content.LOCAL, manager.content());
        assertEquals(before, notifications);

        // A slow failure from an earlier check must not undo a newer success.
        ConnectionManager second = manager(Mode.BACKEND);
        api.failEverything = FakeBackend.unreachable();
        second.appOpened();
        api.respond(); // the failure is waiting for the client thread...
        second.setPreference(Mode.AUTO); // ...when a newer check starts
        api.failEverything = null;
        settle();
        assertEquals(State.ONLINE, second.state());
        assertTrue(second.problem().isEmpty());
    }

    // 9
    @Test
    public void resultsAreAppliedOnlyOnTheOwnerThreadAndChecksAreNotStacked() {
        ConnectionManager manager = manager(Mode.AUTO);
        manager.appOpened();
        manager.appOpened();
        manager.retry();
        manager.retry();
        assertEquals("One check at a time", List.of(ApiFixtures.HEALTH), api.calls);
        api.respond();
        assertEquals(State.CHECKING, manager.state());
        assertEquals(Content.NONE, manager.content());
        assertEquals(0, notifications);
        owner.drain();
        assertEquals(State.ONLINE, manager.state());
        assertEquals(PROBE, api.calls);
    }

    @Test
    public void reopeningTheAppSoonAfterACheckDoesNotCheckAgain() {
        ConnectionManager manager = opened(Mode.AUTO);
        for (int i = 0; i < 20; i++) {
            now += 500;
            manager.appOpened();
        }
        settle();
        assertEquals("Twenty reopenings within the window: still one check", PROBE, api.calls);
        now += ConnectionManager.RECHECK_AFTER_MILLIS;
        manager.appOpened();
        assertEquals("A recheck while online keeps showing backend content", Content.REMOTE, manager.content());
        assertEquals(State.ONLINE, manager.state());
        settle();
        assertEquals(6, api.calls.size());
        assertEquals(State.ONLINE, manager.state());
    }

    // 10
    @Test
    public void connectionChangesDoNotDisturbAnActiveLocalQuestion() {
        api.failEverything = FakeBackend.unreachable();
        ConnectionManager manager = opened(Mode.AUTO); // offline practice (fallback)
        Assignment assignment = Accounting101.CATALOG.assignment("inventory_fundamentals").orElseThrow();
        var learning = progress(temporary.getRoot().toPath());
        var service = learning.mastery().open(assignment);
        PracticeQuestionState screen = new PracticeQuestionState();
        var question = service.nextQuestion().orElseThrow();
        screen.show(question);
        screen.select(question.options().get(2).id());
        var snapshot = screen.snapshot();
        var progressBefore = learning.snapshot();

        // Everything that can happen to the connection while the student looks at that question.
        api.failEverything = null;
        manager.retry();
        settle();
        assertEquals(State.ONLINE, manager.state());
        manager.reportRequestFailure(FakeBackend.unreachable());
        manager.switchToOfflinePractice();
        manager.setPreference(Mode.BACKEND);
        settle();
        manager.setPreference(Mode.OFFLINE);
        manager.setPreference(Mode.AUTO);
        settle();

        assertEquals("Question and selected option are untouched", snapshot, screen.snapshot());
        assertSame(question, service.nextQuestion().orElseThrow());
        assertEquals(progressBefore, learning.snapshot());
        // The attempt finishes against the local question it started with, graded locally, credited once.
        var submission = service.submit(question.id(), question.correctOptionId());
        assertTrue(submission.grading().correct());
        assertTrue(submission.creditEarned());
        assertFalse(service.submit(question.id(), question.correctOptionId()).creditEarned());
    }

    // 10
    @Test
    public void changingThePreferenceAppliesImmediatelyAndIsSaved() {
        ConnectionManager manager = opened(Mode.AUTO);
        manager.setPreference(Mode.AUTO);
        assertTrue("Choosing the current option does nothing", saved.isEmpty());

        manager.setPreference(Mode.OFFLINE);
        assertEquals(List.of(Mode.OFFLINE), saved);
        assertEquals(State.OFFLINE_PRACTICE, manager.state());
        assertEquals(Content.LOCAL, manager.content());
        assertEquals(Optional.of(OfflineReason.CHOSEN), manager.offlineReason());
        int calls = api.calls.size();
        now += ConnectionManager.RECHECK_AFTER_MILLIS;
        manager.appOpened();
        manager.retry();
        settle();
        assertEquals("Offline means no requests from now on", calls, api.calls.size());

        api.failEverything = FakeBackend.unreachable();
        manager.setPreference(Mode.BACKEND);
        assertEquals("Online Required stops showing local content at once", Content.NONE, manager.content());
        assertEquals(State.CHECKING, manager.state());
        settle();
        assertEquals(State.UNAVAILABLE, manager.state());

        manager.setPreference(Mode.AUTO);
        settle();
        assertEquals(State.OFFLINE_PRACTICE, manager.state());
        assertEquals(Optional.of(OfflineReason.FALLBACK), manager.offlineReason());
        assertEquals(List.of(Mode.OFFLINE, Mode.BACKEND, Mode.AUTO), saved);
        assertTrue(manager.saveProblem().isEmpty());

        saveFailure = "This choice could not be saved.";
        manager.setPreference(Mode.OFFLINE);
        assertEquals("A choice that cannot be saved still applies now", Mode.OFFLINE, manager.preference());
        assertEquals(Optional.of("This choice could not be saved."), manager.saveProblem());
        saveFailure = null;
        manager.setPreference(Mode.AUTO);
        assertTrue(manager.saveProblem().isEmpty());
    }

    // 11
    @Test
    public void reconnectsManuallyAfterAnOutage() {
        api.failEverything = FakeBackend.unreachable();
        ConnectionManager manager = opened(Mode.AUTO);
        assertEquals(State.OFFLINE_PRACTICE, manager.state());

        api.failEverything = null; // the server is back, but nothing changes by itself
        settle();
        assertEquals(State.OFFLINE_PRACTICE, manager.state());

        manager.retry();
        assertEquals("Local content stays on screen while reconnecting", Content.LOCAL, manager.content());
        assertEquals(State.OFFLINE_PRACTICE, manager.state());
        assertTrue(manager.checking());
        settle();
        assertEquals(State.ONLINE, manager.state());
        assertEquals(Content.REMOTE, manager.content());
        assertTrue(manager.offlineReason().isEmpty());
        assertTrue(manager.problem().isEmpty());
    }

    // 11
    @Test
    public void reopeningTheAppLaterReconnectsAutomaticModeButNotAnExplicitSwitch() {
        api.failEverything = FakeBackend.unreachable();
        ConnectionManager fallback = opened(Mode.AUTO);
        api.failEverything = null;
        now += ConnectionManager.RECHECK_AFTER_MILLIS;
        fallback.appOpened();
        settle();
        assertEquals(State.ONLINE, fallback.state());

        ConnectionManager switched = opened(Mode.AUTO);
        switched.switchToOfflinePractice();
        int calls = api.calls.size();
        for (int i = 0; i < 5; i++) {
            now += ConnectionManager.RECHECK_AFTER_MILLIS;
            switched.appOpened();
            settle();
        }
        assertEquals("The student chose offline practice; reopening does not override that", State.OFFLINE_PRACTICE, switched.state());
        assertEquals(calls, api.calls.size());
        switched.retry(); // Reconnect is the way back
        settle();
        assertEquals(State.ONLINE, switched.state());
    }

    @Test
    public void reopeningAfterALostConnectionDecidesAfresh() {
        ConnectionManager manager = opened(Mode.AUTO);
        api.failEverything = FakeBackend.unreachable();
        manager.reportRequestFailure(FakeBackend.unreachable());
        assertEquals(State.CONNECTION_LOST, manager.state());
        now += ConnectionManager.RECHECK_AFTER_MILLIS;
        manager.appOpened(); // a new visit: nothing from the old screen is displayed any more
        assertEquals(Content.NONE, manager.content());
        settle();
        assertEquals("Now automatic mode may fall back", State.OFFLINE_PRACTICE, manager.state());
        assertEquals(Optional.of(OfflineReason.FALLBACK), manager.offlineReason());
    }

    private ProfileLearningProgress progress(Path gameDirectory) {
        Set<String> journalIds = IntroJournalAssignment.QUESTIONS.stream().map(question -> question.id()).collect(Collectors.toSet());
        return new ProfileLearningProgress(UUID.fromString("11111111-2222-3333-4444-555555555555"),
                new JsonProgressRepository(gameDirectory), Accounting101.CATALOG.availableAssignments(), journalIds,
                message -> fail("Unexpected progress warning: " + message));
    }

    /** A real manager wired to real config saving, driven through every mode and state. */
    private void exerciseEveryTransition(Path gameDirectory) {
        BackendConfig config = BackendConfig.load(gameDirectory).config();
        ConnectionManager manager = new ConnectionManager(config.mode(), () -> api, owner, () -> now, chosen -> {
            try {
                config.withMode(chosen).save(gameDirectory);
                return Optional.empty();
            } catch (java.io.IOException exception) {
                return Optional.of(exception.toString());
            }
        });
        manager.appOpened();
        settle();
        for (Mode mode : List.of(Mode.OFFLINE, Mode.BACKEND, Mode.AUTO, Mode.OFFLINE, Mode.AUTO)) {
            manager.setPreference(mode);
            settle();
            assertTrue(manager.saveProblem().isEmpty());
            manager.switchToOfflinePractice();
            manager.retry();
            settle();
            api.failEverything = FakeBackend.unreachable();
            manager.reportRequestFailure(FakeBackend.unreachable());
            manager.retry();
            settle();
            now += ConnectionManager.RECHECK_AFTER_MILLIS;
            manager.appOpened();
            settle();
            api.failEverything = null;
            manager.retry();
            settle();
        }
        assertEquals(Mode.AUTO, BackendConfig.load(gameDirectory).config().mode());
    }

    // 12, 13
    @Test
    public void localProgressSurvivesEveryModeChangeAndIsNeverCreditedTwice() throws Exception {
        Path gameDirectory = temporary.getRoot().toPath();
        Assignment assignment = Accounting101.CATALOG.assignment("inventory_fundamentals").orElseThrow();
        var learning = progress(gameDirectory);
        var service = learning.mastery().open(assignment);
        var concept = assignment.concepts().getFirst();
        for (int i = 0; i < concept.requiredCorrectAnswers(); i++) {
            var question = concept.questions().get(i);
            service.submit(question.id(), question.correctOptionId());
        }
        var half = assignment.concepts().get(1).questions().getFirst();
        service.submit(half.id(), half.correctOptionId());
        learning.save();
        assertEquals(20, service.xp());
        Path file = gameDirectory.resolve("mineledger").resolve("progress").resolve("11111111-2222-3333-4444-555555555555.json");
        byte[] savedBytes = Files.readAllBytes(file);
        var snapshot = learning.snapshot();

        exerciseEveryTransition(gameDirectory);

        assertArrayEquals("The save file is byte-for-byte unchanged", savedBytes, Files.readAllBytes(file));
        assertEquals(snapshot, learning.snapshot());
        var restored = progress(gameDirectory); // as after a restart
        assertEquals(snapshot, restored.snapshot());
        var again = restored.mastery().open(assignment);
        assertEquals(20, again.xp());
        assertEquals(1, again.progress().concept(assignment.concepts().get(1).id()).credits());
        // Answering already-credited questions again, in any mode, earns nothing more.
        for (var done : concept.questions().subList(0, concept.requiredCorrectAnswers())) {
            var repeat = again.submit(done.id(), done.correctOptionId());
            assertFalse(repeat.creditEarned());
            assertEquals(0, repeat.xpAwarded());
        }
        assertFalse(again.submit(half.id(), half.correctOptionId()).creditEarned());
        assertEquals(20, again.xp());
        assertEquals(snapshot, restored.snapshot());
    }

    // 14
    @Test
    public void rechargePracticeStaysNonRewardingAcrossModeChanges() throws Exception {
        Path gameDirectory = temporary.getRoot().toPath();
        Assignment assignment = Accounting101.CATALOG.assignment("inventory_fundamentals").orElseThrow();
        var learning = progress(gameDirectory);
        var service = learning.mastery().open(assignment);
        for (var concept : assignment.concepts()) {
            for (int i = 0; i < concept.requiredCorrectAnswers(); i++) {
                service.submit(concept.questions().get(i).id(), concept.questions().get(i).correctOptionId());
            }
        }
        learning.save();
        assertEquals(40, service.xp());
        Path file = gameDirectory.resolve("mineledger").resolve("progress").resolve("11111111-2222-3333-4444-555555555555.json");
        byte[] savedBytes = Files.readAllBytes(file);
        var snapshot = learning.snapshot();

        RechargePractice practice = new RechargePractice(service);
        var first = practice.currentQuestion().orElseThrow();
        practice.submit(first.correctOptionId());
        practice.continuePractice();
        exerciseEveryTransition(gameDirectory); // the connection changes in the middle of a Recharge run
        while (!practice.completed()) {
            practice.submit(practice.currentQuestion().orElseThrow().correctOptionId());
            practice.continuePractice();
        }
        assertEquals(6, practice.reviewedQuestions());
        assertEquals(40, service.xp());
        assertEquals(snapshot, learning.snapshot());
        assertEquals(snapshot, progress(gameDirectory).snapshot());
        assertArrayEquals(savedBytes, Files.readAllBytes(file));
    }

    // 15
    @Test
    public void nothingIsEverSentToTheBackendExceptReadOnlyRequests() throws Exception {
        // Structurally: the only way this client can talk to the backend is these read operations.
        for (var method : CurriculumApi.class.getDeclaredMethods()) {
            for (Class<?> parameter : method.getParameterTypes()) {
                assertEquals(method.getName() + " takes only an ID", String.class, parameter);
            }
        }
        // Behaviorally: a real server sees only GETs across fallback, local practice, reconnection and browsing.
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
                    + " body=" + exchange.getRequestBody().readAllBytes().length);
            String body = ApiFixtures.responses().get(exchange.getRequestURI().getRawPath());
            byte[] bytes = (body == null ? "{\"detail\":\"Not Found\"}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        Path gameDirectory = temporary.getRoot().toPath();
        LinkedBlockingQueue<Runnable> clientThread = new LinkedBlockingQueue<>();
        BackendConfig config = new BackendConfig(Mode.AUTO, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofMillis(1000), Duration.ofMillis(3000));
        try (BackendClient client = new BackendClient(config)) {
            ConnectionManager manager = new ConnectionManager(Mode.AUTO, () -> client, clientThread::add,
                    System::currentTimeMillis, chosen -> Optional.empty());
            manager.switchToOfflinePractice();
            // Offline practice that earns local XP...
            Assignment assignment = Accounting101.CATALOG.assignment("inventory_fundamentals").orElseThrow();
            var learning = progress(gameDirectory);
            var service = learning.mastery().open(assignment);
            for (var concept : assignment.concepts()) {
                for (int i = 0; i < concept.requiredCorrectAnswers(); i++) {
                    service.submit(concept.questions().get(i).id(), concept.questions().get(i).correctOptionId());
                }
            }
            learning.save();
            assertEquals(40, service.xp());
            assertTrue("Offline practice made no request at all", requests.isEmpty());

            // ...followed by going online and browsing.
            manager.retry();
            while (manager.checking()) clientThread.poll(10, TimeUnit.SECONDS).run();
            assertEquals(State.ONLINE, manager.state());
            RemoteCurriculumBrowser browser = new RemoteCurriculumBrowser(manager.api(), clientThread::add);
            browser.open();
            while (browser.loading()) clientThread.poll(10, TimeUnit.SECONDS).run();
            browser.openCourse("accounting101");
            while (browser.loading()) clientThread.poll(10, TimeUnit.SECONDS).run();
            browser.openUnit("accounting101.recording_transactions");
            while (browser.loading()) clientThread.poll(10, TimeUnit.SECONDS).run();
            browser.openAssignment("inventory_fundamentals");
            while (browser.loading()) clientThread.poll(10, TimeUnit.SECONDS).run();
            assertEquals(6, browser.assignment().orElseThrow().questions().size());
        } finally {
            server.stop(0);
        }
        assertFalse(requests.isEmpty());
        for (String request : requests) {
            assertTrue("Only body-less GETs: " + request, request.startsWith("GET ") && request.endsWith(" body=0"));
            assertFalse("No grading or progress endpoint: " + request, request.contains("grade") || request.contains("progress"));
        }
    }
}
