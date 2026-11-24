package com.mineledger.backend.auth;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.mineledger.backend.BackendException;
import com.mineledger.backend.BackendJson;
import com.mineledger.backend.auth.AccountApi.RemoteUser;
import com.mineledger.backend.auth.AccountApi.Role;
import com.mineledger.backend.auth.RefreshTokenStore.Stored;

/** PKCE, the https rule, ID-token checks, the session file, and parsing of the server's account responses. */
public class AuthBuildingBlocksTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private static final long NOW = 1_800_000_000_000L;

    // --- PKCE ----------------------------------------------------------------------------------------

    @Test
    public void pkceMatchesTheRfc7636TestVector() {
        Pkce pkce = Pkce.fromVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkce.challenge());
    }

    @Test
    public void randomValuesAreLongUrlSafeAndNeverRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String token = Pkce.randomToken();
            assertTrue(token, token.matches("[A-Za-z0-9_-]{43}"));
            assertTrue(seen.add(token));
        }
    }

    // --- Transport rule ------------------------------------------------------------------------------

    @Test
    public void tokensOnlyTravelOverHttpsOrToThisComputer() {
        for (String allowed : List.of("https://tenant.us.auth0.com/", "https://api.school.example", "http://127.0.0.1:8000",
                "http://localhost:8000", "http://[::1]:8000", "HTTP://LOCALHOST:8000")) {
            assertTrue(allowed, AuthSettings.secure(allowed));
        }
        for (String refused : List.of("http://tenant.us.auth0.com/", "http://192.168.1.20:8000", "http://10.0.0.5", "http://localhost.evil.example",
                "http://127.0.0.1.evil.example", "http://user:pass@127.0.0.1", "https://user:pass@host.example", "ftp://127.0.0.1", "127.0.0.1:8000",
                "", "not a url", "http://", "file:///etc/passwd")) {
            assertFalse(refused, AuthSettings.secure(refused));
        }
    }

    @Test
    public void settingsRequireEveryValueAndASecureIssuer() {
        new AuthSettings("https://tenant.example/", "client", "audience");
        assertThrows(IllegalArgumentException.class, () -> new AuthSettings("http://tenant.example/", "client", "audience"));
        assertThrows(IllegalArgumentException.class, () -> new AuthSettings("https://tenant.example/", " ", "audience"));
        assertThrows(IllegalArgumentException.class, () -> new AuthSettings("https://tenant.example/", "client", null));
    }

    // --- ID token ------------------------------------------------------------------------------------

    private static Optional<String> name(String idToken) { return IdToken.displayName(idToken, FakeAuth.SETTINGS, "the-nonce", NOW); }

    @Test
    public void theDisplayNameComesFromAnIdentityIssuedForThisAttempt() {
        assertEquals(Optional.of("Alex Kim"), name(FakeAuth.idToken(FakeAuth.ISSUER, FakeAuth.CLIENT_ID, "the-nonce", NOW / 1000 + 60, "Alex Kim")));
        assertEquals(Optional.empty(), name(FakeAuth.idToken(FakeAuth.ISSUER, FakeAuth.CLIENT_ID, "the-nonce", NOW / 1000 + 60, null)));
        assertEquals("A small clock difference is tolerated", Optional.of("Alex"),
                name(FakeAuth.idToken(FakeAuth.ISSUER, FakeAuth.CLIENT_ID, "the-nonce", NOW / 1000 - 30, "Alex")));
    }

    @Test
    public void anIdentityForAnotherIssuerClientAttemptOrTimeIsRefused() {
        for (String token : List.of(
                FakeAuth.idToken("https://evil.example/", FakeAuth.CLIENT_ID, "the-nonce", NOW / 1000 + 60, "x"),
                FakeAuth.idToken(FakeAuth.ISSUER.replace("test/", "test"), FakeAuth.CLIENT_ID, "the-nonce", NOW / 1000 + 60, "x"),
                FakeAuth.idToken(FakeAuth.ISSUER, "another-client", "the-nonce", NOW / 1000 + 60, "x"),
                FakeAuth.idToken(FakeAuth.ISSUER, FakeAuth.CLIENT_ID, "another-nonce", NOW / 1000 + 60, "x"),
                FakeAuth.idToken(FakeAuth.ISSUER, FakeAuth.CLIENT_ID, "the-nonce", NOW / 1000 - 3600, "x"),
                "", "garbage", "a.b", "a.b.c", "a.b.c.d", "e30.bm90LWpzb24.c2ln")) {
            AuthException failure = assertThrows(token, AuthException.class, () -> name(token));
            assertEquals(AuthException.Kind.PROTOCOL, failure.kind());
        }
    }

    @Test
    public void aNameIsShownAsOneShortPlainLine() {
        String hostile = "Alex\\n\\u00a7cKim" + "x".repeat(200);
        String shown = name(FakeAuth.idToken(FakeAuth.ISSUER, FakeAuth.CLIENT_ID, "the-nonce", NOW / 1000 + 60, hostile)).orElseThrow();
        assertFalse(shown.contains("\n"));
        assertFalse("Minecraft formatting codes are stripped", shown.contains("§"));
        assertTrue(shown.startsWith("AlexcKim"));
        assertTrue(shown.length() <= 63);
        assertEquals(Optional.empty(), name(FakeAuth.idToken(FakeAuth.ISSUER, FakeAuth.CLIENT_ID, "the-nonce", NOW / 1000 + 60, "\\n \\t")));
    }

    // --- Session file --------------------------------------------------------------------------------

    @Test
    public void theSessionFileRoundTripsAndIsRemovedOnClear() throws Exception {
        Path game = folder.getRoot().toPath();
        RefreshTokenStore store = RefreshTokenStore.file(game);
        assertEquals(Optional.empty(), store.load());

        Stored stored = new Stored(FakeAuth.ISSUER, FakeAuth.CLIENT_ID, "refresh-1", Optional.of("Alex \"Lex\" Kim"));
        store.save(stored);
        Path file = game.resolve("mineledger").resolve("session.json");
        assertTrue(Files.isRegularFile(file));
        assertEquals(Optional.of(stored), RefreshTokenStore.file(game).load());
        assertFalse(Files.exists(file.resolveSibling("session.json.tmp")));
        assertFalse("It is not the settings file", Files.exists(file.resolveSibling("config.json")));

        store.save(new Stored(FakeAuth.ISSUER, FakeAuth.CLIENT_ID, "refresh-2", Optional.empty()));
        assertEquals("refresh-2", store.load().orElseThrow().refreshToken());
        assertFalse("The old token is gone from disk", Files.readString(file).contains("refresh-1"));

        store.clear();
        assertFalse(Files.exists(file));
        assertEquals(Optional.empty(), store.load());
        store.clear();
    }

    @Test
    public void anUnreadableSessionFileIsTheSameAsNoSession() throws Exception {
        Path game = folder.getRoot().toPath();
        Path file = game.resolve("mineledger").resolve("session.json");
        Files.createDirectories(file.getParent());
        for (String content : List.of("", "not json", "[]", "{}", "{\"version\":2,\"issuer\":\"i\",\"client_id\":\"c\",\"refresh_token\":\"t\"}",
                "{\"version\":1,\"issuer\":\"i\",\"client_id\":\"c\"}", "{\"version\":1,\"issuer\":\"i\",\"client_id\":\"c\",\"refresh_token\":\" \"}")) {
            Files.writeString(file, content, StandardCharsets.UTF_8);
            assertEquals(content, Optional.empty(), RefreshTokenStore.file(game).load());
        }
        assertEquals(Optional.empty(), RefreshTokenStore.none().load());
    }

    // --- Server responses ----------------------------------------------------------------------------

    @Test
    public void signInSettingsAreParsedStrictly() {
        assertEquals(Optional.of(new AuthSettings("https://t.example/", "cid", "aud")), BackendJson.authSettings(
                "{\"enabled\":true,\"issuer\":\"https://t.example/\",\"audience\":\"aud\",\"client_id\":\"cid\",\"future_field\":1}"));
        assertEquals(Optional.empty(), BackendJson.authSettings("{\"enabled\":false,\"issuer\":null,\"audience\":null,\"client_id\":null}"));
        for (String body : List.of("", "[]", "{}", "{\"enabled\":\"yes\"}", "{\"enabled\":true}",
                "{\"enabled\":true,\"issuer\":\"https://t.example/\",\"audience\":\"aud\",\"client_id\":null}",
                "{\"enabled\":true,\"issuer\":\"http://t.example/\",\"audience\":\"aud\",\"client_id\":\"cid\"}")) {
            BackendException failure = assertThrows(body, BackendException.class, () -> BackendJson.authSettings(body));
            assertEquals(BackendException.Kind.MALFORMED_RESPONSE, failure.kind());
        }
    }

    @Test
    public void theAccountIsParsedStrictlyAndAnUnknownRoleIsRefused() {
        String id = FakeAuth.USER_ID;
        assertEquals(new RemoteUser(id, Role.STUDENT),
                BackendJson.user("{\"id\":\"" + id + "\",\"role\":\"student\",\"created_at\":\"2026-11-24T10:00:00Z\"}"));
        assertEquals(new RemoteUser(id, Role.TEACHER), BackendJson.user("{\"id\":\"" + id + "\",\"role\":\"teacher\"}"));
        for (String body : List.of("{}", "{\"id\":\"" + id + "\"}", "{\"id\":\"" + id + "\",\"role\":\"admin\"}",
                "{\"id\":\"" + id + "\",\"role\":\"Teacher\"}", "{\"id\":\"alex\",\"role\":\"student\"}",
                "{\"id\":\"" + id.toUpperCase() + "\",\"role\":\"student\"}", "{\"id\":7,\"role\":\"student\"}", "[]", "null")) {
            assertThrows(body, BackendException.class, () -> BackendJson.user(body));
        }
    }
}
