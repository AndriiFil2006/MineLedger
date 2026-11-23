package com.mineledger.backend;

import static org.junit.Assert.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.mineledger.backend.BackendConfig.Mode;

public class BackendConfigTest {
    /** Exactly what the previous version wrote on first launch, before automatic mode existed. */
    private static final String MILESTONE_11B_GENERATED_FILE = """
            {
              "mode": "offline",
              "backend_url": "http://127.0.0.1:8000",
              "connect_timeout_ms": 3000,
              "request_timeout_ms": 5000
            }
            """;

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path game() { return temporary.getRoot().toPath(); }
    private Path file() { return BackendConfig.file(game()); }

    private BackendConfig.Loaded loadFile(String content) throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), content, StandardCharsets.UTF_8);
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
    public void missingFileMeansAutomaticDefaultsAndWritesAnEditableTemplate() throws Exception {
        var loaded = BackendConfig.load(game());
        assertEquals(BackendConfig.DEFAULT, loaded.config());
        assertEquals(Mode.AUTO, loaded.config().mode());
        assertEquals("http://127.0.0.1:8000", loaded.config().baseUrl().toString());
        assertTrue(loaded.problem().isEmpty());
        String template = Files.readString(file(), StandardCharsets.UTF_8);
        assertTrue(template.contains("\"mode\": \"auto\""));
        assertEquals(BackendConfig.DEFAULT, BackendConfig.parse(template));
        assertEquals(BackendConfig.DEFAULT, BackendConfig.load(game()).config());
    }

    @Test
    public void allThreeModesAreAccepted() {
        assertEquals(Mode.AUTO, BackendConfig.parse("{\"mode\":\"auto\"}").mode());
        assertEquals(Mode.OFFLINE, BackendConfig.parse("{\"mode\":\"offline\"}").mode());
        assertEquals(Mode.BACKEND, BackendConfig.parse("{\"mode\":\"backend\"}").mode());
        assertEquals("A file that says nothing about the mode gets the default", Mode.AUTO, BackendConfig.parse("{}").mode());
        assertEquals(Mode.AUTO, BackendConfig.parse("{\"backend_url\":\"http://example.org\"}").mode());
    }

    @Test
    public void existingFilesWithAnExplicitChoiceKeepIt() throws Exception {
        // Hand-written during the previous milestone, exactly as documented then.
        var offline = loadFile("{ \"mode\": \"offline\" }");
        assertEquals(Mode.OFFLINE, offline.config().mode());
        assertTrue(offline.problem().isEmpty());
        var backend = loadFile("{ \"mode\": \"backend\", \"backend_url\": \"http://127.0.0.1:8000\" }");
        assertEquals(Mode.BACKEND, backend.config().mode());
        var custom = loadFile("""
                {"mode": "backend", "backend_url": "http://localhost:9000/", "connect_timeout_ms": 250, "request_timeout_ms": 1500}
                """);
        assertTrue(custom.problem().isEmpty());
        assertEquals(new BackendConfig(Mode.BACKEND, URI.create("http://localhost:9000"),
                Duration.ofMillis(250), Duration.ofMillis(1500)), custom.config());
    }

    @Test
    public void theUntouchedFileGeneratedByThePreviousVersionBecomesAutomatic() throws Exception {
        for (String generated : List.of(MILESTONE_11B_GENERATED_FILE, MILESTONE_11B_GENERATED_FILE.replace("\n", "\r\n"))) {
            var loaded = loadFile(generated);
            assertEquals("Nobody chose offline here; the old version wrote it by itself", Mode.AUTO, loaded.config().mode());
            assertTrue(loaded.problem().isEmpty());
            assertEquals("Loading does not rewrite the file", generated, Files.readString(file(), StandardCharsets.UTF_8));
        }
        // Any edit at all means a person made a choice, and offline is honored.
        assertEquals(Mode.OFFLINE, loadFile(MILESTONE_11B_GENERATED_FILE.replace("5000", "6000")).config().mode());
        assertEquals(Mode.OFFLINE, loadFile(MILESTONE_11B_GENERATED_FILE.replace("127.0.0.1", "localhost")).config().mode());
    }

    @Test
    public void omittedSettingsUseDefaults() {
        assertEquals(BackendConfig.DEFAULT, BackendConfig.parse("{}"));
        BackendConfig backend = BackendConfig.parse("{\"mode\":\"backend\"}");
        assertEquals(BackendConfig.DEFAULT.baseUrl(), backend.baseUrl());
        assertEquals(BackendConfig.DEFAULT.connectTimeout(), backend.connectTimeout());
        assertEquals(BackendConfig.DEFAULT.requestTimeout(), backend.requestTimeout());
        assertEquals("https://example.org/api", BackendConfig.parse("{\"backend_url\":\" https://example.org/api// \"}").baseUrl().toString());
    }

    @Test
    public void invalidFilesFallBackToDefaultsWithAProblemAndAreLeftUntouched() throws Exception {
        for (String content : List.of("", "{", "not json", "[]", "null", "{\"mode\":\"backend\"} trailing",
                "{\"mode\":\"online\"}", "{\"mode\":\"backend\",\"backend_url\":\"ftp://127.0.0.1\"}",
                "{\"mode\":\"backend\",\"request_timeout_ms\":0}", "{\"mod\":\"backend\"}")) {
            var loaded = loadFile(content);
            assertEquals(content, BackendConfig.DEFAULT, loaded.config());
            assertTrue(content, loaded.problem().orElseThrow().contains("using the default settings"));
            assertEquals(content, Files.readString(file(), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void unreadableConfigLocationDoesNotThrow() throws Exception {
        Files.createDirectories(file()); // a directory where the file should be
        var loaded = BackendConfig.load(game());
        assertEquals(BackendConfig.DEFAULT, loaded.config());
        assertTrue(loaded.problem().isPresent());
        Path blocked = temporary.newFile("not-a-directory").toPath(); // the template cannot be created here
        assertEquals(BackendConfig.DEFAULT, BackendConfig.load(blocked).config());
    }

    @Test
    public void rejectsEachInvalidSettingWithAReason() {
        assertRejected("{\"mode\":\"BACKEND\"}", "mode must be");
        assertRejected("{\"mode\":\"automatic\"}", "mode must be");
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
        assertEquals(BackendConfig.DEFAULT, loaded.config());
        assertFalse(loaded.problem().orElseThrow().contains("hunter2"));
    }

    @Test
    public void savingAPreferenceKeepsTheUrlAndTimeoutsAndReadsBackIdentically() throws Exception {
        BackendConfig original = loadFile("""
                {"mode": "backend", "backend_url": "https://school.example/mineledger", "connect_timeout_ms": 700, "request_timeout_ms": 9000}
                """).config();
        for (Mode mode : Mode.values()) {
            original.withMode(mode).save(game());
            var reloaded = BackendConfig.load(game());
            assertTrue(reloaded.problem().isEmpty());
            assertEquals(original.withMode(mode), reloaded.config());
        }
        assertFalse("No temporary file is left behind", Files.exists(file().resolveSibling("config.json.tmp")));
        assertFalse("A readable file needs no backup", Files.exists(file().resolveSibling("config.invalid.json")));
    }

    @Test
    public void aSavedOfflineChoiceIsNotMistakenForTheOldGeneratedFile() throws Exception {
        // Same values as the old generated file; only a file saved by this version carries config_version.
        BackendConfig.DEFAULT.withMode(Mode.OFFLINE).save(game());
        assertNotEquals(MILESTONE_11B_GENERATED_FILE, Files.readString(file(), StandardCharsets.UTF_8));
        assertEquals("The student's saved choice survives a restart", Mode.OFFLINE, BackendConfig.load(game()).config().mode());
        assertRejected("{\"config_version\": 3}", "config_version");
        assertRejected("{\"config_version\": \"2\"}", "config_version");
        assertEquals(Mode.BACKEND, BackendConfig.parse("{\"config_version\": 2, \"mode\": \"backend\"}").mode());
    }

    @Test
    public void savingOverAnUnreadableFileKeepsACopyOfIt() throws Exception {
        loadFile("{ this is not json, but someone typed it");
        BackendConfig.DEFAULT.withMode(Mode.BACKEND).save(game());
        assertEquals(Mode.BACKEND, BackendConfig.load(game()).config().mode());
        assertEquals("{ this is not json, but someone typed it",
                Files.readString(file().resolveSibling("config.invalid.json"), StandardCharsets.UTF_8));
    }

    @Test
    public void savingCreatesTheFolderAndNeverTouchesProgressFiles() throws Exception {
        Path progress = game().resolve("mineledger").resolve("progress").resolve("11111111-2222-3333-4444-555555555555.json");
        Files.createDirectories(progress.getParent());
        Files.writeString(progress, "{\"pretend\":\"progress\"}", StandardCharsets.UTF_8);
        BackendConfig.DEFAULT.withMode(Mode.BACKEND).save(game());
        BackendConfig.DEFAULT.withMode(Mode.AUTO).save(game());
        assertEquals("{\"pretend\":\"progress\"}", Files.readString(progress, StandardCharsets.UTF_8));
        try (var files = Files.list(game().resolve("mineledger"))) {
            assertEquals(List.of("config.json", "progress"), files.map(path -> path.getFileName().toString()).sorted().toList());
        }

        Path fresh = temporary.newFolder("fresh").toPath();
        BackendConfig.DEFAULT.withMode(Mode.OFFLINE).save(fresh);
        assertTrue(Files.isRegularFile(BackendConfig.file(fresh)));
    }
}
