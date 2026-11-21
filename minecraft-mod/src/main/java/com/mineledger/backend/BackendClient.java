package com.mineledger.backend;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import com.mineledger.backend.RemoteCurriculum.*;

/**
 * HTTP transport for the FastAPI backend. No Minecraft or GUI types; nothing here blocks the calling thread.
 * Requests and response parsing run on two daemon worker threads. Redirects are not followed.
 */
public final class BackendClient implements CurriculumApi, AutoCloseable {
    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final AtomicInteger THREADS = new AtomicInteger();

    private final String baseUrl;
    private final Duration requestTimeout;
    private final Duration deadline;
    private final ExecutorService executor;
    private final HttpClient http;

    public BackendClient(BackendConfig config) {
        baseUrl = config.baseUrl().toString();
        requestTimeout = config.requestTimeout();
        // HttpRequest.timeout stops at the response headers on some JDKs; this bounds the whole exchange.
        deadline = config.connectTimeout().plus(config.requestTimeout()).plusMillis(500);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "MineLedger-Backend-" + THREADS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        pool.allowCoreThreadTimeOut(true);
        executor = pool;
        http = HttpClient.newBuilder().executor(executor).connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public CompletableFuture<Health> health() { return get("/health", BackendJson::health); }
    @Override public CompletableFuture<List<CourseSummary>> courses() { return get("/api/v1/courses", BackendJson::courses); }
    @Override public CompletableFuture<CourseDetail> course(String id) { return get("/api/v1/courses/" + segment(id), BackendJson::course); }
    @Override public CompletableFuture<UnitDetail> unit(String id) { return get("/api/v1/units/" + segment(id), BackendJson::unit); }
    @Override public CompletableFuture<AssignmentDetail> assignment(String id) {
        return get("/api/v1/assignments/" + segment(id), BackendJson::assignment);
    }
    @Override public CompletableFuture<ConceptDetail> concept(String id) { return get("/api/v1/concepts/" + segment(id), BackendJson::concept); }
    @Override public CompletableFuture<AssignmentQuestions> assignmentQuestions(String id) {
        return get("/api/v1/assignments/" + segment(id) + "/questions", BackendJson::questions);
    }

    private static String segment(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("An entity ID is required");
        return URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private <T> CompletableFuture<T> get(String path, Function<String, T> parser) {
        CompletableFuture<HttpResponse<byte[]>> exchange;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(requestTimeout)
                    .header("Accept", "application/json").GET().build();
            exchange = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(BackendException.from(exception));
        }
        return exchange.copy().orTimeout(deadline.toMillis(), TimeUnit.MILLISECONDS).handleAsync((response, failure) -> {
            if (failure != null) {
                exchange.cancel(true);
                throw BackendException.from(failure);
            }
            return parser.apply(body(response));
        }, executor);
    }

    private static String body(HttpResponse<byte[]> response) {
        byte[] bytes = response.body();
        if (bytes.length > MAX_RESPONSE_BYTES) throw BackendException.malformed("the response is too large");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            if (response.statusCode() == 200) throw BackendException.malformed("the response is not UTF-8 text");
            text = "";
        }
        int status = response.statusCode();
        if (status != 200) {
            String detail = BackendJson.errorDetail(text).map(message -> ": " + message).orElse("");
            throw new BackendException(BackendException.Kind.HTTP_STATUS, status,
                    "The backend answered HTTP " + status + detail, null);
        }
        return text;
    }

    /** Abandons in-flight requests; their futures fail instead of completing. */
    @Override
    public void close() {
        http.shutdownNow();
        executor.shutdownNow();
    }
}
