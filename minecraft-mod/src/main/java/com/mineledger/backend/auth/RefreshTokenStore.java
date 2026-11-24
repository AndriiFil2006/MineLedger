package com.mineledger.backend.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Remembers a sign-in between game launches by keeping the refresh token. The access token is never stored.
 *
 * <p>A stored token is tied to the issuer and client it came from and is ignored for any other.
 */
public interface RefreshTokenStore {
    /** {@code displayName} is the name shown in Settings; it is kept so a restored sign-in still has one. */
    record Stored(String issuer, String clientId, String refreshToken, Optional<String> displayName) {
        @Override public String toString() { return "Stored[issuer=" + issuer + "]"; }
    }

    Optional<Stored> load();

    /** @throws IOException if the token could not be saved; the sign-in then lasts until the game closes */
    void save(Stored stored) throws IOException;

    void clear();

    /** Keeps nothing: every launch starts signed out. */
    static RefreshTokenStore none() {
        return new RefreshTokenStore() {
            @Override public Optional<Stored> load() { return Optional.empty(); }
            @Override public void save(Stored stored) { }
            @Override public void clear() { }
        };
    }

    /**
     * {@code <game directory>/mineledger/session.json}, kept apart from {@code config.json} so that sharing
     * or editing settings never involves a credential. Readable by the current operating-system user only
     * where the file system supports that; on Windows it inherits the profile folder's protection.
     *
     * <p>This is a plain file: other software running as the same user can read it. Refresh-token rotation,
     * an absolute lifetime at the provider, and revocation on Sign Out bound what a copy is worth.
     */
    static RefreshTokenStore file(Path gameDirectory) {
        Path file = gameDirectory.resolve("mineledger").resolve("session.json");
        return new RefreshTokenStore() {
            @Override
            public Optional<Stored> load() {
                try {
                    if (!Files.isRegularFile(file) || Files.size(file) > 64 * 1024) return Optional.empty();
                    JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (!"1".equals(text(json, "version"))) return Optional.empty();
                    String issuer = text(json, "issuer");
                    String clientId = text(json, "client_id");
                    String token = text(json, "refresh_token");
                    if (issuer == null || clientId == null || token == null) return Optional.empty();
                    return Optional.of(new Stored(issuer, clientId, token, Optional.ofNullable(text(json, "display_name"))));
                } catch (IOException | RuntimeException exception) {
                    return Optional.empty(); // An unreadable session is the same as no session.
                }
            }

            @Override
            public void save(Stored stored) throws IOException {
                JsonObject json = new JsonObject();
                json.addProperty("version", 1);
                json.addProperty("issuer", stored.issuer());
                json.addProperty("client_id", stored.clientId());
                json.addProperty("refresh_token", stored.refreshToken());
                stored.displayName().ifPresent(name -> json.addProperty("display_name", name));
                Files.createDirectories(file.getParent());
                Path temporary = file.resolveSibling("session.json.tmp");
                Files.deleteIfExists(temporary);
                try {
                    Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                } catch (UnsupportedOperationException exception) {
                    Files.createFile(temporary); // Windows: no POSIX permissions; the user profile's ACL applies.
                }
                try {
                    Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
                    try {
                        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException exception) {
                        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }

            @Override
            public void clear() {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException | RuntimeException exception) {
                    // Overwriting is the fallback: a token that cannot be deleted must at least stop being readable.
                    try {
                        Files.writeString(file, "{}", StandardCharsets.UTF_8);
                    } catch (IOException | RuntimeException ignored) {
                        // Nothing more can be done locally; Sign Out still revokes the token at the provider.
                    }
                }
            }

            private static String text(JsonObject json, String key) {
                JsonElement value = json.get(key);
                if (value == null || !value.isJsonPrimitive()) return null;
                String text = value.getAsString();
                return text.isBlank() ? null : text;
            }
        };
    }
}
