package com.mineledger.backend.auth;

import static org.junit.Assert.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.mineledger.backend.BackendClient;
import com.mineledger.backend.BackendConfig;
import com.mineledger.backend.auth.AccountApi.Role;
import com.mineledger.backend.auth.SignInManager.State;

/**
 * The whole sign-in over real HTTP: the real backend client, the real identity-provider client, the real
 * loopback listener and the real session file, against one loopback server that plays both the identity
 * provider and the MineLedger server. Only the browser is simulated, by following the redirect.
 */
public class SignInFlowTest {
    private static final String CLIENT_ID = "native-client-id";
    private static final String AUDIENCE = "https://api.mineledger.test";
    private static final String USER_ID = FakeAuth.USER_ID;

    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private HttpServer server;
    private BackendClient backend;
    private OidcHttpClient provider;
    private ExecutorService clientThread;
    private final HttpClient browserHttp = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    // What the fake identity provider and server remember.
    private final Map<String, Map<String, String>> codes = new ConcurrentHashMap<>();
    private final Map<String, String> refreshTokens = new ConcurrentHashMap<>();
    private final List<String> validAccessTokens = new CopyOnWriteArrayList<>();
    private final List<String> revoked = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
    private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger issued = new AtomicInteger();
    private final AtomicReference<String> role = new AtomicReference<>("student");
    private final AtomicReference<String> authorizeError = new AtomicReference<>();
    private volatile boolean serverDown;
    private volatile boolean serverTrustsAnotherIssuer;
    private final java.util.concurrent.atomic.AtomicLong clockOffsetMillis = new java.util.concurrent.atomic.AtomicLong();

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "test-idp");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", this::handle);
        server.start();
        BackendConfig config = new BackendConfig(BackendConfig.Mode.AUTO, URI.create(url()), Duration.ofMillis(2000), Duration.ofMillis(4000));
        backend = new BackendClient(config);
        provider = new OidcHttpClient(config.connectTimeout(), config.requestTimeout());
        clientThread = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "test-client-thread");
            thread.setDaemon(true);
            return thread;
        });
    }

    @After
    public void stop() {
        backend.close();
        provider.close();
        clientThread.shutdownNow();
        server.stop(0);
    }

    private String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    // --- The fake identity provider and MineLedger server ---------------------------------------------

    private void handle(HttpExchange exchange) {
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) authorizationHeaders.add(path + " " + authorization);
            switch (path) {
                case "/.well-known/openid-configuration" -> json(exchange, 200, "{\"issuer\":\"" + url() + "\",\"authorization_endpoint\":\"" + url()
                        + "/authorize\",\"token_endpoint\":\"" + url() + "/oauth/token\",\"revocation_endpoint\":\"" + url() + "/oauth/revoke\"}");
                case "/authorize" -> authorize(exchange);
                case "/oauth/token" -> token(exchange);
                case "/oauth/revoke" -> {
                    revoked.add(form(exchange).get("token"));
                    refreshTokens.remove(revoked.getLast());
                    json(exchange, 200, "{}");
                }
                case "/api/v1/auth/config" -> json(exchange, 200, "{\"enabled\":true,\"issuer\":\"" + url() + "\",\"audience\":\"" + AUDIENCE
                        + "\",\"client_id\":\"" + CLIENT_ID + "\"}");
                case "/api/v1/users/me" -> {
                    if (serverDown) json(exchange, 500, "{\"detail\":\"boom\"}");
                    else if (serverTrustsAnotherIssuer || authorization == null || !authorization.startsWith("Bearer ")
                            || !validAccessTokens.contains(authorization.substring(7))) {
                        json(exchange, 401, "{\"detail\":\"Not authenticated\"}");
                    } else {
                        json(exchange, 200, "{\"id\":\"" + USER_ID + "\",\"role\":\"" + role.get() + "\",\"created_at\":\"2026-11-24T10:00:00Z\"}");
                    }
                }
                default -> json(exchange, 404, "{\"detail\":\"Not Found\"}");
            }
        } catch (Exception ignored) {
            // The client under test may have gone away.
        }
    }

    private void authorize(HttpExchange exchange) throws Exception {
        Map<String, String> query = parse(exchange.getRequestURI().getRawQuery());
        String redirect = query.get("redirect_uri");
        String location;
        if (authorizeError.get() != null) {
            location = redirect + "?error=" + authorizeError.get() + "&state=" + query.get("state");
        } else {
            String code = "code-" + issued.incrementAndGet();
            codes.put(code, query);
            location = redirect + "?code=" + code + "&state=" + query.get("state");
        }
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private void token(HttpExchange exchange) throws Exception {
        Map<String, String> form = form(exchange);
        tokenRequests.add(form);
        String refresh;
        String nonce = null;
        if ("authorization_code".equals(form.get("grant_type"))) {
            Map<String, String> request = codes.remove(String.valueOf(form.get("code")));
            boolean valid = request != null && CLIENT_ID.equals(form.get("client_id")) && request.get("redirect_uri").equals(form.get("redirect_uri"))
                    && "S256".equals(request.get("code_challenge_method")) && form.get("code_verifier") != null
                    && request.get("code_challenge").equals(Pkce.fromVerifier(form.get("code_verifier")).challenge());
            if (!valid) {
                json(exchange, 403, "{\"error\":\"invalid_grant\",\"error_description\":\"secret detail the player must not see\"}");
                return;
            }
            nonce = request.get("nonce");
        } else if ("refresh_token".equals(form.get("grant_type"))) {
            // Rotation: a refresh token works once.
            if (!CLIENT_ID.equals(form.get("client_id")) || refreshTokens.remove(String.valueOf(form.get("refresh_token"))) == null) {
                json(exchange, 403, "{\"error\":\"invalid_grant\"}");
                return;
            }
        } else {
            json(exchange, 400, "{\"error\":\"unsupported_grant_type\"}");
            return;
        }
        int number = issued.incrementAndGet();
        String access = "access-" + number;
        refresh = "refresh-" + number;
        validAccessTokens.add(access);
        refreshTokens.put(refresh, access);
        String idToken = nonce == null ? "" : ",\"id_token\":\"" + FakeAuth.idToken(url(), CLIENT_ID, nonce, System.currentTimeMillis() / 1000 + 600, "Alex Kim") + "\"";
        json(exchange, 200, "{\"access_token\":\"" + access + "\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"refresh_token\":\"" + refresh + "\"" + idToken + "}");
    }

    private static Map<String, String> form(HttpExchange exchange) throws Exception {
        assertEquals("POST", exchange.getRequestMethod());
        return parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static Map<String, String> parse(String encoded) {
        Map<String, String> result = new HashMap<>();
        if (encoded == null || encoded.isEmpty()) return result;
        for (String pair : encoded.split("&")) {
            int equals = pair.indexOf('=');
            result.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return result;
    }

    private static void json(HttpExchange exchange, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    // --- Driving the manager on its own "client thread" -----------------------------------------------

    /** What a person does in the browser: load the sign-in page, which redirects back to the game. */
    private void browse(URI authorizationUrl) {
        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<Void> page = browserHttp.send(HttpRequest.newBuilder(authorizationUrl).GET().build(), HttpResponse.BodyHandlers.discarding());
                String location = page.headers().firstValue("Location").orElseThrow();
                browserHttp.send(HttpRequest.newBuilder(URI.create(location)).GET().build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private SignInManager manager(Consumer<URI> browser) {
        return new SignInManager(() -> backend, provider, new LoopbackCallbackServer(List.of(0)), browser,
                RefreshTokenStore.file(folder.getRoot().toPath()), clientThread, () -> System.currentTimeMillis() + clockOffsetMillis.get(),
                AuthSettings.secure(url()));
    }

    private <T> T onClientThread(Supplier<T> action) throws Exception {
        return CompletableFuture.supplyAsync(action, clientThread).get(10, TimeUnit.SECONDS);
    }

    private void run(Runnable action) throws Exception {
        onClientThread(() -> {
            action.run();
            return null;
        });
    }

    private void await(SignInManager manager, State expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (onClientThread(manager::state) == expected) return;
            Thread.sleep(20);
        }
        fail("Still " + onClientThread(manager::state) + " (problem: " + onClientThread(manager::problem) + "), expected " + expected);
    }

    private Path sessionFile() { return folder.getRoot().toPath().resolve("mineledger").resolve("session.json"); }

    private SignInManager signedIn() throws Exception {
        SignInManager manager = manager(this::browse);
        run(manager::signIn);
        await(manager, State.SIGNED_IN);
        return manager;
    }

    // --- Tests ---------------------------------------------------------------------------------------

    @Test
    public void aPlayerSignsInThroughTheBrowserAndTheServerConfirmsWhoTheyAre() throws Exception {
        AtomicReference<URI> opened = new AtomicReference<>();
        SignInManager manager = manager(uri -> {
            opened.set(uri);
            browse(uri);
        });
        long started = System.nanoTime();
        run(manager::signIn);
        assertTrue("Sign In returns at once; nothing blocks the client thread", (System.nanoTime() - started) / 1_000_000 < 2000);
        await(manager, State.SIGNED_IN);

        SignInManager.Account account = onClientThread(() -> manager.account().orElseThrow());
        assertEquals(USER_ID, account.user().id());
        assertEquals(Role.STUDENT, account.user().role());
        assertEquals(Optional.of("Alex Kim"), account.displayName());

        Map<String, String> query = FakeAuth.query(opened.get());
        assertTrue(query.get("redirect_uri").matches("http://127\\.0\\.0\\.1:\\d+/callback"));
        assertEquals(AUDIENCE, query.get("audience"));
        Map<String, String> exchange = tokenRequests.getFirst();
        assertEquals("authorization_code", exchange.get("grant_type"));
        assertEquals(query.get("code_challenge"), Pkce.fromVerifier(exchange.get("code_verifier")).challenge());
        assertFalse("A public client sends no secret", exchange.containsKey("client_secret"));

        assertEquals("The access token goes to the MineLedger server, as a bearer header",
                List.of("/api/v1/users/me Bearer access-2"), authorizationHeaders);
        assertEquals("access-2", onClientThread(() -> manager.accessToken().join()));

        String saved = Files.readString(sessionFile());
        assertTrue(saved.contains("refresh-2"));
        assertFalse("The access token is never written to disk", saved.contains("access-2"));
        assertFalse(Files.exists(folder.getRoot().toPath().resolve("mineledger").resolve("config.json")));
    }

    @Test
    public void aCodeCannotBeRedeemedWithoutItsVerifier() throws Exception {
        signedIn();
        Map<String, String> used = tokenRequests.getFirst();
        // An attacker who saw the redirect has the code (already spent) but never the verifier.
        HttpResponse<String> replay = browserHttp.send(HttpRequest.newBuilder(URI.create(url() + "/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=authorization_code&client_id=" + CLIENT_ID + "&code=" + used.get("code")
                        + "&redirect_uri=" + used.get("redirect_uri") + "&code_verifier=guess")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, replay.statusCode());
    }

    @Test
    public void aRestartRestoresTheSignInWithoutABrowserAndRotatesTheToken() throws Exception {
        signedIn();
        assertTrue(Files.readString(sessionFile()).contains("refresh-2"));

        SignInManager afterRestart = manager(uri -> fail("A remembered sign-in must not open the browser"));
        assertEquals(State.SIGNED_OUT, onClientThread(afterRestart::state));
        run(afterRestart::appOpened);
        await(afterRestart, State.SIGNED_IN);
        assertEquals(Optional.of("Alex Kim"), onClientThread(() -> afterRestart.account().orElseThrow().displayName()));
        assertEquals("refresh_token", tokenRequests.getLast().get("grant_type"));
        assertEquals("refresh-2", tokenRequests.getLast().get("refresh_token"));
        String saved = Files.readString(sessionFile());
        assertTrue(saved.contains("refresh-3"));
        assertFalse(saved.contains("refresh-2"));
        assertEquals("/api/v1/users/me Bearer access-3", authorizationHeaders.getLast());
    }

    @Test
    public void signOutRevokesTheRefreshTokenAndRemovesTheSessionFile() throws Exception {
        SignInManager manager = signedIn();
        run(manager::signOut);
        assertEquals(State.SIGNED_OUT, onClientThread(manager::state));
        assertFalse(Files.exists(sessionFile()));
        for (int i = 0; i < 250 && revoked.isEmpty(); i++) Thread.sleep(20);
        assertEquals(List.of("refresh-2"), revoked);

        SignInManager afterRestart = manager(uri -> fail("Nothing is remembered after Sign Out"));
        run(afterRestart::appOpened);
        assertEquals(State.SIGNED_OUT, onClientThread(afterRestart::state));
    }

    @Test
    public void cancellingClosesTheListenerAndALateBrowserGetsNowhere() throws Exception {
        AtomicReference<URI> opened = new AtomicReference<>();
        SignInManager manager = manager(opened::set); // The player never finishes in the browser.
        run(manager::signIn);
        await(manager, State.WAITING_FOR_BROWSER);
        for (int i = 0; i < 250 && opened.get() == null; i++) Thread.sleep(20);
        URI redirect = URI.create(FakeAuth.query(opened.get()).get("redirect_uri"));
        try (Socket listening = new Socket("127.0.0.1", redirect.getPort())) {
            assertTrue(listening.isConnected());
        }

        run(manager::cancel);
        assertEquals(State.SIGNED_OUT, onClientThread(manager::state));
        assertEquals(Optional.empty(), onClientThread(manager::problem));
        assertThrows(java.io.IOException.class, () -> new Socket("127.0.0.1", redirect.getPort()).close());
        assertTrue(tokenRequests.isEmpty());
        assertTrue(authorizationHeaders.isEmpty());
        assertFalse(Files.exists(sessionFile()));
    }

    @Test
    public void refusingInTheBrowserLeavesThePlayerSignedOut() throws Exception {
        authorizeError.set("access_denied");
        SignInManager manager = manager(this::browse);
        run(manager::signIn);
        await(manager, State.SIGNED_OUT);
        for (int i = 0; i < 250 && onClientThread(manager::problem).isEmpty(); i++) Thread.sleep(20);
        assertTrue(onClientThread(manager::problem).orElse("").contains("cancelled or refused"));
        assertTrue(tokenRequests.isEmpty());
    }

    @Test
    public void aTokenTheServerRejectsDoesNotSignAnyoneIn() throws Exception {
        serverTrustsAnotherIssuer = true; // As if the server were configured for a different issuer or audience.
        SignInManager manager = manager(this::browse);
        run(manager::signIn);
        for (int i = 0; i < 500 && onClientThread(manager::problem).isEmpty(); i++) Thread.sleep(20);
        assertEquals(State.SIGNED_OUT, onClientThread(manager::state));
        assertTrue(onClientThread(manager::problem).orElse("").contains("did not accept this sign-in"));
        assertEquals("/api/v1/users/me Bearer access-2", authorizationHeaders.getLast());
        assertFalse(Files.exists(sessionFile()));
        for (int i = 0; i < 250 && revoked.isEmpty(); i++) Thread.sleep(20);
        assertEquals("The unusable session is revoked", List.of("refresh-2"), revoked);
    }

    @Test
    public void anExpiredOrRejectedAccessTokenIsReplacedThroughTheRefreshToken() throws Exception {
        SignInManager manager = signedIn();

        // Past its lifetime: the next use renews it first, without a browser.
        clockOffsetMillis.addAndGet(2 * 3600_000L);
        CompletableFuture<String> renewed = new CompletableFuture<>();
        run(() -> manager.accessToken().whenComplete((token, failure) -> renewed.complete(failure == null ? token : failure.toString())));
        assertEquals("access-3", renewed.get(10, TimeUnit.SECONDS));
        assertTrue(Files.readString(sessionFile()).contains("refresh-3"));

        // Rejected early by the server: the account check renews once and asks again.
        validAccessTokens.clear();
        clockOffsetMillis.addAndGet(SignInManager.VERIFY_AFTER_MILLIS);
        run(manager::appOpened);
        for (int i = 0; i < 500 && !"/api/v1/users/me Bearer access-4".equals(authorizationHeaders.getLast()); i++) Thread.sleep(20);
        assertEquals(List.of("/api/v1/users/me Bearer access-2", "/api/v1/users/me Bearer access-3", "/api/v1/users/me Bearer access-4"),
                authorizationHeaders);
        Thread.sleep(100);
        assertEquals(State.SIGNED_IN, onClientThread(manager::state));
        assertEquals("access-4", onClientThread(() -> manager.accessToken().join()));
    }

    @Test
    public void aRefreshTokenTheProviderNoLongerAcceptsEndsTheSession() throws Exception {
        SignInManager manager = signedIn();
        refreshTokens.clear(); // Revoked at the provider, for example from its dashboard.
        clockOffsetMillis.addAndGet(2 * 3600_000L);
        run(() -> manager.accessToken());
        await(manager, State.SIGNED_OUT);
        assertTrue(onClientThread(manager::problem).orElse("").contains("expired"));
        assertFalse(Files.exists(sessionFile()));
    }

    @Test
    public void aRememberedSignInSurvivesAServerOutageAndIsRestoredLater() throws Exception {
        signedIn();
        serverDown = true;
        SignInManager afterRestart = manager(uri -> fail("no browser"));
        run(afterRestart::appOpened);
        for (int i = 0; i < 500 && onClientThread(afterRestart::problem).isEmpty(); i++) Thread.sleep(20);
        assertEquals(State.SIGNED_OUT, onClientThread(afterRestart::state));
        assertTrue(onClientThread(afterRestart::problem).orElse("").contains("could not be confirmed"));
        assertTrue("The rotated token was kept", Files.readString(sessionFile()).contains("refresh-3"));
        assertTrue(revoked.isEmpty());

        serverDown = false;
        run(afterRestart::appOpened);
        await(afterRestart, State.SIGNED_IN);
        assertEquals(Role.STUDENT, onClientThread(() -> afterRestart.account().orElseThrow().user().role()));
    }

    @Test
    public void curriculumRequestsNeverCarryTheAccessToken() throws Exception {
        signedIn();
        authorizationHeaders.clear();
        requests.clear();
        try {
            backend.health().get(10, TimeUnit.SECONDS);
        } catch (Exception expected) {
            // This fake server has no curriculum; only the request headers matter here.
        }
        backend.authSettings().get(10, TimeUnit.SECONDS);
        assertTrue(requests.contains("GET /health"));
        assertTrue("Only /users/me is ever sent the token", authorizationHeaders.isEmpty());
    }

    @Test
    public void anIdentityProviderThatDescribesAnotherIssuerIsRefused() throws Exception {
        // A trailing slash is a different issuer: the match is exact.
        Throwable failure = AuthException.unwrap(provider.discover(url() + "/").handle((metadata, error) -> error).get(10, TimeUnit.SECONDS));
        assertEquals(AuthException.Kind.PROTOCOL, ((AuthException) failure).kind());
        assertEquals(URI.create(url() + "/oauth/token"), provider.discover(url()).get(10, TimeUnit.SECONDS).tokenEndpoint());
    }

    @Test
    public void providerErrorsAreReportedByCodeAndNeverByBody() throws Exception {
        OidcProvider.Metadata metadata = provider.discover(url()).get(10, TimeUnit.SECONDS);
        Throwable failure = AuthException.unwrap(provider.refresh(metadata, CLIENT_ID, "never-issued").handle((tokens, error) -> error).get(10, TimeUnit.SECONDS));
        assertEquals(AuthException.Kind.REJECTED, ((AuthException) failure).kind());
        assertTrue(failure.getMessage().contains("invalid_grant"));
        assertFalse(failure.getMessage().contains("never-issued"));

        Throwable exchange = AuthException.unwrap(provider.exchangeCode(metadata, CLIENT_ID, "bad-code", URI.create("http://127.0.0.1:1/callback"), "verifier")
                .handle((tokens, error) -> error).get(10, TimeUnit.SECONDS));
        assertFalse(exchange.getMessage().contains("secret detail"));
        assertFalse(exchange.getMessage().contains("bad-code"));

        OidcProvider.Metadata insecure = new OidcProvider.Metadata(URI.create("http://tenant.example/authorize"),
                URI.create("http://tenant.example/oauth/token"), Optional.empty());
        Throwable refused = AuthException.unwrap(provider.refresh(insecure, CLIENT_ID, "refresh").handle((tokens, error) -> error).get(10, TimeUnit.SECONDS));
        assertEquals("A token is never posted over plain http to another machine", AuthException.Kind.NOT_AVAILABLE, ((AuthException) refused).kind());
    }
}
