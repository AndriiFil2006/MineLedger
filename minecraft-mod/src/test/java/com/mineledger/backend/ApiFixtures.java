package com.mineledger.backend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import com.google.gson.JsonParser;

/** Real backend responses from ../shared/fixtures/api_responses; backend/tests/test_api_contract.py keeps them true. */
final class ApiFixtures {
    static final Path DIRECTORY = Path.of("..", "shared", "fixtures", "api_responses");
    static final String HEALTH = "/health";
    static final String META = "/api/v1/meta";
    static final String COURSES = "/api/v1/courses";
    static final String COURSE = "/api/v1/courses/accounting101";
    static final String UNIT = "/api/v1/units/accounting101.recording_transactions";
    static final String ASSIGNMENT = "/api/v1/assignments/inventory_fundamentals";
    static final String CONCEPT_1 = "/api/v1/concepts/inventory.purchases_on_credit";
    static final String CONCEPT_2 = "/api/v1/concepts/payables.cash_payments";
    static final String QUESTIONS = "/api/v1/assignments/inventory_fundamentals/questions";

    /** Request path to response body, in manifest order. */
    static Map<String, String> responses() {
        try {
            Map<String, String> result = new LinkedHashMap<>();
            var manifest = JsonParser.parseString(Files.readString(DIRECTORY.resolve("manifest.json"), StandardCharsets.UTF_8));
            for (var entry : manifest.getAsJsonObject().getAsJsonArray("responses")) {
                var response = entry.getAsJsonObject();
                result.put(response.get("path").getAsString(),
                        Files.readString(DIRECTORY.resolve(response.get("file").getAsString()), StandardCharsets.UTF_8));
            }
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static String body(String path) {
        String body = responses().get(path);
        if (body == null) throw new IllegalArgumentException("No fixture for " + path);
        return body;
    }

    private ApiFixtures() { }
}
