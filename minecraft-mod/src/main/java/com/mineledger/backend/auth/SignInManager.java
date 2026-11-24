package com.mineledger.backend.auth;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import com.mineledger.backend.BackendException;
import com.mineledger.backend.auth.AccountApi.RemoteUser;
import com.mineledger.backend.auth.AuthException.Kind;
import com.mineledger.backend.auth.CallbackReceiver.Listening;
import com.mineledger.backend.auth.OidcProvider.Metadata;
import com.mineledger.backend.auth.OidcProvider.Tokens;
import com.mineledger.backend.auth.RefreshTokenStore.Stored;

/**
 * The single owner of "who is signed in?" for the running game.
 *
 * <p>Sign-in is OAuth 2.0 Authorization Code with PKCE (RFC 7636) for a native public client (RFC 8252):
 * the player authenticates in their own web browser, the browser redirects to a listener on this computer,
 * and the code is exchanged for tokens without any client secret. The game never sees a password. A
 * sign-in counts only once the MineLedger server has accepted the access token at
 * {@code GET /api/v1/users/me}; the user ID and role shown are the server's answer.
 *
 * <p>It knows nothing about practice, progress or XP. Offline practice never touches it, and nothing here
 * reads or writes local progress, so local XP cannot become server XP through it. The Minecraft profile
 * is never consulted: only the identity provider's token says who the player is.
 *
 * <p>Tokens: the access token lives in memory only. The refresh token is also handed to the
 * {@link RefreshTokenStore}. Neither is logged, put in an exception message, or exposed by a
 * {@code toString}.
 *
 * <p>Threading: call every method on the owner thread (Minecraft's client thread). All network work runs
 * elsewhere, and results are handed to {@code ownerThread} before any state changes. Each attempt carries
 * a ticket; a result whose ticket is no longer current is discarded.
 */
public final class SignInManager {
    public enum State {
        SIGNED_OUT,
        /** The browser is open (or opening) and the game is waiting for the player to finish there. */
        WAITING_FOR_BROWSER,
        /** The browser came back; the code is being exchanged and the server is confirming the account. */
        FINISHING,
        /** A sign-in remembered from an earlier launch is being renewed. */
        RESTORING,
        SIGNED_IN
    }

    /** {@code displayName} comes from the identity provider and is for display only. */
    public record Account(RemoteUser user, Optional<String> displayName) { }

    /** {@code openid} and {@code profile} for the display name, {@code offline_access} for a refresh token. */
    static final String SCOPE = "openid profile offline_access";
    static final long BROWSER_TIMEOUT_MILLIS = 5 * 60_000;
    /** An access token this close to expiry is renewed before it is used. */
    static final long REFRESH_BEFORE_EXPIRY_MILLIS = 60_000;
    /** Opening the app again sooner than this does not ask the server about the account again. */
    static final long VERIFY_AFTER_MILLIS = 15_000;
    static final String EXPIRED = "Your sign-in has expired. Please sign in again.";
    static final int MAX_PROVIDER_MESSAGE_LENGTH = 240;

    private record Prepared(AuthSettings settings, Metadata metadata) { }

    private final Supplier<? extends AccountApi> api;
    private final OidcProvider provider;
    private final CallbackReceiver receiver;
    private final Consumer<URI> browser;
    private final RefreshTokenStore store;
    private final Executor ownerThread;
    private final LongSupplier clockMillis;
    private final boolean serverIsSecure;
    private final Set<Runnable> listeners = new LinkedHashSet<>();
    long browserTimeoutMillis = BROWSER_TIMEOUT_MILLIS;

    private State state = State.SIGNED_OUT;
    private long ticket;
    private String problem;
    private String notice;
    private Listening listening;
    private URI browserUrl;
    private Prepared session;
    private Account account;
    private String accessToken;
    private long accessExpiresAtMillis;
    private String refreshToken;
    private CompletableFuture<String> refreshing;
    private long lastVerifiedMillis;

    /**
     * @param api            called only when a sign-in or restore actually happens, so Offline Practice
     *                       never creates an HTTP client through this class
     * @param browser        opens a URL in the player's system browser
     * @param serverIsSecure whether the MineLedger server address is https (or this computer); an access
     *                       token is never sent anywhere else
     */
    public SignInManager(Supplier<? extends AccountApi> api, OidcProvider provider, CallbackReceiver receiver,
            Consumer<URI> browser, RefreshTokenStore store, Executor ownerThread, LongSupplier clockMillis, boolean serverIsSecure) {
        this.api = api;
        this.provider = provider;
        this.receiver = receiver;
        this.browser = browser;
        this.store = store;
        this.ownerThread = ownerThread;
        this.clockMillis = clockMillis;
        this.serverIsSecure = serverIsSecure;
    }

    public State state() { return state; }
    /** Present exactly while {@link State#SIGNED_IN}. */
    public Optional<Account> account() { return Optional.ofNullable(account); }
    /** Why the last sign-in ended or failed, for the player. */
    public Optional<String> problem() { return Optional.ofNullable(problem); }
    /** Something that worked but deserves a mention, such as a sign-in that will not be remembered. */
    public Optional<String> notice() { return Optional.ofNullable(notice); }
    public boolean busy() { return state != State.SIGNED_OUT && state != State.SIGNED_IN; }
    /**
     * The sign-in page for the attempt that is waiting, so the player can open it by hand if the browser
     * did not appear. It holds no token and no secret, and is useless once the attempt ends.
     */
    public Optional<URI> browserUrl() { return state == State.WAITING_FOR_BROWSER ? Optional.ofNullable(browserUrl) : Optional.empty(); }
    /** False when the server address would expose a token; Sign In explains this instead of starting. */
    public boolean serverIsSecure() { return serverIsSecure; }

    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }

    // --- Sign in -------------------------------------------------------------------------------------

    /** Opens the player's browser to sign in. Ignored unless signed out. */
    public void signIn() {
        if (state != State.SIGNED_OUT) return;
        if (!serverIsSecure) {
            problem = "Sign-in needs a MineLedger server address that starts with https://, so your account cannot be intercepted.";
            changed();
            return;
        }
        long mine = begin(State.WAITING_FOR_BROWSER);
        prepare().whenCompleteAsync((prepared, failure) -> {
            if (mine != ticket) return;
            if (failure != null) signedOut(describe(failure));
            else openBrowser(mine, prepared);
        }, ownerThread);
        changed();
    }

    private void openBrowser(long mine, Prepared prepared) {
        String stateValue = Pkce.randomToken();
        String nonce = Pkce.randomToken();
        Pkce pkce = Pkce.fromVerifier(Pkce.randomToken());
        Listening current;
        try {
            current = receiver.start(stateValue);
            listening = current;
            browserUrl = authorizationUrl(prepared, current.redirectUri(), stateValue, nonce, pkce);
            browser.accept(browserUrl);
        } catch (RuntimeException exception) {
            stopListening();
            signedOut(exception instanceof AuthException ? exception.getMessage() : "Your web browser could not be opened.");
            return;
        }
        current.callback().copy().orTimeout(browserTimeoutMillis, TimeUnit.MILLISECONDS).whenCompleteAsync((parameters, failure) -> {
            if (mine != ticket) return;
            stopListening();
            if (failure != null) {
                signedOut(AuthException.unwrap(failure) instanceof TimeoutException
                        ? "Sign-in was not finished in the browser in time. Try again when you are ready." : describe(failure));
            } else if (!stateValue.equals(parameters.get("state"))) {
                signedOut("Sign-in failed: the answer does not belong to this sign-in attempt.");
            } else if (parameters.containsKey("error")) {
                signedOut(refusedInBrowser(parameters.get("error"), parameters.get("error_description")));
            } else if (parameters.get("code") == null || parameters.get("code").isBlank()) {
                signedOut("Sign-in failed: the browser returned without a sign-in code.");
            } else {
                state = State.FINISHING;
                changed();
                exchange(mine, prepared, parameters.get("code"), current.redirectUri(), pkce, nonce);
            }
        }, ownerThread);
    }

    private void exchange(long mine, Prepared prepared, String code, URI redirectUri, Pkce pkce, String nonce) {
        attempt(() -> provider.exchangeCode(prepared.metadata(), prepared.settings().clientId(), code, redirectUri, pkce.verifier()))
                .whenCompleteAsync((tokens, failure) -> {
                    if (mine != ticket) {
                        // Cancelled while the exchange was in flight: the session it created must not linger.
                        if (failure == null) tokens.refreshToken().ifPresent(token -> revokeQuietly(prepared, token));
                        return;
                    }
                    if (failure != null) {
                        signedOut(describe(failure));
                        return;
                    }
                    Optional<String> name;
                    try {
                        name = tokens.idToken().isPresent()
                                ? IdToken.displayName(tokens.idToken().get(), prepared.settings(), nonce, clockMillis.getAsLong())
                                : Optional.empty();
                    } catch (AuthException exception) {
                        tokens.refreshToken().ifPresent(token -> revokeQuietly(prepared, token));
                        signedOut(exception.getMessage());
                        return;
                    }
                    confirmWithServer(mine, prepared, tokens, tokens.refreshToken().orElse(null), name, false);
                }, ownerThread);
    }

    /** The last step of both paths: the MineLedger server, not the client, says who this is. */
    private void confirmWithServer(long mine, Prepared prepared, Tokens tokens, String newRefreshToken, Optional<String> name,
            boolean restoring) {
        long issuedAt = clockMillis.getAsLong();
        attempt(() -> api.get().me(tokens.accessToken())).whenCompleteAsync((user, failure) -> {
            if (mine != ticket) {
                if (!restoring && newRefreshToken != null) revokeQuietly(prepared, newRefreshToken);
                return;
            }
            if (failure != null) {
                // A restored session survives a server that is merely unreachable; a refused one does not.
                if (!restoring || refusedByServer(failure)) {
                    if (newRefreshToken != null) revokeQuietly(prepared, newRefreshToken);
                    store.clear();
                }
                signedOut(restoring && !refusedByServer(failure)
                        ? "Your earlier sign-in could not be confirmed: " + describe(failure) : describe(failure));
                return;
            }
            session = prepared;
            account = new Account(user, name);
            accessToken = tokens.accessToken();
            accessExpiresAtMillis = issuedAt + tokens.expiresInSeconds() * 1000;
            refreshToken = newRefreshToken;
            lastVerifiedMillis = clockMillis.getAsLong();
            state = State.SIGNED_IN;
            problem = null;
            remember();
            changed();
        }, ownerThread);
    }

    /** Stops a sign-in or restore in progress. Late answers are ignored. A stored session is kept. */
    public void cancel() {
        if (!busy()) return;
        ticket++;
        stopListening();
        state = State.SIGNED_OUT;
        problem = null;
        changed();
    }

    // --- Sign out ------------------------------------------------------------------------------------

    /**
     * Forgets the account on this computer at once, then asks the identity provider to revoke the refresh
     * token. The current access token cannot be revoked and simply expires; it is no longer held anywhere.
     */
    public void signOut() {
        if (busy()) {
            cancel();
            return;
        }
        if (state != State.SIGNED_IN) return;
        Prepared prepared = session;
        String token = refreshToken;
        ticket++;
        forget();
        store.clear();
        state = State.SIGNED_OUT;
        problem = null;
        notice = null;
        changed();
        if (token != null) revokeQuietly(prepared, token);
    }

    // --- Restore and upkeep --------------------------------------------------------------------------

    /**
     * The player opened MineLedger while the server is reachable. Renews a sign-in remembered from an
     * earlier launch, or, when already signed in, asks the server whether the account and role still hold.
     */
    public void appOpened() {
        if (state == State.SIGNED_IN) {
            if (clockMillis.getAsLong() - lastVerifiedMillis >= VERIFY_AFTER_MILLIS) {
                lastVerifiedMillis = clockMillis.getAsLong();
                verify(ticket, false);
            }
            return;
        }
        if (state != State.SIGNED_OUT || !serverIsSecure) return;
        Optional<Stored> stored = store.load();
        if (stored.isEmpty()) return;
        long mine = begin(State.RESTORING);
        changed();
        prepare().whenCompleteAsync((prepared, failure) -> {
            if (mine != ticket) return;
            if (failure != null) {
                signedOut("Your earlier sign-in could not be restored: " + describe(failure));
                return;
            }
            Stored remembered = stored.get();
            if (!remembered.issuer().equals(prepared.settings().issuer()) || !remembered.clientId().equals(prepared.settings().clientId())) {
                store.clear(); // It belongs to a different sign-in service and must never be sent to this one.
                signedOut(null);
                return;
            }
            attempt(() -> provider.refresh(prepared.metadata(), prepared.settings().clientId(), remembered.refreshToken()))
                    .whenCompleteAsync((tokens, refreshFailure) -> {
                        if (refreshFailure == null) keepRotatedToken(remembered, tokens);
                        if (mine != ticket) return;
                        if (refreshFailure != null) {
                            if (rejected(refreshFailure)) {
                                store.clear();
                                signedOut(EXPIRED);
                            } else {
                                signedOut("Your earlier sign-in could not be restored: " + describe(refreshFailure));
                            }
                            return;
                        }
                        confirmWithServer(mine, prepared, tokens, tokens.refreshToken().orElse(remembered.refreshToken()),
                                remembered.displayName(), true);
                    }, ownerThread);
        }, ownerThread);
    }

    /**
     * A provider that rotates refresh tokens has just made the stored one worthless, so the replacement is
     * written before anything else can fail. If the player started something else meanwhile, the store is
     * left alone.
     */
    private void keepRotatedToken(Stored remembered, Tokens tokens) {
        if (tokens.refreshToken().isEmpty()) return;
        Optional<Stored> current = store.load();
        if (current.isEmpty() || !current.get().refreshToken().equals(remembered.refreshToken())) return;
        try {
            store.save(new Stored(remembered.issuer(), remembered.clientId(), tokens.refreshToken().get(), remembered.displayName()));
        } catch (IOException | RuntimeException exception) {
            notice = "Your sign-in could not be saved, so you will need to sign in again next time.";
        }
    }

    private void verify(long mine, boolean retried) {
        accessToken().thenCompose(token -> attempt(() -> api.get().me(token))).whenCompleteAsync((user, failure) -> {
            if (mine != ticket || state != State.SIGNED_IN) return;
            if (failure == null) {
                if (!user.equals(account.user())) {
                    account = new Account(user, account.displayName());
                    changed();
                }
                return;
            }
            int status = serverStatus(failure);
            if (status == 401 && !retried && refreshToken != null) {
                accessExpiresAtMillis = 0; // The server no longer accepts this token: get a new one and ask once more.
                verify(mine, true);
            } else if (status == 401) {
                expire(EXPIRED);
            } else if (status == 403) {
                expire(describe(failure));
            }
            // Anything else means the server could not be asked. The sign-in stands until it can be.
        }, ownerThread);
    }

    /**
     * An access token that is valid now, renewing it first if it is about to expire. For authenticated
     * requests to the MineLedger server only. Completes on the owner thread.
     */
    public CompletableFuture<String> accessToken() {
        if (state != State.SIGNED_IN) return CompletableFuture.failedFuture(new AuthException(Kind.REJECTED, "You are not signed in."));
        if (clockMillis.getAsLong() < accessExpiresAtMillis - REFRESH_BEFORE_EXPIRY_MILLIS) {
            return CompletableFuture.completedFuture(accessToken);
        }
        if (refreshing != null) return refreshing;
        if (refreshToken == null) {
            expire(EXPIRED);
            return CompletableFuture.failedFuture(new AuthException(Kind.REJECTED, EXPIRED));
        }
        long mine = ticket;
        Prepared prepared = session;
        String previous = refreshToken;
        CompletableFuture<String> result = new CompletableFuture<>();
        refreshing = result;
        long requestedAt = clockMillis.getAsLong();
        attempt(() -> provider.refresh(prepared.metadata(), prepared.settings().clientId(), previous)).whenCompleteAsync((tokens, failure) -> {
            if (refreshing == result) refreshing = null;
            if (mine != ticket) {
                // Signed out while renewing: the new session must not outlive that decision.
                if (failure == null) tokens.refreshToken().ifPresent(token -> revokeQuietly(prepared, token));
                result.completeExceptionally(new AuthException(Kind.REJECTED, "You are not signed in."));
                return;
            }
            if (failure != null) {
                if (rejected(failure)) expire(EXPIRED);
                result.completeExceptionally(AuthException.unwrap(failure));
                return;
            }
            accessToken = tokens.accessToken();
            accessExpiresAtMillis = requestedAt + tokens.expiresInSeconds() * 1000;
            refreshToken = tokens.refreshToken().orElse(previous);
            remember();
            result.complete(accessToken);
        }, ownerThread);
        return result;
    }

    /** Game shutdown: stop listening and ignore anything still in flight. The stored session is kept. */
    public void close() {
        ticket++;
        stopListening();
    }

    // --- Internals -----------------------------------------------------------------------------------

    private long begin(State next) {
        state = next;
        problem = null;
        notice = null;
        return ++ticket;
    }

    private void signedOut(String message) {
        stopListening();
        forget();
        state = State.SIGNED_OUT;
        problem = message;
        changed();
    }

    /** The session ended without the player asking: forget it here and at the provider. */
    private void expire(String message) {
        Prepared prepared = session;
        String token = refreshToken;
        ticket++;
        store.clear();
        signedOut(message);
        if (token != null) revokeQuietly(prepared, token);
    }

    private void forget() {
        session = null;
        account = null;
        accessToken = null;
        accessExpiresAtMillis = 0;
        refreshToken = null;
        refreshing = null;
    }

    private void remember() {
        if (refreshToken == null) {
            store.clear();
            notice = "This sign-in lasts until you close the game.";
            return;
        }
        try {
            store.save(new Stored(session.settings().issuer(), session.settings().clientId(), refreshToken, account.displayName()));
        } catch (IOException | RuntimeException exception) {
            notice = "Your sign-in could not be saved, so you will need to sign in again next time.";
        }
    }

    private void stopListening() {
        if (listening != null) listening.close();
        listening = null;
        browserUrl = null;
    }

    private CompletableFuture<Prepared> prepare() {
        return attempt(() -> api.get().authSettings()).thenCompose(found -> {
            AuthSettings settings = found.orElseThrow(() -> new AuthException(Kind.NOT_AVAILABLE,
                    "Sign-in is not set up on this MineLedger server."));
            return provider.discover(settings.issuer()).thenApply(metadata -> new Prepared(settings, metadata));
        });
    }

    /** A call that throws instead of returning a future is treated the same as a failed future. */
    private static <T> CompletableFuture<T> attempt(Supplier<CompletableFuture<T>> call) {
        try {
            return call.get();
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private void revokeQuietly(Prepared prepared, String token) {
        if (prepared == null) return;
        try {
            provider.revoke(prepared.metadata(), prepared.settings().clientId(), token);
        } catch (RuntimeException ignored) {
            // Revocation is best effort; the local session is already gone.
        }
    }

    private static URI authorizationUrl(Prepared prepared, URI redirectUri, String stateValue, String nonce, Pkce pkce) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", prepared.settings().clientId());
        query.put("redirect_uri", redirectUri.toString());
        query.put("scope", SCOPE);
        query.put("audience", prepared.settings().audience());
        query.put("state", stateValue);
        query.put("nonce", nonce);
        query.put("code_challenge", pkce.challenge());
        query.put("code_challenge_method", "S256");
        // Always ask for credentials: on a shared computer, the browser's remembered session must not sign
        // the next player in as the previous one. Staying signed in is the refresh token's job.
        query.put("prompt", "login");
        String endpoint = prepared.metadata().authorizationEndpoint().toString();
        return URI.create(endpoint + (endpoint.contains("?") ? "&" : "?") + query.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("&")));
    }

    /**
     * The provider's own explanation is shown, because it is usually the only way to tell which setting is
     * wrong. It arrives through the browser, so it is treated as untrusted text: one plain capped line.
     */
    private static String refusedInBrowser(String error, String description) {
        String code = error != null && error.matches("[a-z_]{1,40}") ? error : "unknown";
        String explanation = description == null ? "" : IdToken.printable(description, MAX_PROVIDER_MESSAGE_LENGTH);
        String said = explanation.isEmpty() ? "" : " The sign-in service said: " + explanation;
        if ("access_denied".equals(error)) return "Sign-in was cancelled or refused in the browser." + said;
        return "The sign-in service reported a problem (" + code + ")." + said;
    }

    private static boolean rejected(Throwable failure) {
        return AuthException.unwrap(failure) instanceof AuthException known && known.kind() == Kind.REJECTED;
    }

    /** The HTTP status the MineLedger server answered with, or 0 if it did not answer. */
    private static int serverStatus(Throwable failure) {
        return AuthException.unwrap(failure) instanceof BackendException known && known.kind() == BackendException.Kind.HTTP_STATUS
                ? known.status() : 0;
    }

    private static boolean refusedByServer(Throwable failure) {
        int status = serverStatus(failure);
        return status == 401 || status == 403;
    }

    private static String describe(Throwable failure) {
        Throwable cause = AuthException.unwrap(failure);
        if (cause instanceof AuthException known) return known.getMessage();
        if (cause instanceof BackendException known) {
            return switch (serverStatus(known)) {
                case 401 -> "The MineLedger server did not accept this sign-in. It may be set up for a different sign-in service.";
                case 403 -> "The MineLedger server refused this account. Ask your teacher or administrator.";
                case 404 -> "This MineLedger server does not support sign-in.";
                case 503 -> "Sign-in is not available on the MineLedger server right now.";
                default -> known.getMessage();
            };
        }
        return "Sign-in failed unexpectedly (" + cause.getClass().getSimpleName() + ").";
    }

    private void changed() {
        for (Runnable listener : List.copyOf(listeners)) listener.run();
    }
}
