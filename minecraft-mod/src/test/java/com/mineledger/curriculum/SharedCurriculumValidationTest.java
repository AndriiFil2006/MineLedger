package com.mineledger.curriculum;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.Test;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Runs ../shared/fixtures/curriculum_validation_cases.json through the real client loading path
 * (JsonCurriculumRepository + CurriculumLoader). The backend's Python validator runs the same cases.
 */
public class SharedCurriculumValidationTest {
    private static final Path FIXTURE = Path.of("..", "shared", "fixtures", "curriculum_validation_cases.json");
    private static final Path WORK = Path.of("build", "tmp", "shared-curriculum-validation");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    @Test
    public void javaLoaderMatchesSharedValidationCases() throws IOException {
        JsonObject fixture = JsonParser.parseString(Files.readString(FIXTURE, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, fixture.get("fixture_version").getAsInt());
        String base = Files.readString(Path.of("..").resolve(fixture.get("base_curriculum").getAsString()), StandardCharsets.UTF_8);
        Files.createDirectories(WORK);
        List<String> mismatches = new ArrayList<>();
        int index = 0;
        for (JsonElement element : fixture.getAsJsonArray("cases")) {
            JsonObject item = element.getAsJsonObject();
            String name = item.get("name").getAsString();
            Path file = WORK.resolve("case-" + index++ + ".json");
            Files.write(file, bytes(item, base));
            String error = null;
            try {
                new JsonCurriculumRepository(file).load();
            } catch (CurriculumValidationException | IOException exception) {
                error = exception.getMessage();
            }
            boolean expectValid = item.get("expect").getAsString().equals("valid");
            if (expectValid && error != null) mismatches.add(name + ": expected valid but Java rejected it: " + error);
            if (!expectValid && error == null) mismatches.add(name + ": expected invalid but Java accepted it");
            if (!expectValid && error != null && item.has("java_message") && !error.contains(item.get("java_message").getAsString())) {
                mismatches.add(name + ": Java error lacks '" + item.get("java_message").getAsString() + "': " + error);
            }
            System.out.println("[" + name + "] " + (error == null ? "accepted" : "rejected: " + error));
        }
        assertTrue(String.join("\n", mismatches), mismatches.isEmpty());
    }

    private static byte[] bytes(JsonObject item, String base) {
        if (item.get("kind").getAsString().equals("raw")) {
            return item.has("base64") ? Base64.getDecoder().decode(item.get("base64").getAsString())
                    : item.get("text").getAsString().getBytes(StandardCharsets.UTF_8);
        }
        JsonObject document = JsonParser.parseString(base).getAsJsonObject();
        for (JsonElement op : item.getAsJsonArray("ops")) apply(document, op.getAsJsonObject());
        return GSON.toJson(document).getBytes(StandardCharsets.UTF_8);
    }

    private static void apply(JsonObject document, JsonObject op) {
        String kind = op.get("op").getAsString();
        JsonArray path = op.getAsJsonArray("path");
        if (kind.equals("append") || kind.equals("remove_value")) {
            JsonArray target = resolve(document, path, path.size()).getAsJsonArray();
            if (kind.equals("append")) target.add(op.get("value"));
            else assertTrue("value to remove is absent", target.remove(op.get("value")));
            return;
        }
        JsonElement parent = resolve(document, path, path.size() - 1);
        JsonElement last = path.get(path.size() - 1);
        if (parent.isJsonObject()) {
            String key = last.getAsString();
            if (kind.equals("set")) parent.getAsJsonObject().add(key, op.get("value"));
            else if (kind.equals("delete")) parent.getAsJsonObject().remove(key);
            else fail("unknown fixture op " + kind);
        } else {
            JsonArray array = parent.getAsJsonArray();
            int position = position(array, last);
            if (kind.equals("set")) array.set(position, op.get("value"));
            else if (kind.equals("delete")) array.remove(position);
            else fail("unknown fixture op " + kind);
        }
    }

    private static JsonElement resolve(JsonElement current, JsonArray path, int steps) {
        for (int i = 0; i < steps; i++) {
            JsonElement step = path.get(i);
            current = current.isJsonObject() ? current.getAsJsonObject().get(step.getAsString())
                    : current.getAsJsonArray().get(position(current.getAsJsonArray(), step));
            assertNotNull("fixture path step not found: " + step, current);
        }
        return current;
    }

    private static int position(JsonArray array, JsonElement step) {
        if (step.isJsonPrimitive() && step.getAsJsonPrimitive().isNumber()) return step.getAsInt();
        String id = step.getAsJsonObject().get("id").getAsString();
        for (int i = 0; i < array.size(); i++) {
            JsonElement candidate = array.get(i);
            if (candidate.isJsonObject() && candidate.getAsJsonObject().has("id")
                    && candidate.getAsJsonObject().get("id").getAsString().equals(id)) return i;
        }
        throw new AssertionError("fixture id not found: " + id);
    }
}
