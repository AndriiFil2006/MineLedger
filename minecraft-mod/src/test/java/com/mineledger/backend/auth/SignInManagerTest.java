package com.mineledger.backend.auth;

import static org.junit.Assert.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.Test;
import com.mineledger.backend.auth.AccountApi.RemoteUser;
import com.mineledger.backend.auth.AccountApi.Role;
import com.mineledger.backend.auth.AuthException.Kind;
import com.mineledger.backend.auth.OidcProvider.Tokens;
import com.mineledger.backend.auth.RefreshTokenStore.Stored;
import com.mineledger.backend.auth.SignInManager.State;

/** The sign-in state machine, with every network answer and every client-thread turn under the test's control. */
public class SignInManagerTest {
    private static final RemoteUser STUDENT = new RemoteUser(FakeAuth.USER_ID, Role.STUDENT);
    private static final RemoteUser TEACHER = new RemoteUser(FakeAuth.USER_ID, Role.TEACHER);

    private final FakeAuth.Api api = new FakeAuth.Api();
    private final FakeAuth.Provider provider = new FakeAuth.Provider();
    private final FakeAuth.Receiver receiver = new FakeAuth.Receiver();
    private final FakeAuth.Store store = new FakeAuth.Store();
    private final FakeAuth.OwnerThread owner = new FakeAuth.OwnerThread();
    private final List<URI> browser = new ArrayList<>();
    private long now = 1_800_000_000_000L;
    private int changes;
    private int apiCreated;
    private SignInManager manager = manager(true);

    private SignInManager manager(boolean serverIsSecure) {
        SignInManager created = new SignInManager(() -> {
            apiCreated++;
            return api;
        }, provider, receiver, browser::add, store, owner, () -> now, serverIsSecure);
        created.addListener(() -> changes++);
        return created;
    }

    private Map<String, String> url() { return FakeAuth.query(browser.getLast()); }

    /** Sign In pressed, settings and discovery answered: the browser is open and the game is waiting. */
    private void toBrowser() {
        manager.signIn();
        prepare();
    }

    private void prepare() {
        api.settings.answer(Optional.of(FakeAuth.SETTINGS));
        provider.discoveries.answer(FakeAuth.METADATA);
        owner.drain();
    }

    private void redirectBack() {
        receiver.redirect(Map.of("code", "the-code", "state", receiver.state()));
        owner.drain();
    }

    private Tokens tokens(String access, String refresh) { return tokens(access, refresh, "Alex Kim"); }

    private Tokens tokens(String access, String refresh, String name) {
        String idToken = browser.isEmpty() ? null : FakeAuth.idToken(FakeAuth.ISSUER, FakeAuth.CLIENT_ID, url().get("nonce"), now / 1000 + 600, name);
        return new Tokens(access, 3600, Optional.ofNullable(refresh), Optional.ofNullable(idToken));
    }

    private void signInFully() {
        toBrowser();
        redirectBack();
        provider.exchanges.answer(tokens("access-1", "refresh-1"));
        owner.drain();
        api.me.answer(STUDENT);
        owner.drain();
        assertEquals(State.SIGNED_IN, manager.state());
    }

    private void assertSignedOut(String problemContains) {
        assertEquals(State.SIGNED_OUT, manager.state());
        assertTrue(manager.account().isEmpty());
        if (problemContains == null) assertEquals(Optional.empty(), manager.problem());
        else assertTrue("Problem was: " + manager.problem(), manager.problem().orElse("").contains(problemContains));
    }

    private static <T> Throwable failureOf(CompletableFuture<T> future) {
        assertTrue(future.isCompletedExceptionally());
        return AuthException.unwrap(future.handle((value, failure) -> failure).join());
    }

    // --- Successful sign-in --------------------------------------------------------------------------

    @Test
    public void signInOpensTheSystemBrowserWithAPkceRequestAndNoSecret() {
        manager.signIn();
        assertEquals(State.WAITING_FOR_BROWSER, manager.state());
        assertTrue("Nothing opens before the server's settings are known", browser.isEmpty());
        prepare();

        assertEquals(List.of(FakeAuth.ISSUER), provider.issuersDiscovered);
        assertEquals(1, browser.size());
        assertEquals("The same link can be opened by hand", Optional.of(browser.getLast()), manager.browserUrl());
        assertTrue(browser.getLast().toString().startsWith(FakeAuth.ISSUER + "authorize?"));
        Map<String, String> query = url();
        assertEquals("code", query.get("response_type"));
        assertEquals(FakeAuth.CLIENT_ID, query.get("client_id"));
        assertEquals(FakeAuth.AUDIENCE, query.get("audience"));
        assertEquals(FakeAuth.Receiver.REDIRECT.toString(), query.get("redirect_uri"));
        assertEquals("openid profile offline_access", query.get("scope"));
        assertEquals("S256", query.get("code_challenge_method"));
        assertEquals("login", query.get("prompt"));
        assertEquals(receiver.state(), query.get("state"));
        assertEquals(43, query.get("code_challenge").length());
        assertEquals(43, query.get("state").length());
        assertEquals(43, query.get("nonce").length());
        assertEquals("Every value is distinct", 3, Stream.of(query.get("state"), query.get("nonce"), query.get("code_challenge")).distinct().count());
        assertFalse(browser.getLast().toString().contains("secret"));
        assertFalse("The verifier itself never leaves the game through the browser", query.containsKey("code_verifier"));
    }

    @Test
    public void aCompletedSignInIsConfirmedByTheServerAndShowsItsUserAndRole() {
        toBrowser();
        redirectBack();
        assertEquals(State.FINISHING, manager.state());
        assertEquals(1, receiver.closed);
        assertEquals(Optional.empty(), manager.browserUrl());

        FakeAuth.Provider.Exchange exchange = provider.exchanged.getFirst();
        assertEquals(FakeAuth.CLIENT_ID, exchange.clientId());
        assertEquals("the-code", exchange.code());
        assertEquals(FakeAuth.Receiver.REDIRECT, exchange.redirectUri());
        assertEquals("The verifier sent to the token endpoint matches the challenge shown to the browser",
                url().get("code_challenge"), Pkce.fromVerifier(exchange.codeVerifier()).challenge());

        provider.exchanges.answer(tokens("access-1", "refresh-1"));
        owner.drain();
        assertEquals("Tokens alone do not sign anyone in", State.FINISHING, manager.state());
        assertEquals(List.of("access-1"), api.tokensSeen);

        api.me.answer(TEACHER);
        owner.drain();
        assertEquals(State.SIGNED_IN, manager.state());
        assertEquals(new SignInManager.Account(TEACHER, Optional.of("Alex Kim")), manager.account().orElseThrow());
        assertEquals(Optional.empty(), manager.problem());
        assertEquals(new Stored(FakeAuth.ISSUER, FakeAuth.CLIENT_ID, "refresh-1", Optional.of("Alex Kim")), store.stored);
        assertEquals("access-1", manager.accessToken().join());
        assertTrue(provider.revoked.isEmpty());
        assertTrue(changes >= 3);
    }

    @Test
    public void everyStateChangeHappensOnTheOwnerThread() {
        manager.signIn();
        api.settings.answer(Optional.of(FakeAuth.SETTINGS));
        provider.discoveries.answer(FakeAuth.METADATA);
        assertTrue("The answer waits for the client thread", browser.isEmpty());
        owner.drain();
        receiver.redirect(Map.of("code", "the-code", "state", receiver.state()));
        assertEquals(State.WAITING_FOR_BROWSER, manager.state());
        owner.drain();
        provider.exchanges.answer(tokens("access-1", "refresh-1"));
        assertTrue(api.tokensSeen.isEmpty());
        owner.drain();
        api.me.answer(STUDENT);
        assertEquals(State.FINISHING, manager.state());
        owner.drain();
        assertEquals(State.SIGNED_IN, manager.state());
    }

    @Test
    public void signInWithoutARefreshTokenLastsForThisLaunchOnly() {
        toBrowser();
        redirectBack();
        provider.exchanges.answer(tokens("access-1", null));
        owner.drain();
        api.me.answer(STUDENT);
        owner.drain();
        assertEquals(State.SIGNED_IN, manager.state());
        assertNull(store.stored);
        assertTrue(manager.notice().orElse("").contains("until you close the game"));
    }

    @Test
    public void aSignInThatCannotBeSavedStillWorksAndSaysSo() {
        store.failToSave = true;
        signInFully();
        assertNull(store.stored);
        assertTrue(manager.notice().orElse("").contains("could not be saved"));
    }

    @Test
    public void anAccountWithoutANameIsStillSignedIn() {
        toBrowser();
        redirectBack();
        provider.exchanges.answer(new Tokens("access-1", 3600, Optional.of("refresh-1"), Optional.empty()));
        owner.drain();
        api.me.answer(STUDENT);
        owner.drain();
        assertEquals(Optional.empty(), manager.account().orElseThrow().displayName());
    }

    @Test
    public void signInIsIgnoredWhileOneIsRunningOrAlreadySignedIn() {
        toBrowser();
        manager.signIn();
        assertEquals(1, api.settings.count);
        redirectBack();
        manager.signIn();
        provider.exchanges.answer(tokens("access-1", "refresh-1"));
        owner.drain();
        api.me.answer(STUDENT);
        owner.drain();
        manager.signIn();
        assertEquals(1, api.settings.count);
        assertEquals(1, browser.size());
    }

    // --- Refused and failed sign-ins -----------------------------------------------------------------

    @Test
    public void aServerThatRejectsTheTokenDoesNotSignAnyoneIn() {
        toBrowser();
        redirectBack();
        provider.exchanges.answer(tokens("access-1", "refresh-1"));
        owner.drain();
        api.me.fail(FakeAuth.http(401));
        owner.drain();
        assertSignedOut("did not accept this sign-in");
        assertNull(store.stored);
        assertEquals("The session the provider created is not left behind", List.of("refresh-1"), provider.revoked);
        assertTrue(failureOf(manager.accessToken()) instanceof AuthException);
    }

    @Test
    public void aDisabledAccountIsNotSignedIn() {
        toBrowser();
        redirectBack();
        provider.exchanges.answer(tokens("access-1", "refresh-1"));
        owner.drain();
        api.me.fail(FakeAuth.http(403));
        owner.drain();
        assertSignedOut("refused this account");
        assertNull(store.stored);
        assertEquals(List.of("refresh-1"), provider.revoked);
    }

    @Test
    public void aServerThatDisconnectsWhileConfirmingDoesNotSignAnyoneIn() {
        toBrowser();
        redirectBack();
        provider.exchanges.answer(tokens("access-1", "refresh-1"));
        owner.drain();
        api.me.fail(FakeAuth.unreachable());
        owner.drain();
        assertSignedOut("Cannot reach the backend");
        assertNull(store.stored);
        assertEquals(List.of("refresh-1"), provider.revoked);
    }

    @Test
    public void aServerThatIsUnreachableBeforeSignInStartsOpensNoBrowser() {
        manager.signIn();
        api.settings.fail(FakeAuth.unreachable());
        owner.drain();
        assertSignedOut("Cannot reach the backend");
        assertTrue(browser.isEmpty());
        assertTrue(receiver.states.isEmpty());
    }

    @Test
    public void aServerWithoutSignInSaysSo() {
        manager.signIn();
        api.settings.answer(Optional.empty());
        owner.drain();
        assertSignedOut("not set up on this MineLedger server");

        manager.signIn();
        api.settings.fail(FakeAuth.http(404));
        owner.drain();
        assertSignedOut("does not support sign-in");
        assertTrue(browser.isEmpty());
        assertEquals(0, provider.discoveries.count);
    }

    @Test
    public void anUnreachableIdentityProviderOpensNoBrowser() {
        manager.signIn();
        api.settings.answer(Optional.of(FakeAuth.SETTINGS));
        provider.discoveries.fail(AuthException.network(new java.net.ConnectException("refused")));
        owner.drain();
        assertSignedOut("Cannot reach the sign-in service");
        assertTrue(browser.isEmpty());
    }

    @Test
    public void signInRefusesToSendATokenToAnInsecureServer() {
        manager = manager(false);
        manager.signIn();
        assertSignedOut("https://");
        assertEquals("Nothing was contacted", 0, apiCreated);
        assertTrue(browser.isEmpty());
        manager.appOpened();
        assertEquals(0, apiCreated);
    }

    @Test
    public void refusingInTheBrowserEndsTheAttempt() {
        toBrowser();
        receiver.redirect(Map.of("error", "access_denied", "error_description", "User did not authorize the request", "state", receiver.state()));
        owner.drain();
        assertSignedOut("cancelled or refused in the browser. The sign-in service said: User did not authorize the request");
        assertTrue(provider.exchanged.isEmpty());
        assertEquals(1, receiver.closed);
    }

    @Test
    public void anUnknownBrowserErrorIsReportedByItsCodeOnly() {
        toBrowser();
        receiver.redirect(Map.of("error", "temporarily_unavailable", "state", receiver.state()));
        owner.drain();
        assertSignedOut("(temporarily_unavailable)");

        toBrowser();
        receiver.redirect(Map.of("error", "<script>alert(1)</script>", "state", receiver.state()));
        owner.drain();
        assertSignedOut("(unknown)");
    }

    @Test
    public void theProvidersExplanationIsShownSoAMisconfigurationCanBeFixed() {
        toBrowser();
        receiver.redirect(Map.of("error", "invalid_request", "state", receiver.state(), "error_description",
                "Client \"abc123\" is not authorized to access resource server \"https://api.mineledger.dev\"."));
        owner.drain();
        assertSignedOut("The sign-in service reported a problem (invalid_request). The sign-in service said: "
                + "Client \"abc123\" is not authorized to access resource server \"https://api.mineledger.dev\".");
    }

    @Test
    public void theProvidersExplanationIsShownAsOnePlainCappedLine() {
        toBrowser();
        receiver.redirect(Map.of("error", "server_error", "state", receiver.state(), "error_description",
                "line one\nline two \u00a7cred \u202Ereversed " + "x".repeat(500)));
        owner.drain();
        String problem = manager.problem().orElseThrow();
        assertTrue(problem, problem.startsWith("The sign-in service reported a problem (server_error). The sign-in service said: line oneline two cred reversed xxx"));
        assertFalse(problem.contains("\n"));
        assertFalse("No Minecraft formatting codes", problem.contains("\u00a7"));
        assertFalse("No text-direction overrides", problem.contains("\u202E"));
        assertTrue(problem.endsWith("..."));
        assertTrue(problem.length() < 340);

        toBrowser();
        receiver.redirect(Map.of("error", "server_error", "error_description", " \n ", "state", receiver.state()));
        owner.drain();
        assertEquals(Optional.of("The sign-in service reported a problem (server_error)."), manager.problem());
    }

    @Test
    public void aRedirectForAnotherAttemptIsRefused() {
        toBrowser();
        receiver.redirect(Map.of("code", "injected-code", "state", "not-the-state-this-attempt-made"));
        owner.drain();
        assertSignedOut("does not belong to this sign-in attempt");
        assertTrue("An injected code is never exchanged", provider.exchanged.isEmpty());

        toBrowser();
        receiver.redirect(Map.of("code", "injected-code"));
        owner.drain();
        assertSignedOut("does not belong to this sign-in attempt");

        toBrowser();
        receiver.redirect(Map.of("state", receiver.state()));
        owner.drain();
        assertSignedOut("without a sign-in code");
        assertTrue(provider.exchanged.isEmpty());
    }

    @Test
    public void aCodeTheProviderRejectsDoesNotSignAnyoneIn() {
        toBrowser();
        redirectBack();
        provider.exchanges.fail(new AuthException(Kind.REJECTED, "The sign-in service refused the request (invalid_grant)."));
        owner.drain();
        assertSignedOut("invalid_grant");
        assertTrue(api.tokensSeen.isEmpty());
    }

    @Test
    public void anIdentityForAnotherAttemptClientOrIssuerIsRefused() {
        String[][] wrong = {
            {FakeAuth.ISSUER, FakeAuth.CLIENT_ID, "a-different-nonce", "does not belong to this sign-in attempt"},
            {FakeAuth.ISSUER, "another-client", null, "different application"},
            {"https://evil.example/", FakeAuth.CLIENT_ID, null, "different sign-in service"},
        };
        for (String[] attempt : wrong) {
            toBrowser();
            redirectBack();
            String nonce = attempt[2] == null ? url().get("nonce") : attempt[2];
            String idToken = FakeAuth.idToken(attempt[0], attempt[1], nonce, now / 1000 + 600, "Mallory");
            provider.exchanges.answer(new Tokens("access-x", 3600, Optional.of("refresh-x"), Optional.of(idToken)));
            owner.drain();
            assertSignedOut(attempt[3]);
        }
        assertTrue("The server is never asked about a refused identity", api.tokensSeen.isEmpty());
        assertEquals(List.of("refresh-x", "refresh-x", "refresh-x"), provider.revoked);
        assertNull(store.stored);
    }

    @Test
    public void aBusyCallbackPortIsExplained() {
        receiver.failToStart = new AuthException(Kind.NOT_AVAILABLE, "Sign-in could not start because the ports it needs are in use.");
        toBrowser();
        assertSignedOut("ports it needs are in use");
        assertTrue(browser.isEmpty());
    }

    @Test
    public void aBrowserThatCannotBeOpenedEndsTheAttemptAndStopsListening() {
        manager = new SignInManager(() -> api, provider, receiver, url -> {
            throw new IllegalStateException("no browser");
        }, store, owner, () -> now, true);
        toBrowser();
        assertSignedOut("web browser could not be opened");
        assertEquals(1, receiver.closed);
    }

    @Test
    public void anUnfinishedSignInTimesOut() throws Exception {
        manager.browserTimeoutMillis = 50;
        toBrowser();
        for (int i = 0; i < 500 && !owner.hasWork(); i++) Thread.sleep(10);
        owner.drain();
        assertSignedOut("not finished in the browser in time");
        assertEquals(1, receiver.closed);
        assertTrue(provider.exchanged.isEmpty());
    }

    // --- Cancellation and stale answers --------------------------------------------------------------

    @Test
    public void cancellingWhileTheBrowserIsOpenStopsListeningAndIgnoresALateRedirect() {
        toBrowser();
        var abandoned = receiver.callbacks.getLast();
        String state = receiver.state();
        manager.cancel();
        assertSignedOut(null);
        assertEquals(1, receiver.closed);
        assertEquals("The link dies with the attempt", Optional.empty(), manager.browserUrl());

        abandoned.complete(Map.of("code", "late-code", "state", state));
        owner.drain();
        assertSignedOut(null);
        assertTrue(provider.exchanged.isEmpty());
        assertTrue(api.tokensSeen.isEmpty());
    }

    @Test
    public void cancellingBeforeTheBrowserOpensNeverOpensIt() {
        manager.signIn();
        manager.cancel();
        prepare();
        assertSignedOut(null);
        assertTrue(browser.isEmpty());
        assertTrue(receiver.states.isEmpty());
    }

    @Test
    public void cancellingDuringTheExchangeDiscardsAndRevokesTheSessionItCreated() {
        toBrowser();
        redirectBack();
        manager.cancel();
        provider.exchanges.answer(tokens("access-1", "refresh-1"));
        owner.drain();
        assertSignedOut(null);
        assertTrue("A cancelled sign-in never reaches the server", api.tokensSeen.isEmpty());
        assertEquals(List.of("refresh-1"), provider.revoked);
        assertNull(store.stored);
    }

    @Test
    public void cancellingWhileTheServerConfirmsDiscardsTheLateAnswer() {
        toBrowser();
        redirectBack();
        provider.exchanges.answer(tokens("access-1", "refresh-1"));
        owner.drain();
        manager.cancel();
        api.me.answer(TEACHER);
        owner.drain();
        assertSignedOut(null);
        assertEquals(List.of("refresh-1"), provider.revoked);
        assertNull(store.stored);
    }

    @Test
    public void answersFromAnAbandonedAttemptCannotCompleteANewOne() {
        toBrowser();
        var firstCallback = receiver.callbacks.getLast();
        String firstState = receiver.state();
        manager.cancel();

        toBrowser();
        assertEquals(State.WAITING_FOR_BROWSER, manager.state());
        assertNotEquals("Each attempt has its own state", firstState, receiver.state());
        firstCallback.complete(Map.of("code", "old-code", "state", firstState));
        owner.drain();
        assertEquals(State.WAITING_FOR_BROWSER, manager.state());
        assertTrue(provider.exchanged.isEmpty());

        redirectBack();
        assertEquals("the-code", provider.exchanged.getLast().code());
        assertEquals(1, provider.exchanged.size());
    }

    @Test
    public void aStaleExchangeFromTheFirstAttemptDoesNotSignInTheSecond() {
        toBrowser();
        redirectBack();
        manager.cancel();
        toBrowser();
        provider.exchanges.answer(tokens("stale-access", "stale-refresh"));
        owner.drain();
        assertEquals(State.WAITING_FOR_BROWSER, manager.state());
        assertTrue(api.tokensSeen.isEmpty());
        assertEquals(List.of("stale-refresh"), provider.revoked);
    }

    @Test
    public void closingTheGameStopsListeningAndIgnoresLateAnswers() {
        toBrowser();
        manager.close();
        assertEquals(1, receiver.closed);
        receiver.redirect(Map.of("code", "the-code", "state", receiver.state()));
        owner.drain();
        assertTrue(provider.exchanged.isEmpty());
    }

    // --- Sign out ------------------------------------------------------------------------------------

    @Test
    public void signOutForgetsTheAccountAtOnceAndRevokesTheRefreshToken() {
        signInFully();
        manager.signOut();
        assertSignedOut(null);
        assertNull(store.stored);
        assertEquals(List.of("refresh-1"), provider.revoked);
        assertEquals("You are not signed in.", failureOf(manager.accessToken()).getMessage());
        manager.appOpened();
        assertEquals("Signing out leaves nothing to restore", State.SIGNED_OUT, manager.state());
        assertEquals(1, api.settings.count);
    }

    @Test
    public void signOutWhileSigningInIsACancel() {
        toBrowser();
        manager.signOut();
        assertSignedOut(null);
        assertEquals(1, receiver.closed);
        assertTrue(provider.revoked.isEmpty());
    }

    @Test
    public void signOutWhenSignedOutDoesNothing() {
        manager.signOut();
        assertEquals(0, changes);
        assertEquals(0, apiCreated);
    }

    @Test
    public void anAccountCheckThatAnswersAfterSignOutIsIgnored() {
        signInFully();
        now += SignInManager.VERIFY_AFTER_MILLIS;
        manager.appOpened();
        manager.signOut();
        api.me.answer(TEACHER);
        owner.drain();
        assertSignedOut(null);
    }

    @Test
    public void signingInAgainAfterSignOutIsAFreshAttempt() {
        signInFully();
        manager.signOut();
        toBrowser();
        redirectBack();
        provider.exchanges.answer(tokens("access-2", "refresh-2"));
        owner.drain();
        api.me.answer(TEACHER);
        owner.drain();
        assertEquals(TEACHER, manager.account().orElseThrow().user());
        assertEquals("refresh-2", store.stored.refreshToken());
        assertEquals("login", url().get("prompt"));
    }

    // --- Expiry and refresh --------------------------------------------------------------------------

    @Test
    public void aFreshAccessTokenIsUsedWithoutAskingTheProvider() {
        signInFully();
        now += 3600_000 - SignInManager.REFRESH_BEFORE_EXPIRY_MILLIS - 1;
        assertEquals("access-1", manager.accessToken().join());
        assertEquals(0, provider.refreshes.count);
    }

    @Test
    public void anExpiringAccessTokenIsRenewedOnceAndTheRotatedRefreshTokenIsKept() {
        signInFully();
        now += 3600_000 - SignInManager.REFRESH_BEFORE_EXPIRY_MILLIS;
        CompletableFuture<String> first = manager.accessToken();
        CompletableFuture<String> second = manager.accessToken();
        assertFalse(first.isDone());
        assertEquals("Two callers share one refresh", 1, provider.refreshes.count);
        assertEquals(List.of("refresh-1"), provider.refreshTokensUsed);

        provider.refreshes.answer(new Tokens("access-2", 3600, Optional.of("refresh-2"), Optional.empty()));
        assertFalse("The result is applied on the client thread", first.isDone());
        owner.drain();
        assertEquals("access-2", first.join());
        assertEquals("access-2", second.join());
        assertEquals("refresh-2", store.stored.refreshToken());
        assertEquals(State.SIGNED_IN, manager.state());
        assertEquals(Optional.of("Alex Kim"), manager.account().orElseThrow().displayName());

        assertEquals("access-2", manager.accessToken().join());
        assertEquals(1, provider.refreshes.count);
        now += 3600_000;
        manager.accessToken();
        assertEquals("The rotated token is the one used next", List.of("refresh-1", "refresh-2"), provider.refreshTokensUsed);
    }

    @Test
    public void aProviderThatDoesNotRotateKeepsTheSameRefreshToken() {
        signInFully();
        now += 3600_000;
        CompletableFuture<String> token = manager.accessToken();
        provider.refreshes.answer(new Tokens("access-2", 3600, Optional.empty(), Optional.empty()));
        owner.drain();
        assertEquals("access-2", token.join());
        assertEquals("refresh-1", store.stored.refreshToken());
    }

    @Test
    public void aRejectedRefreshEndsTheSession() {
        signInFully();
        now += 3600_000;
        CompletableFuture<String> token = manager.accessToken();
        provider.refreshes.fail(new AuthException(Kind.REJECTED, "The sign-in service refused the request (invalid_grant)."));
        owner.drain();
        assertSignedOut("expired");
        assertNull(store.stored);
        assertTrue(failureOf(token) instanceof AuthException);
    }

    @Test
    public void aRefreshThatCannotReachTheProviderKeepsTheSession() {
        signInFully();
        now += 3600_000;
        CompletableFuture<String> token = manager.accessToken();
        provider.refreshes.fail(AuthException.network(new TimeoutException()));
        owner.drain();
        assertEquals(State.SIGNED_IN, manager.state());
        assertEquals(Kind.NETWORK, ((AuthException) failureOf(token)).kind());
        assertEquals("refresh-1", store.stored.refreshToken());

        manager.accessToken();
        assertEquals("The next request tries again", 2, provider.refreshes.count);
    }

    @Test
    public void anExpiredSessionWithoutARefreshTokenEnds() {
        toBrowser();
        redirectBack();
        provider.exchanges.answer(tokens("access-1", null));
        owner.drain();
        api.me.answer(STUDENT);
        owner.drain();
        now += 3600_000;
        assertTrue(failureOf(manager.accessToken()) instanceof AuthException);
        assertSignedOut("expired");
        assertEquals(0, provider.refreshes.count);
    }

    @Test
    public void signingOutDuringARefreshDiscardsAndRevokesTheNewSession() {
        signInFully();
        now += 3600_000;
        CompletableFuture<String> token = manager.accessToken();
        manager.signOut();
        provider.refreshes.answer(new Tokens("access-2", 3600, Optional.of("refresh-2"), Optional.empty()));
        owner.drain();
        assertSignedOut(null);
        assertTrue(failureOf(token) instanceof AuthException);
        assertEquals(List.of("refresh-1", "refresh-2"), provider.revoked);
        assertNull(store.stored);
    }

    // --- Remembered sign-in --------------------------------------------------------------------------

    private void remember(String refreshToken) {
        store.stored = new Stored(FakeAuth.ISSUER, FakeAuth.CLIENT_ID, refreshToken, Optional.of("Alex Kim"));
    }

    @Test
    public void aRememberedSignInIsRenewedWithoutABrowser() {
        remember("refresh-1");
        manager.appOpened();
        assertEquals(State.RESTORING, manager.state());
        prepare();
        assertEquals(List.of("refresh-1"), provider.refreshTokensUsed);
        provider.refreshes.answer(new Tokens("access-2", 3600, Optional.of("refresh-2"), Optional.empty()));
        owner.drain();
        assertEquals("The rotated token is saved before anything else can fail", "refresh-2", store.stored.refreshToken());
        assertEquals(State.RESTORING, manager.state());
        assertEquals(List.of("access-2"), api.tokensSeen);

        api.me.answer(TEACHER);
        owner.drain();
        assertEquals(State.SIGNED_IN, manager.state());
        assertEquals(new SignInManager.Account(TEACHER, Optional.of("Alex Kim")), manager.account().orElseThrow());
        assertTrue(browser.isEmpty());
        assertTrue(receiver.states.isEmpty());
        assertEquals("access-2", manager.accessToken().join());
    }

    @Test
    public void nothingIsContactedWhenNothingIsRemembered() {
        manager.appOpened();
        assertEquals(State.SIGNED_OUT, manager.state());
        assertEquals(0, apiCreated);
        assertEquals(0, changes);
    }

    @Test
    public void aRememberedSignInThatTheProviderRejectsIsForgotten() {
        remember("refresh-1");
        manager.appOpened();
        prepare();
        provider.refreshes.fail(new AuthException(Kind.REJECTED, "The sign-in service refused the request (invalid_grant)."));
        owner.drain();
        assertSignedOut("expired");
        assertNull(store.stored);
        manager.appOpened();
        assertEquals(1, api.settings.count);
    }

    @Test
    public void aRememberedSignInSurvivesAnUnreachableProvider() {
        remember("refresh-1");
        manager.appOpened();
        prepare();
        provider.refreshes.fail(AuthException.network(new java.net.ConnectException("refused")));
        owner.drain();
        assertSignedOut("could not be restored");
        assertEquals("refresh-1", store.stored.refreshToken());
    }

    @Test
    public void aRememberedSignInSurvivesAServerThatDisconnectsWhileConfirming() {
        remember("refresh-1");
        manager.appOpened();
        prepare();
        provider.refreshes.answer(new Tokens("access-2", 3600, Optional.of("refresh-2"), Optional.empty()));
        owner.drain();
        api.me.fail(FakeAuth.unreachable());
        owner.drain();
        assertSignedOut("could not be confirmed");
        assertEquals("The rotated token is still there for next time", "refresh-2", store.stored.refreshToken());
        assertTrue(provider.revoked.isEmpty());

        manager.appOpened();
        prepare();
        assertEquals(List.of("refresh-1", "refresh-2"), provider.refreshTokensUsed);
    }

    @Test
    public void aRememberedSignInTheServerRefusesIsForgottenAndRevoked() {
        for (int status : new int[] {401, 403}) {
            remember("refresh-1");
            manager.appOpened();
            prepare();
            provider.refreshes.answer(new Tokens("access-2", 3600, Optional.of("refresh-2"), Optional.empty()));
            owner.drain();
            api.me.fail(FakeAuth.http(status));
            owner.drain();
            assertEquals(State.SIGNED_OUT, manager.state());
            assertNull(store.stored);
        }
        assertEquals(List.of("refresh-2", "refresh-2"), provider.revoked);
    }

    @Test
    public void aTokenRememberedForAnotherSignInServiceIsNeverSentToThisOne() {
        store.stored = new Stored("https://old-tenant.example/", FakeAuth.CLIENT_ID, "refresh-old", Optional.empty());
        manager.appOpened();
        prepare();
        assertSignedOut(null);
        assertNull(store.stored);
        assertTrue(provider.refreshTokensUsed.isEmpty());

        store.stored = new Stored(FakeAuth.ISSUER, "another-client", "refresh-old", Optional.empty());
        manager.appOpened();
        prepare();
        assertNull(store.stored);
        assertTrue(provider.refreshTokensUsed.isEmpty());
    }

    @Test
    public void cancellingARestoreKeepsTheRememberedSignInAndItsRotatedToken() {
        remember("refresh-1");
        manager.appOpened();
        prepare();
        manager.cancel();
        assertSignedOut(null);
        provider.refreshes.answer(new Tokens("access-2", 3600, Optional.of("refresh-2"), Optional.empty()));
        owner.drain();
        assertSignedOut(null);
        assertTrue(api.tokensSeen.isEmpty());
        assertEquals("refresh-2", store.stored.refreshToken());
    }

    @Test
    public void aRestoreThatFinishesAfterANewSignInDoesNotOverwriteIt() {
        remember("refresh-1");
        manager.appOpened();
        prepare();
        manager.cancel();
        signInFully();
        assertEquals("refresh-1", store.stored.refreshToken());
        store.stored = new Stored(FakeAuth.ISSUER, FakeAuth.CLIENT_ID, "refresh-new", Optional.empty());
        provider.refreshes.answer(new Tokens("access-old", 3600, Optional.of("refresh-old"), Optional.empty()));
        owner.drain();
        assertEquals("refresh-new", store.stored.refreshToken());
        assertEquals(State.SIGNED_IN, manager.state());
        assertEquals("access-1", manager.accessToken().join());
    }

    // --- Keeping a signed-in account honest ----------------------------------------------------------

    @Test
    public void reopeningTheAppPicksUpARoleTheServerChanged() {
        signInFully();
        manager.appOpened();
        assertEquals("Not asked again within the throttle", 1, api.me.count);

        now += SignInManager.VERIFY_AFTER_MILLIS;
        manager.appOpened();
        assertEquals(List.of("access-1", "access-1"), api.tokensSeen);
        api.me.answer(TEACHER);
        owner.drain();
        assertEquals(TEACHER, manager.account().orElseThrow().user());
        assertEquals(Optional.of("Alex Kim"), manager.account().orElseThrow().displayName());
    }

    @Test
    public void anAccessTokenTheServerNoLongerAcceptsIsRenewedOnceThenTheSessionEnds() {
        signInFully();
        now += SignInManager.VERIFY_AFTER_MILLIS;
        manager.appOpened();
        api.me.fail(FakeAuth.http(401));
        owner.drain();
        assertEquals(1, provider.refreshes.count);
        provider.refreshes.answer(new Tokens("access-2", 3600, Optional.of("refresh-2"), Optional.empty()));
        owner.drain();
        assertEquals(List.of("access-1", "access-1", "access-2"), api.tokensSeen);
        api.me.answer(STUDENT);
        owner.drain();
        assertEquals(State.SIGNED_IN, manager.state());

        now += SignInManager.VERIFY_AFTER_MILLIS;
        manager.appOpened();
        api.me.fail(FakeAuth.http(401));
        owner.drain();
        provider.refreshes.answer(new Tokens("access-3", 3600, Optional.of("refresh-3"), Optional.empty()));
        owner.drain();
        api.me.fail(FakeAuth.http(401));
        owner.drain();
        assertSignedOut("expired");
        assertNull(store.stored);
        assertEquals(List.of("refresh-3"), provider.revoked);
    }

    @Test
    public void anAccountDisabledOnTheServerIsSignedOut() {
        signInFully();
        now += SignInManager.VERIFY_AFTER_MILLIS;
        manager.appOpened();
        api.me.fail(FakeAuth.http(403));
        owner.drain();
        assertSignedOut("refused this account");
        assertNull(store.stored);
        assertEquals(List.of("refresh-1"), provider.revoked);
    }

    @Test
    public void aDisconnectedServerDoesNotSignAnyoneOut() {
        signInFully();
        for (var failure : List.of(FakeAuth.unreachable(), FakeAuth.http(500), FakeAuth.http(503))) {
            now += SignInManager.VERIFY_AFTER_MILLIS;
            manager.appOpened();
            api.me.fail(failure);
            owner.drain();
            assertEquals(State.SIGNED_IN, manager.state());
            assertEquals(STUDENT, manager.account().orElseThrow().user());
            assertEquals(Optional.empty(), manager.problem());
        }
        assertEquals("refresh-1", store.stored.refreshToken());
        assertEquals("access-1", manager.accessToken().join());
    }

    // --- What never happens --------------------------------------------------------------------------

    @Test
    public void tokensNeverAppearInMessagesOrTextForms() {
        Tokens tokens = new Tokens("secret-access", 3600, Optional.of("secret-refresh"), Optional.of("secret-id"));
        Stored stored = new Stored(FakeAuth.ISSUER, FakeAuth.CLIENT_ID, "secret-refresh", Optional.empty());
        String text = tokens + " " + stored + " " + Pkce.fromVerifier("secret-verifier");
        assertFalse(text, text.contains("secret"));

        toBrowser();
        redirectBack();
        provider.exchanges.answer(new Tokens("secret-access", 3600, Optional.of("secret-refresh"), Optional.empty()));
        owner.drain();
        api.me.fail(FakeAuth.http(401));
        owner.drain();
        assertFalse(manager.problem().orElseThrow().contains("secret"));
        assertFalse(manager.problem().orElseThrow().contains("the-code"));
    }

    @Test
    public void signInCodeNeverTouchesPracticeProgressOrTheMinecraftProfile() throws Exception {
        Path sources = Path.of("src/main/java/com/mineledger/backend/auth");
        try (Stream<Path> files = Files.list(sources)) {
            for (Path file : files.toList()) {
                String source = Files.readString(file);
                for (String forbidden : List.of("com.mineledger.progress", "com.mineledger.accounting", "com.mineledger.education",
                        "net.minecraft", "com.mojang", "LOGGER", "System.out", "client_secret")) {
                    assertFalse(file.getFileName() + " must not mention " + forbidden, source.contains(forbidden));
                }
            }
        }
    }
}
