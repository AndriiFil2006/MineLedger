package com.mineledger.backend.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineledger.backend.auth.AuthException.Kind;

/**
 * Talks to the identity provider's discovery, token and revocation endpoints as a public client: it sends
 * a client ID and never a client secret. No Minecraft types; nothing blocks the caller. Redirects are not
 * followed, and every endpoint must be https (or this computer).
 *
 * <p>Nothing here logs. Error messages name the provider's error code at most, never a response body.
 */
public final class OidcHttpClient implements OidcProvider, AutoCloseable {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private ExecutorService executor;
    private HttpClient http;

    public OidcHttpClient(Duration connectTimeout, Duration requestTimeout) {
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
    }

    /** Built on first use, so a player who never signs in never creates it. */
    private synchronized HttpClient http() {
        if (http == null) {
            ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
                Thread thread = new Thread(task, "MineLedger-SignIn-Http");
                thread.setDaemon(true);
                return thread;
            });
            pool.allowCoreThreadTimeOut(true);
            executor = pool;
            http = HttpClient.newBuilder().executor(executor).connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        }
        return http;
    }

    @Override
    public CompletableFuture<Metadata> discover(String issuer) {
        URI document;
        try {
            document = URI.create(issuer + (issuer.endsWith("/") ? "" : "/") + ".well-known/openid-configuration");
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(new AuthException(Kind.NOT_AVAILABLE, "The sign-in service address is not valid."));
        }
        return send(HttpRequest.newBuilder(document).GET(), body -> {
            JsonObject json = object(body);
            // Exact match, as OpenID Connect Discovery requires: a document for another issuer is refused.
            if (!issuer.equals(string(json, "issuer").orElse(null))) throw AuthException.protocol("the sign-in service did not identify itself correctly.");
            return new Metadata(endpoint(json, "authorization_endpoint").orElseThrow(() -> AuthException.protocol("the sign-in service has no sign-in page.")),
                    endpoint(json, "token_endpoint").orElseThrow(() -> AuthException.protocol("the sign-in service has no token endpoint.")),
                    endpoint(json, "revocation_endpoint"));
        });
    }

    @Override
    public CompletableFuture<Tokens> exchangeCode(Metadata metadata, String clientId, String code, URI redirectUri, String codeVerifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", clientId);
        form.put("code", code);
        form.put("redirect_uri", redirectUri.toString());
        form.put("code_verifier", codeVerifier);
        return send(post(metadata.tokenEndpoint(), form), OidcHttpClient::tokens);
    }

    @Override
    public CompletableFuture<Tokens> refresh(Metadata metadata, String clientId, String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", clientId);
        form.put("refresh_token", refreshToken);
        return send(post(metadata.tokenEndpoint(), form), OidcHttpClient::tokens);
    }

    @Override
    public CompletableFuture<Void> revoke(Metadata metadata, String clientId, String refreshToken) {
        if (metadata.revocationEndpoint().isEmpty()) return CompletableFuture.completedFuture(null);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("token", refreshToken);
        form.put("token_type_hint", "refresh_token");
        return send(post(metadata.revocationEndpoint().get(), form), body -> (Void) null).exceptionally(failure -> null);
    }

    private static HttpRequest.Builder post(URI endpoint, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return HttpRequest.newBuilder(endpoint).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }

    private <T> CompletableFuture<T> send(HttpRequest.Builder builder, Function<String, T> parser) {
        CompletableFuture<HttpResponse<byte[]>> exchange;
        try {
            HttpRequest request = builder.timeout(requestTimeout).header("Accept", "application/json").build();
            if (!AuthSettings.secure(request.uri())) {
                return CompletableFuture.failedFuture(new AuthException(Kind.NOT_AVAILABLE, "The sign-in service must use https."));
            }
            exchange = http().sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(AuthException.network(exception));
        }
        long deadline = connectTimeout.plus(requestTimeout).plusMillis(500).toMillis();
        return exchange.copy().orTimeout(deadline, TimeUnit.MILLISECONDS).handle((response, failure) -> {
            if (failure != null) {
                exchange.cancel(true);
                Throwable cause = AuthException.unwrap(failure);
                throw cause instanceof AuthException known ? known : AuthException.network(cause);
            }
            if (response.body().length > MAX_RESPONSE_BYTES) throw AuthException.protocol("the sign-in service sent too much data.");
            String body = new String(response.body(), StandardCharsets.UTF_8);
            int status = response.statusCode();
            if (status >= 200 && status < 300) return parser.apply(body);
            if (status >= 500) throw new AuthException(Kind.NETWORK, "The sign-in service is having problems (HTTP " + status + "). Try again later.");
            throw new AuthException(Kind.REJECTED, "The sign-in service refused the request (" + errorCode(body).orElse("HTTP " + status) + ").");
        });
    }

    private static Tokens tokens(String body) {
        JsonObject json = object(body);
        String access = string(json, "access_token").orElseThrow(() -> AuthException.protocol("no access token was issued."));
        if (!string(json, "token_type").map(type -> type.equalsIgnoreCase("Bearer")).orElse(false)) {
            throw AuthException.protocol("the sign-in service issued a token type this game cannot use.");
        }
        long expiresIn;
        try {
            JsonElement value = json.get("expires_in");
            expiresIn = value == null || !value.isJsonPrimitive() ? 0 : value.getAsBigDecimal().longValue();
        } catch (RuntimeException exception) {
            expiresIn = 0;
        }
        if (expiresIn <= 0) throw AuthException.protocol("the sign-in service did not say when the session expires.");
        return new Tokens(access, expiresIn, string(json, "refresh_token"), string(json, "id_token"));
    }

    /** An OAuth error code such as {@code invalid_grant}, restricted to the characters such codes use. */
    private static Optional<String> errorCode(String body) {
        try {
            return string(JsonParser.parseString(body).getAsJsonObject(), "error").filter(code -> code.matches("[a-z_]{1,40}"));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static JsonObject object(String body) {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw AuthException.protocol("the sign-in service sent an answer this game cannot read.");
        }
    }

    private static Optional<String> string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.getAsString());
    }

    private static Optional<URI> endpoint(JsonObject json, String key) {
        Optional<String> text = string(json, key);
        if (text.isEmpty()) return Optional.empty();
        URI uri;
        try {
            uri = URI.create(text.get());
        } catch (IllegalArgumentException exception) {
            throw AuthException.protocol("the sign-in service published an invalid address.");
        }
        if (!AuthSettings.secure(uri)) throw new AuthException(Kind.NOT_AVAILABLE, "The sign-in service must use https.");
        return Optional.of(uri);
    }

    /** Abandons requests in flight; their futures fail. */
    @Override
    public synchronized void close() {
        if (http != null) http.shutdownNow();
        if (executor != null) executor.shutdownNow();
    }
}
