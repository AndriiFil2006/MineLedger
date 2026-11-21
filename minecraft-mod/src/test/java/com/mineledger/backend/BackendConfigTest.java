package com.mineledger.backend;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.mineledger.backend.BackendConfig.Mode;
import com.mineledger.curriculum.CurriculumContent;

public class BackendConfigTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path game() { return temporary.getRoot().toPath(); }

    private BackendConfig.Loaded loadFile(String content) throws Exception {
        Path file = BackendConfig.file(game());
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return BackendConfig.load(game());
    }

    private static void assertRejected(String json, String reason) {
        try {
            BackendConfig.parse(json);
            fail("Accepted: " + json);
        } catch (IllegalArgumentException exception) {
            assertTrue(exception.getMessage(), exception.getMessage().contains(reason));
        }
    }

    @Test
    public void missingFileMeansOfflineDefaultsAndWritesAnEditableTemplate() throws Exception {
        var loaded = BackendConfig.load(game());
        assertEquals(BackendConfig.DEFAULT, loaded.config());
        assertEquals(Mode.OFFLINE, loaded.config().mode());
        assertEquals("http://127.0.0.1:8000", loaded.config().baseUrl().toString());
        assertTrue(loaded.problem().isEmpty());
        String template = Files.readString(BackendConfig.file(game()), StandardCharsets.UTF_8);
        assertEquals(BackendConfig.DEFAULT, BackendConfig.parse(template));
        assertEquals(BackendConfig.DEFAULT, BackendConfig.load(game()).config());
    }

    @Test
    public void backendModeWithCustomUrlAndTimeouts() throws Exception {
        var loaded = loadFile("""
                {"mode": "backend", "backend_url": "http://localhost:9000/", "connect_timeout_ms": 250, "request_timeout_ms": 1500}
                """);
        assertTrue(loaded.problem().isEmpty());
        assertEquals(new BackendConfig(Mode.BACKEND, java.net.URI.create("http://localhost:9000"),
                Duration.ofMillis(250), Duration.ofMillis(1500)), loaded.config());
    }

    @Test
    public void omittedSettingsUseDefaults() {
        assertEquals(BackendConfig.DEFAULT, BackendConfig.parse("{}"));
        BackendConfig backend = BackendConfig.parse("{\"mode\":\"backend\"}");
        assertEquals(Mode.BACKEND, backend.mode());
        assertEquals(BackendConfig.DEFAULT.baseUrl(), backend.baseUrl());
        assertEquals(BackendConfig.DEFAULT.connectTimeout(), backend.connectTimeout());
        assertEquals(BackendConfig.DEFAULT.requestTimeout(), backend.requestTimeout());
        assertEquals("https://example.org/api", BackendConfig.parse("{\"backend_url\":\" https://example.org/api// \"}").baseUrl().toString());
    }

    @Test
    public void invalidFilesFallBackToOfflineWithAProblemAndAreLeftUntouched() throws Exception {
        for (String content : List.of("", "{", "not json", "[]", "null", "{\"mode\":\"backend\"} trailing",
                "{\"mode\":\"online\"}", "{\"mode\":\"backend\",\"backend_url\":\"ftp://127.0.0.1\"}",
                "{\"mode\":\"backend\",\"request_timeout_ms\":0}", "{\"mod\":\"backend\"}")) {
            var loaded = loadFile(content);
            assertEquals(content, BackendConfig.DEFAULT, loaded.config());
            assertEquals(content, Mode.OFFLINE, loaded.config().mode());
            assertTrue(content, loaded.problem().orElseThrow().contains("using offline mode"));
            assertEquals(content, Files.readString(BackendConfig.file(game()), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void unreadableConfigLocationDoesNotThrow() throws Exception {
        Files.createDirectories(BackendConfig.file(game())); // a directory where the file should be
        var loaded = BackendConfig.load(game());
        assertEquals(BackendConfig.DEFAULT, loaded.config());
        assertTrue(loaded.problem().isPresent());
        Path blocked = temporary.newFile("not-a-directory").toPath(); // the template cannot be created here
        assertEquals(BackendConfig.DEFAULT, BackendConfig.load(blocked).config());
    }

    @Test
    public void rejectsEachInvalidSettingWithAReason() {
        assertRejected("{\"mode\":\"BACKEND\"}", "mode must be");
        assertRejected("{\"mode\":true}", "mode must be a string");
        assertRejected("{\"mode\":null}", "mode must be a string");
        assertRejected("{\"backend_url\":8000}", "backend_url must be a string");
        assertRejected("{\"backend_url\":\"\"}", "http:// or https://");
        assertRejected("{\"backend_url\":\"127.0.0.1:8000\"}", "backend_url");
        assertRejected("{\"backend_url\":\"localhost:8000\"}", "http:// or https://");
        assertRejected("{\"backend_url\":\"http://\"}", "backend_url");
        assertRejected("{\"backend_url\":\"http://bad host\"}", "not a valid URL");
        assertRejected("{\"backend_url\":\"file:///C:/secrets\"}", "http:// or https://");
        assertRejected("{\"backend_url\":\"http://127.0.0.1:8000/?token=abc\"}", "query or fragment");
        assertRejected("{\"backend_url\":\"http://127.0.0.1:8000/#x\"}", "query or fragment");
        assertRejected("{\"connect_timeout_ms\":\"3000\"}", "whole number");
        assertRejected("{\"connect_timeout_ms\":1.5}", "whole number");
        assertRejected("{\"connect_timeout_ms\":99}", "between 100 and 60000");
        assertRejected("{\"request_timeout_ms\":-5}", "between 100 and 60000");
        assertRejected("{\"request_timeout_ms\":60001}", "between 100 and 60000");
        assertRejected("{\"request_timeout_ms\":1e30}", "whole number");
        assertRejected("{\"api_key\":\"x\"}", "unknown setting");
        assertEquals(100, BackendConfig.parse("{\"connect_timeout_ms\":100}").connectTimeout().toMillis());
        assertEquals(60000, BackendConfig.parse("{\"request_timeout_ms\":60000}").requestTimeout().toMillis());
    }

    @Test
    public void credentialsInTheUrlAreRejectedAndNeverEchoed() throws Exception {
        assertRejected("{\"backend_url\":\"http://admin:hunter2@127.0.0.1:8000\"}", "must not contain credentials");
        var loaded = loadFile("{\"mode\":\"backend\",\"backend_url\":\"http://admin:hunter2@127.0.0.1:8000\"}");
        assertEquals(Mode.OFFLINE, loaded.config().mode());
        assertFalse(loaded.problem().orElseThrow().contains("hunter2"));
    }

    @Test
    public void offlineSelectsTheLocalProviderWithoutCreatingAClient() {
        AtomicInteger clients = new AtomicInteger();
        var source = CurriculumSource.select(BackendConfig.DEFAULT, CurriculumContent::get, config -> {
            clients.incrementAndGet();
            return new BackendClient(config);
        });
        assertTrue(source instanceof CurriculumSource.Local);
        assertSame(CurriculumContent.get(), ((CurriculumSource.Local) source).curriculum());
        assertEquals(0, clients.get());
    }

    @Test
    public void backendModeSelectsTheRemoteProviderBuiltFromThatConfig() {
        BackendConfig config = BackendConfig.parse("{\"mode\":\"backend\",\"backend_url\":\"http://127.0.0.1:1\"}");
        AtomicInteger localLoads = new AtomicInteger();
        BackendConfig[] seen = new BackendConfig[1];
        try (BackendClient client = new BackendClient(config)) {
            var source = CurriculumSource.select(config, () -> {
                localLoads.incrementAndGet();
                return CurriculumContent.get();
            }, used -> {
                seen[0] = used;
                return client;
            });
            assertTrue(source instanceof CurriculumSource.Remote);
            assertSame(client, ((CurriculumSource.Remote) source).api());
            assertSame(config, seen[0]);
            assertEquals("Backend mode must not quietly read local content", 0, localLoads.get());
        }
    }

    @Test
    public void invalidConfigCanNeverSelectTheRemoteProvider() throws Exception {
        var loaded = loadFile("{\"mode\":\"backend\",\"backend_url\":\"nonsense\"}");
        var source = CurriculumSource.select(loaded.config(), CurriculumContent::get, config -> {
            throw new AssertionError("No client for an invalid configuration");
        });
        assertTrue(source instanceof CurriculumSource.Local);
    }
}
