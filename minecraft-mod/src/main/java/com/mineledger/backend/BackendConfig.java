package com.mineledger.backend;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/**
 * Local content-mode settings from {@code <game directory>/mineledger/config.json}. Holds no credentials.
 * Any invalid file means defaults plus a reported problem. Loading never rewrites an existing file; only an
 * explicit {@link #save} does, and it keeps a copy of a file it could not read.
 */
public record BackendConfig(Mode mode, URI baseUrl, Duration connectTimeout, Duration requestTimeout) {
    /** The connection preference: prefer the backend, never contact it, or require it. */
    public enum Mode {
        AUTO("auto"), OFFLINE("offline"), BACKEND("backend");

        private final String key;
        Mode(String key) { this.key = key; }
        public String key() { return key; }
    }

    public record Loaded(BackendConfig config, Optional<String> problem) { }

    public static final String DEFAULT_URL = "http://127.0.0.1:8000";
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 3000;
    public static final int DEFAULT_REQUEST_TIMEOUT_MS = 5000;
    public static final int MIN_TIMEOUT_MS = 100;
    public static final int MAX_TIMEOUT_MS = 60_000;
    public static final BackendConfig DEFAULT = new BackendConfig(Mode.AUTO, URI.create(DEFAULT_URL),
            Duration.ofMillis(DEFAULT_CONNECT_TIMEOUT_MS), Duration.ofMillis(DEFAULT_REQUEST_TIMEOUT_MS));
    /**
     * The file the previous version wrote by itself on first launch. Unedited, it records no choice by the
     * player, so it is read as the current default (auto) rather than as a request for offline mode.
     */
    static final String LEGACY_GENERATED_FILE = """
            {
              "mode": "offline",
              "backend_url": "http://127.0.0.1:8000",
              "connect_timeout_ms": 3000,
              "request_timeout_ms": 5000
            }
            """;
    /** Written into every file this version saves, which is what tells a saved choice from the old template. */
    public static final int FILE_VERSION = 2;
    private static final Set<String> KEYS = Set.of("config_version", "mode", "backend_url", "connect_timeout_ms", "request_timeout_ms");
    private static final int MAX_FILE_BYTES = 64 * 1024;

    public BackendConfig {
        if (mode == null || baseUrl == null || connectTimeout == null || requestTimeout == null) {
            throw new IllegalArgumentException("Backend configuration values are required");
        }
    }

    public static Path file(Path gameDirectory) {
        return gameDirectory.resolve("mineledger").resolve("config.json");
    }

    public BackendConfig withMode(Mode newMode) {
        return new BackendConfig(newMode, baseUrl, connectTimeout, requestTimeout);
    }

    /** The complete file content for these settings. */
    public String toJson() {
        return """
                {
                  "config_version": %d,
                  "mode": "%s",
                  "backend_url": "%s",
                  "connect_timeout_ms": %d,
                  "request_timeout_ms": %d
                }
                """.formatted(FILE_VERSION, mode.key(), baseUrl, connectTimeout.toMillis(), requestTimeout.toMillis());
    }

    /** Never throws. A missing file is created with the defaults so it can be edited by hand. */
    public static Loaded load(Path gameDirectory) {
        Path file = file(gameDirectory);
        try {
            if (Files.notExists(file)) {
                try {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, DEFAULT.toJson(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
                } catch (IOException | RuntimeException ignored) {
                    // Defaults apply whether or not the template could be written.
                }
                return new Loaded(DEFAULT, Optional.empty());
            }
            if (Files.size(file) > MAX_FILE_BYTES) throw new IllegalArgumentException("file is too large");
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
            if (text.replace("\r\n", "\n").strip().equals(LEGACY_GENERATED_FILE.strip())) return new Loaded(DEFAULT, Optional.empty());
            return new Loaded(parse(text), Optional.empty());
        } catch (IOException | RuntimeException exception) {
            return new Loaded(DEFAULT, Optional.of("Invalid " + file + " (" + exception.getMessage()
                    + "); using the default settings (automatic connection). Fix or delete the file, or choose a "
                    + "connection mode in MineLedger Settings."));
        }
    }

    /**
     * Writes these settings, replacing the file in one step so a crash cannot leave half a file. A file that
     * exists but cannot be read as configuration is first copied to {@code config.invalid.json}.
     */
    public void save(Path gameDirectory) throws IOException {
        Path file = file(gameDirectory);
        Files.createDirectories(file.getParent());
        if (Files.isRegularFile(file)) {
            boolean readable;
            try {
                parse(Files.readString(file, StandardCharsets.UTF_8));
                readable = true;
            } catch (IOException | RuntimeException exception) {
                readable = false;
            }
            if (!readable) {
                Files.copy(file, file.resolveSibling("config.invalid.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path temporary = file.resolveSibling("config.json.tmp");
        Files.writeString(temporary, toJson(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** @throws IllegalArgumentException describing the first invalid setting */
    public static BackendConfig parse(String json) {
        JsonObject root;
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement element = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("trailing content");
            if (!element.isJsonObject()) throw new IllegalArgumentException("expected a JSON object");
            root = element.getAsJsonObject();
        } catch (IOException | com.google.gson.JsonParseException | IllegalStateException exception) {
            throw new IllegalArgumentException("not valid JSON");
        }
        for (String key : root.keySet()) {
            if (!KEYS.contains(key)) throw new IllegalArgumentException("unknown setting \"" + key + "\"");
        }
        if (root.has("config_version")) {
            JsonElement version = root.get("config_version");
            boolean known = version.isJsonPrimitive() && version.getAsJsonPrimitive().isNumber()
                    && (version.getAsString().equals("1") || version.getAsString().equals("2"));
            if (!known) throw new IllegalArgumentException("config_version is not supported by this version of the game");
        }
        Mode mode = switch (string(root, "mode", "auto")) {
            case "auto" -> Mode.AUTO;
            case "offline" -> Mode.OFFLINE;
            case "backend" -> Mode.BACKEND;
            default -> throw new IllegalArgumentException("mode must be \"auto\", \"offline\" or \"backend\"");
        };
        return new BackendConfig(mode, url(string(root, "backend_url", DEFAULT_URL)),
                timeout(root, "connect_timeout_ms", DEFAULT_CONNECT_TIMEOUT_MS),
                timeout(root, "request_timeout_ms", DEFAULT_REQUEST_TIMEOUT_MS));
    }

    private static String string(JsonObject root, String key, String fallback) {
        if (!root.has(key)) return fallback;
        JsonElement value = root.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value.getAsString();
    }

    private static Duration timeout(JsonObject root, String key, int fallback) {
        if (!root.has(key)) return Duration.ofMillis(fallback);
        JsonElement value = root.get(key);
        long millis;
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            millis = value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be a whole number of milliseconds");
        }
        if (millis < MIN_TIMEOUT_MS || millis > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException(key + " must be between " + MIN_TIMEOUT_MS + " and " + MAX_TIMEOUT_MS);
        }
        return Duration.ofMillis(millis);
    }

    private static URI url(String text) {
        URI uri;
        try {
            uri = new URI(text.strip());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("backend_url is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("backend_url must be an http:// or https:// URL with a host");
        }
        // Credentials never belong in this file, and they would otherwise reach logs and error messages.
        if (uri.getRawUserInfo() != null) throw new IllegalArgumentException("backend_url must not contain credentials");
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("backend_url must not contain a query or fragment");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
        return URI.create(scheme + "://" + uri.getRawAuthority() + path);
    }
}
