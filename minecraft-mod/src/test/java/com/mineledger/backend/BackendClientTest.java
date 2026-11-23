package com.mineledger.backend;

import static org.junit.Assert.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.mineledger.backend.BackendException.Kind;

/** Drives the real HTTP client against a loopback test server; no running backend is needed. */
public class BackendClientTest {
    private interface Route { void handle(HttpExchange exchange) throws Exception; }

    private HttpServer server;
    private BackendClient client;
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "test-backend");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            try (exchange) {
                Route route = routes.get(path);
                if (route == null) respond(exchange, 404, "{\"detail\":\"Not Found\"}");
                else route.handle(exchange);
            } catch (Exception ignored) {
                // The client under test may have given up on this exchange already.
            }
        });
        server.start();
        ApiFixtures.responses().forEach((path, body) -> routes.put(path, exchange -> respond(exchange, 200, body)));
        client = client(1000, 2000);
    }

    @After
    public void stop() {
        release.countDown();
        client.close();
        server.stop(0);
    }

    private String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private BackendClient client(int connectMillis, int requestMillis) {
        return new BackendClient(new BackendConfig(BackendConfig.Mode.BACKEND, URI.create(url()),
                Duration.ofMillis(connectMillis), Duration.ofMillis(requestMillis)));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
    }

    private void route(String path, int status, String body) { routes.put(path, exchange -> respond(exchange, status, body)); }

    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(15, TimeUnit.SECONDS); }

    private static BackendException failure(CompletableFuture<?> future) throws Exception {
        try {
            Object value = future.get(15, TimeUnit.SECONDS);
            throw new AssertionError("Expected a failure but received " + value);
        } catch (ExecutionException exception) {
            assertTrue("Failures must be BackendExceptions: " + exception.getCause(), exception.getCause() instanceof BackendException);
            return BackendException.from(exception);
        }
    }

    @Test
    public void everyCurriculumEndpointReturnsTypedObjectsFromTheExpectedPath() throws Exception {
        assertTrue(await(client.health()).ok());
        assertEquals(new RemoteCurriculum.Meta(1, List.of(1), 1), await(client.meta()));
        assertEquals("accounting101", await(client.courses()).getFirst().id());
        assertEquals(3, await(client.course("accounting101")).units().size());
        assertEquals(3, await(client.unit("accounting101.recording_transactions")).assignments().size());
        assertEquals(2, await(client.assignment("inventory_fundamentals")).concepts().size());
        assertTrue(await(client.concept("inventory.purchases_on_credit")).lesson().isPresent());
        assertTrue(await(client.concept("payables.cash_payments")).lesson().isEmpty());
        assertEquals(6, await(client.assignmentQuestions("inventory_fundamentals")).questions().size());
        assertEquals(ApiFixtures.responses().keySet().stream().map(path -> "GET " + path).toList(), requests);
    }

    @Test
    public void requestsReturnImmediatelyAndCompleteOnABackendWorkerThread() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1);
        routes.put(ApiFixtures.COURSES, exchange -> {
            arrived.countDown();
            release.await();
            respond(exchange, 200, ApiFixtures.body(ApiFixtures.COURSES));
        });
        long started = System.nanoTime();
        var future = client.courses();
        long callMillis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(arrived.await(10, TimeUnit.SECONDS));
        assertFalse("The caller holds an unfinished future while the server is still working", future.isDone());
        assertTrue("The call itself must not wait for the server (took " + callMillis + " ms)", callMillis < 1000);
        var thread = future.thenApply(courses -> Thread.currentThread());
        release.countDown();
        assertEquals(1, await(future).size());
        assertNotSame(Thread.currentThread(), await(thread));
    }

    @Test
    public void notFoundIsAnHttpStatusFailureWithTheServersDetail() throws Exception {
        route("/api/v1/courses/nope", 404, "{\"detail\":\"Course not found: nope\"}");
        BackendException error = failure(client.course("nope"));
        assertEquals(Kind.HTTP_STATUS, error.kind());
        assertEquals(404, error.status());
        assertTrue(error.backendReachable());
        assertEquals("The backend answered HTTP 404: Course not found: nope", error.getMessage());
    }

    @Test
    public void serverErrorsAreHttpStatusFailuresEvenWithAValidLookingBody() throws Exception {
        route(ApiFixtures.COURSES, 500, "Internal Server Error");
        BackendException error = failure(client.courses());
        assertEquals(Kind.HTTP_STATUS, error.kind());
        assertEquals(500, error.status());
        assertFalse(error.backendReachable());
        assertEquals("The backend answered HTTP 500", error.getMessage());

        route(ApiFixtures.HEALTH, 503, "{\"status\":\"degraded\",\"database\":\"unavailable\"}");
        assertEquals(503, failure(client.health()).status());

        route(ApiFixtures.COURSE, 500, ApiFixtures.body(ApiFixtures.COURSE));
        assertEquals("A course body on a 500 is still an error", 500, failure(client.course("accounting101")).status());
        route(ApiFixtures.UNIT, 204, "");
        assertEquals(204, failure(client.unit("accounting101.recording_transactions")).status());
    }

    @Test
    public void redirectsAreNotFollowed() throws Exception {
        routes.put(ApiFixtures.COURSES, exchange -> {
            exchange.getResponseHeaders().add("Location", url() + ApiFixtures.COURSE);
            exchange.sendResponseHeaders(302, -1);
        });
        BackendException error = failure(client.courses());
        assertEquals(Kind.HTTP_STATUS, error.kind());
        assertEquals(302, error.status());
        assertEquals(List.of("GET " + ApiFixtures.COURSES), requests);
    }

    @Test
    public void malformedBodiesAreMalformedResponseFailures() throws Exception {
        for (String body : List.of("{\"id\": ", "<html>It works!</html>", "", "[]", "null",
                ApiFixtures.body(ApiFixtures.COURSE).replace("\"title\"", "\"name\""), ApiFixtures.body(ApiFixtures.UNIT))) {
            route(ApiFixtures.COURSE, 200, body);
            assertEquals(body, Kind.MALFORMED_RESPONSE, failure(client.course("accounting101")).kind());
        }
        routes.put(ApiFixtures.COURSE, exchange -> {
            byte[] invalidUtf8 = {'{', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};
            exchange.sendResponseHeaders(200, invalidUtf8.length);
            exchange.getResponseBody().write(invalidUtf8);
        });
        BackendException error = failure(client.course("accounting101"));
        assertEquals(Kind.MALFORMED_RESPONSE, error.kind());
        assertTrue(error.getMessage(), error.getMessage().contains("UTF-8"));
    }

    @Test
    public void missingFieldNamesTheField() throws Exception {
        route(ApiFixtures.QUESTIONS, 200, ApiFixtures.body(ApiFixtures.QUESTIONS).replaceFirst("\"prompt\"", "\"question\""));
        BackendException error = failure(client.assignmentQuestions("inventory_fundamentals"));
        assertEquals(Kind.MALFORMED_RESPONSE, error.kind());
        assertTrue(error.getMessage(), error.getMessage().contains("missing field \"prompt\""));
    }

    @Test
    public void aServerThatNeverAnswersTimesOut() throws Exception {
        routes.put(ApiFixtures.COURSES, exchange -> release.await());
        try (BackendClient impatient = client(1000, 300)) {
            long started = System.nanoTime();
            BackendException error = failure(impatient.courses());
            assertEquals(Kind.TIMEOUT, error.kind());
            assertFalse(error.backendReachable());
            assertTrue("Timed out promptly", (System.nanoTime() - started) / 1_000_000 < 5000);
        }
    }

    @Test
    public void aResponseBodyThatStallsTimesOut() throws Exception {
        routes.put(ApiFixtures.COURSES, exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("[{\"id\":".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            release.await();
        });
        try (BackendClient impatient = client(200, 300)) {
            assertEquals(Kind.TIMEOUT, failure(impatient.courses()).kind());
        }
    }

    @Test
    public void aBackendThatIsNotRunningIsUnreachable() throws Exception {
        server.stop(0);
        for (var call : List.<java.util.function.Supplier<CompletableFuture<?>>>of(client::health, client::courses,
                () -> client.course("accounting101"), () -> client.assignmentQuestions("inventory_fundamentals"))) {
            BackendException error = failure(call.get());
            assertEquals(Kind.UNREACHABLE, error.kind());
            assertFalse(error.backendReachable());
            assertEquals("Cannot reach the backend. Is the server running?", error.getMessage());
        }
    }

    @Test
    public void aSlowServerStillSucceedsWithinTheTimeout() throws Exception {
        routes.put(ApiFixtures.COURSES, exchange -> {
            Thread.sleep(300);
            respond(exchange, 200, ApiFixtures.body(ApiFixtures.COURSES));
        });
        assertEquals(1, await(client.courses()).size());
    }

    @Test
    public void idsAreSentAsOneEncodedPathSegment() throws Exception {
        route("/api/v1/courses/a%20b%2F..%2Fc%3Fx%3D1%23y", 404, "{\"detail\":\"Course not found\"}");
        assertEquals(404, failure(client.course("a b/../c?x=1#y")).status());
        assertEquals(List.of("GET /api/v1/courses/a%20b%2F..%2Fc%3Fx%3D1%23y"), requests);
        for (String blank : new String[] {null, "", "  "}) {
            try {
                client.unit(blank);
                fail("A blank ID must not become a request");
            } catch (IllegalArgumentException expected) {
                assertEquals(1, requests.size());
            }
        }
    }

    @Test
    public void baseUrlPathPrefixIsKept() throws Exception {
        route("/mineledger/api/v1/courses", 200, ApiFixtures.body(ApiFixtures.COURSES));
        BackendConfig config = BackendConfig.parse("{\"mode\":\"backend\",\"backend_url\":\"" + url() + "/mineledger/\"}");
        try (BackendClient prefixed = new BackendClient(config)) {
            assertEquals(1, await(prefixed.courses()).size());
        }
        assertEquals(List.of("GET /mineledger/api/v1/courses"), requests);
    }

    @Test
    public void concurrentRequestsAllComplete() throws Exception {
        var futures = new java.util.ArrayList<CompletableFuture<?>>();
        for (int i = 0; i < 25; i++) {
            futures.add(client.courses());
            futures.add(client.assignmentQuestions("inventory_fundamentals"));
        }
        for (var future : futures) assertNotNull(await(future));
        assertEquals(50, requests.size());
    }

    @Test
    public void aClosedClientFailsRequestsInsteadOfHanging() throws Exception {
        routes.put(ApiFixtures.COURSES, exchange -> release.await());
        BackendClient closing = client(1000, 2000);
        var pending = closing.courses();
        closing.close();
        try {
            Object value = pending.get(15, TimeUnit.SECONDS);
            fail("Expected failure, got " + value);
        } catch (ExecutionException | java.util.concurrent.CancellationException expected) {
            // Any prompt failure is acceptable once the client is shut down.
        }
    }
}
