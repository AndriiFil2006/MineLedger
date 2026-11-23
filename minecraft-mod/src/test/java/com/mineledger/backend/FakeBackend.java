package com.mineledger.backend;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import com.mineledger.backend.BackendException.Kind;
import com.mineledger.backend.RemoteCurriculum.*;

/** Test doubles: a backend whose responses, and a client thread whose turns, happen only when a test says so. */
final class FakeBackend {
    /** Answers from the shared real-response fixtures, but only when {@link #respond()} is called. */
    static final class FakeApi implements CurriculumApi {
        private record Pending(String path, Runnable succeed, CompletableFuture<?> future) { }

        final List<String> calls = new ArrayList<>();
        final Map<String, String> bodies = new HashMap<>(ApiFixtures.responses());
        final Map<String, BackendException> failures = new HashMap<>();
        BackendException failEverything;
        private final ArrayDeque<Pending> pending = new ArrayDeque<>();

        private <T> CompletableFuture<T> call(String path, Function<String, T> parser) {
            calls.add(path);
            CompletableFuture<T> future = new CompletableFuture<>();
            pending.add(new Pending(path, () -> {
                try {
                    String body = bodies.get(path);
                    if (body == null) throw new BackendException(Kind.HTTP_STATUS, 404, "The backend answered HTTP 404", null);
                    future.complete(parser.apply(body));
                } catch (RuntimeException exception) {
                    future.completeExceptionally(exception);
                }
            }, future));
            return future;
        }

        /** Completes everything requested so far, including requests chained from those completions. */
        boolean respond() {
            boolean any = false;
            while (!pending.isEmpty()) {
                Pending next = pending.poll();
                BackendException failure = failEverything != null ? failEverything : failures.get(next.path());
                if (failure != null) next.future().completeExceptionally(failure);
                else next.succeed().run();
                any = true;
            }
            return any;
        }

        @Override public CompletableFuture<Health> health() { return call(ApiFixtures.HEALTH, BackendJson::health); }
        @Override public CompletableFuture<Meta> meta() { return call(ApiFixtures.META, BackendJson::meta); }
        @Override public CompletableFuture<List<CourseSummary>> courses() { return call(ApiFixtures.COURSES, BackendJson::courses); }
        @Override public CompletableFuture<CourseDetail> course(String id) { return call("/api/v1/courses/" + id, BackendJson::course); }
        @Override public CompletableFuture<UnitDetail> unit(String id) { return call("/api/v1/units/" + id, BackendJson::unit); }
        @Override public CompletableFuture<AssignmentDetail> assignment(String id) { return call("/api/v1/assignments/" + id, BackendJson::assignment); }
        @Override public CompletableFuture<ConceptDetail> concept(String id) { return call("/api/v1/concepts/" + id, BackendJson::concept); }
        @Override public CompletableFuture<AssignmentQuestions> assignmentQuestions(String id) {
            return call("/api/v1/assignments/" + id + "/questions", BackendJson::questions);
        }
    }

    /** Stands in for Minecraft's client thread: nothing runs until the test drains it. */
    static final class OwnerThread implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        boolean drain() {
            boolean any = !tasks.isEmpty();
            while (!tasks.isEmpty()) tasks.poll().run();
            return any;
        }
    }

    static BackendException unreachable() { return BackendException.from(new java.net.ConnectException("refused")); }

    static BackendException timeout() { return BackendException.from(new java.util.concurrent.TimeoutException()); }

    private FakeBackend() { }
}
