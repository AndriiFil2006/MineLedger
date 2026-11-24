package com.mineledger.backend.auth;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import com.mineledger.backend.BackendException;

/** Test doubles for sign-in: every answer, and every turn of the client thread, happens only when a test says so. */
final class FakeAuth {
    static final String ISSUER = "https://tenant.example-idp.test/";
    static final String CLIENT_ID = "public-client-id";
    static final String AUDIENCE = "https://api.mineledger.test";
    static final AuthSettings SETTINGS = new AuthSettings(ISSUER, CLIENT_ID, AUDIENCE);
    static final OidcProvider.Metadata METADATA = new OidcProvider.Metadata(URI.create(ISSUER + "authorize"),
            URI.create(ISSUER + "oauth/token"), Optional.of(URI.create(ISSUER + "oauth/revoke")));
    static final String USER_ID = "6f1d2c3b-4a59-4e7f-8a1b-2c3d4e5f6a7b";

    /** A queue of calls a test answers one at a time. */
    static final class Calls<T> {
        private final ArrayDeque<CompletableFuture<T>> pending = new ArrayDeque<>();
        int count;

        CompletableFuture<T> add() {
            count++;
            CompletableFuture<T> future = new CompletableFuture<>();
            pending.add(future);
            return future;
        }

        boolean waiting() { return !pending.isEmpty(); }
        void answer(T value) { pending.remove().complete(value); }
        void fail(Throwable failure) { pending.remove().completeExceptionally(failure); }
    }

    static final class Api implements AccountApi {
        final Calls<Optional<AuthSettings>> settings = new Calls<>();
        final Calls<RemoteUser> me = new Calls<>();
        final List<String> tokensSeen = new ArrayList<>();

        @Override public CompletableFuture<Optional<AuthSettings>> authSettings() { return settings.add(); }
        @Override public CompletableFuture<RemoteUser> me(String accessToken) {
            tokensSeen.add(accessToken);
            return me.add();
        }
    }

    static final class Provider implements OidcProvider {
        record Exchange(String clientId, String code, URI redirectUri, String codeVerifier) { }

        final Calls<Metadata> discoveries = new Calls<>();
        final Calls<Tokens> exchanges = new Calls<>();
        final Calls<Tokens> refreshes = new Calls<>();
        final List<String> issuersDiscovered = new ArrayList<>();
        final List<Exchange> exchanged = new ArrayList<>();
        final List<String> refreshTokensUsed = new ArrayList<>();
        final List<String> revoked = new ArrayList<>();

        @Override public CompletableFuture<Metadata> discover(String issuer) {
            issuersDiscovered.add(issuer);
            return discoveries.add();
        }
        @Override public CompletableFuture<Tokens> exchangeCode(Metadata metadata, String clientId, String code, URI redirectUri, String verifier) {
            exchanged.add(new Exchange(clientId, code, redirectUri, verifier));
            return exchanges.add();
        }
        @Override public CompletableFuture<Tokens> refresh(Metadata metadata, String clientId, String refreshToken) {
            refreshTokensUsed.add(refreshToken);
            return refreshes.add();
        }
        @Override public CompletableFuture<Void> revoke(Metadata metadata, String clientId, String refreshToken) {
            revoked.add(refreshToken);
            return CompletableFuture.completedFuture(null);
        }
    }

    static final class Receiver implements CallbackReceiver {
        static final URI REDIRECT = URI.create("http://127.0.0.1:47821/callback");
        final List<String> states = new ArrayList<>();
        final List<CompletableFuture<Map<String, String>>> callbacks = new ArrayList<>();
        int closed;
        AuthException failToStart;

        @Override public Listening start(String expectedState) {
            if (failToStart != null) throw failToStart;
            states.add(expectedState);
            CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();
            callbacks.add(callback);
            return new Listening() {
                @Override public URI redirectUri() { return REDIRECT; }
                @Override public CompletableFuture<Map<String, String>> callback() { return callback; }
                @Override public void close() { closed++; }
            };
        }

        String state() { return states.getLast(); }
        void redirect(Map<String, String> parameters) { callbacks.getLast().complete(parameters); }
    }

    static final class Store implements RefreshTokenStore {
        Stored stored;
        int saves;
        boolean failToSave;

        @Override public Optional<Stored> load() { return Optional.ofNullable(stored); }
        @Override public void save(Stored value) throws java.io.IOException {
            if (failToSave) throw new java.io.IOException("disk full");
            saves++;
            stored = value;
        }
        @Override public void clear() { stored = null; }
    }

    /** Stands in for Minecraft's client thread: nothing runs until the test drains it. */
    static final class OwnerThread implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public synchronized void execute(Runnable task) { tasks.add(task); }
        synchronized boolean hasWork() { return !tasks.isEmpty(); }
        boolean drain() {
            boolean any = false;
            while (true) {
                Runnable next;
                synchronized (this) {
                    next = tasks.poll();
                }
                if (next == null) return any;
                next.run();
                any = true;
            }
        }
    }

    static Map<String, String> query(URI uri) {
        Map<String, String> result = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int equals = pair.indexOf('=');
            result.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return result;
    }

    /** An unsigned-looking ID token with the given claims; the client never checks its signature. */
    static String idToken(String issuer, String audience, String nonce, long expiresAtSeconds, String name) {
        String claims = "{\"iss\":\"" + issuer + "\",\"aud\":\"" + audience + "\",\"nonce\":\"" + nonce + "\",\"exp\":" + expiresAtSeconds
                + (name == null ? "" : ",\"name\":\"" + name + "\"") + "}";
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".c2lnbmF0dXJl";
    }

    static BackendException http(int status) {
        return new BackendException(BackendException.Kind.HTTP_STATUS, status, "The backend answered HTTP " + status, null);
    }

    static BackendException unreachable() { return BackendException.from(new java.net.ConnectException("refused")); }

    private FakeAuth() { }
}
