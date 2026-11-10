package com.mineledger.progress;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mineledger.accounting.mastery.AssignmentProgress;
import com.mineledger.accounting.mastery.ConceptProgress;
import com.mineledger.progress.ProfileProgress.SavedAssignment;

/** Explicit JSON parsing only; no reflective or polymorphic deserialization. */
public final class JsonProgressRepository implements ProgressRepository {
    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private final Path directory;
    private final java.util.Map<UUID, Optional<ProfileProgress>> lastSeen = new HashMap<>();

    public JsonProgressRepository(Path gameDirectory) {
        directory = gameDirectory.toAbsolutePath().normalize().resolve("mineledger").resolve("progress");
    }

    public Path file(UUID profileId) { return directory.resolve(profileId.toString() + ".json"); }

    @Override
    public Optional<ProfileProgress> load(UUID profileId) throws IOException {
        checkPaths(profileId);
        Path path = file(profileId);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            lastSeen.put(profileId, Optional.empty());
            return Optional.empty();
        }
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new ProgressFormatException("Progress file exceeds the supported size");
            try (JsonReader reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
                reader.setStrictness(Strictness.STRICT);
                JsonObject root = readValue(reader, 0).getAsJsonObject();
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new ProgressFormatException("Trailing JSON content");
                if (number(root, "schemaVersion") != SCHEMA_VERSION) {
                    throw new ProgressFormatException("Unsupported progress schema; file preserved for migration");
                }
                if (!text(root, "profileId").equals(profileId.toString())) throw new ProgressFormatException("Profile ID mismatch");
                var assignments = new HashMap<String, SavedAssignment>();
                for (var entry : object(root, "assignments").entrySet()) {
                    String id = identifier(entry.getKey());
                    JsonObject value = entry.getValue().getAsJsonObject();
                    var concepts = new HashMap<String, ConceptProgress>();
                    for (var concept : object(value, "concepts").entrySet()) {
                        String conceptId = identifier(concept.getKey());
                        JsonObject state = concept.getValue().getAsJsonObject();
                        concepts.put(conceptId, new ConceptProgress(ids(state, "correctQuestionIds"),
                                ids(state, "attemptedQuestionIds"), nullableText(state, "lastAttemptedQuestionId")));
                    }
                    assignments.put(id, new SavedAssignment(new AssignmentProgress(id, concepts), nullableText(value, "activeConceptId"),
                            ids(value, "rewardedConceptIds"), number(value, "awardedXp")));
                }
                var result = Optional.of(new ProfileProgress(profileId, assignments, ids(root, "journalQuestionIds"), number(root, "totalAwardedXp")));
                lastSeen.put(profileId, result);
                return result;
            } catch (RuntimeException exception) {
                throw new ProgressFormatException("Malformed progress JSON: " + exception.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void save(ProfileProgress progress) throws IOException {
        // Re-read before replacement: an externally corrupted or newer-schema file is never overwritten.
        var expected = lastSeen.get(progress.profileId());
        var actual = load(progress.profileId());
        if (expected != null && !expected.equals(actual)) {
            throw new ProgressFormatException("Progress changed outside this client; existing file preserved. Reopen Minecraft to reload it.");
        }
        checkPaths(progress.profileId());
        Files.createDirectories(directory);
        checkPaths(progress.profileId());
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("profileId", progress.profileId().toString());
        root.addProperty("totalAwardedXp", progress.totalAwardedXp());
        root.add("journalQuestionIds", array(progress.journalQuestionIds()));
        JsonObject assignments = new JsonObject();
        progress.assignments().forEach((id, saved) -> {
            JsonObject assignment = new JsonObject();
            assignment.addProperty("activeConceptId", saved.activeConceptId());
            assignment.addProperty("awardedXp", saved.awardedXp());
            assignment.add("rewardedConceptIds", array(saved.rewardedConceptIds()));
            JsonObject concepts = new JsonObject();
            saved.progress().concepts().forEach((conceptId, state) -> {
                JsonObject concept = new JsonObject();
                concept.add("correctQuestionIds", array(state.correctQuestionIds()));
                concept.add("attemptedQuestionIds", array(state.attemptedQuestionIds()));
                concept.addProperty("lastAttemptedQuestionId", state.lastAttemptedQuestionId());
                concepts.add(conceptId, concept);
            });
            assignment.add("concepts", concepts);
            assignments.add(id, assignment);
        });
        root.add("assignments", assignments);
        String serialized = JSON.toJson(root) + "\n";
        if (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException("Progress exceeds the supported size");
        Path temporary = Files.createTempFile(directory, progress.profileId() + "-", ".tmp");
        try {
            Files.writeString(temporary, serialized, StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            try {
                Files.move(temporary, file(progress.profileId()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                // Keep a last-good copy on filesystems without atomic replacement.
                if (Files.exists(file(progress.profileId()))) {
                    Path backup = directory.resolve(progress.profileId() + ".json.bak");
                    if (Files.isSymbolicLink(backup)) throw new IOException("Refusing a symbolic-link backup path");
                    Files.copy(file(progress.profileId()), backup, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(temporary, file(progress.profileId()), StandardCopyOption.REPLACE_EXISTING);
            }
            lastSeen.put(progress.profileId(), Optional.of(progress));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void checkPaths(UUID profileId) throws IOException {
        if (Files.isSymbolicLink(directory.getParent()) || Files.isSymbolicLink(directory) || Files.isSymbolicLink(file(profileId))) {
            throw new IOException("Refusing symbolic links in the progress storage path");
        }
    }

    private static JsonElement readValue(JsonReader reader, int depth) throws IOException {
        if (depth > 32) throw new ProgressFormatException("Progress JSON is nested too deeply");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject value = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (value.has(name)) throw new ProgressFormatException("Duplicate JSON field");
                    value.add(name, readValue(reader, depth + 1));
                }
                reader.endObject();
                return value;
            }
            case BEGIN_ARRAY -> {
                JsonArray value = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) value.add(readValue(reader, depth + 1));
                reader.endArray();
                return value;
            }
            case STRING -> { return new JsonPrimitive(reader.nextString()); }
            case NUMBER -> { return new JsonPrimitive(new BigDecimal(reader.nextString())); }
            case BOOLEAN -> { return new JsonPrimitive(reader.nextBoolean()); }
            case NULL -> { reader.nextNull(); return JsonNull.INSTANCE; }
            default -> throw new ProgressFormatException("Unexpected JSON token");
        }
    }

    private static JsonElement required(JsonObject object, String key) throws ProgressFormatException {
        if (!object.has(key)) throw new ProgressFormatException("Missing progress field: " + key);
        return object.get(key);
    }

    private static JsonObject object(JsonObject object, String key) throws ProgressFormatException {
        return required(object, key).getAsJsonObject();
    }

    private static String text(JsonObject object, String key) throws ProgressFormatException {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new ProgressFormatException("Expected text: " + key);
        return identifier(value.getAsString());
    }

    private static String nullableText(JsonObject object, String key) throws ProgressFormatException {
        return required(object, key).isJsonNull() ? null : text(object, key);
    }

    private static String identifier(String value) throws ProgressFormatException {
        if (value.isBlank() || value.length() > 256) throw new ProgressFormatException("Invalid saved identifier");
        return value;
    }

    private static int number(JsonObject object, String key) throws ProgressFormatException {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ProgressFormatException("Expected integer: " + key);
        int result = value.getAsBigDecimal().intValueExact();
        if (result < 0) throw new ProgressFormatException("Negative progress value");
        return result;
    }

    private static Set<String> ids(JsonObject object, String key) throws ProgressFormatException {
        var values = new HashSet<String>();
        for (JsonElement entry : required(object, key).getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()
                    || !values.add(identifier(entry.getAsString()))) throw new ProgressFormatException("Invalid or duplicate saved identifier");
        }
        return values;
    }

    private static JsonArray array(Set<String> values) {
        JsonArray result = new JsonArray();
        values.stream().sorted().forEach(result::add);
        return result;
    }
}
