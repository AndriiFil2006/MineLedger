package com.mineledger.curriculum;

import static org.junit.Assert.*;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.google.gson.*;

public class CurriculumLoaderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private String resource(String path) throws Exception {
        try (var input = getClass().getResourceAsStream(path)) {
            assertNotNull(path, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private JsonObject original() throws Exception { return JsonParser.parseString(resource(JsonCurriculumRepository.RESOURCE)).getAsJsonObject(); }
    private JsonObject first(JsonObject root, String table) { return root.getAsJsonArray(table).get(0).getAsJsonObject(); }
    private Curriculum load(JsonObject root) { return new CurriculumLoader().load(new StringReader(root.toString()), "test-curriculum.json"); }
    private void invalid(JsonObject root, String message) {
        var error = assertThrows(CurriculumValidationException.class, () -> load(root));
        assertTrue(error.getMessage(), error.getMessage().contains("test-curriculum.json"));
        assertTrue(error.getMessage(), error.getMessage().contains(message));
    }
    private String fingerprint(Object object) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(new Gson().toJson(object).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void bundledJsonResolvesAllExistingContentAndSupportedTypes() throws Exception {
        var data = new JsonCurriculumRepository().load();
        assertEquals(1, data.schemaVersion());
        assertEquals(1, data.courses().courses().size());
        assertEquals(3, data.courses().courses().getFirst().units().size());
        assertEquals(8, data.courses().courses().getFirst().units().stream().mapToInt(unit -> unit.assignments().size()).sum());
        assertEquals(1, data.courses().availableAssignments().size());
        assertEquals(2, data.concepts().size());
        assertEquals(6, data.questions().size());
        assertEquals(3, data.labs().get("intro_journal_practice").questions().size());
        assertSame(data.questions().get("inventory.shovels"), data.concepts().get("inventory.purchases_on_credit").questions().getFirst());
        assertSame(data.concepts().get("inventory.purchases_on_credit"), data.courses().assignment("inventory_fundamentals")
                .orElseThrow().concepts().getFirst());
        assertTrue(data.lessons().forConcept("inventory.purchases_on_credit").isPresent());
        assertTrue(data.lessons().forConcept("payables.cash_payments").isEmpty());
    }

    @Test
    public void allTextIdsAnswersAndRelationshipsMatchPreMigrationCompiledObjects() throws Exception {
        var expected = JsonParser.parseString(resource("/curriculum/pre_json_fingerprints.json")).getAsJsonObject();
        var data = new JsonCurriculumRepository().load();
        assertEquals(expected.get("course_sha256").getAsString(), fingerprint(data.courses().courses().getFirst()));
        assertEquals(expected.get("journal_sha256").getAsString(), fingerprint(data.labs().get("intro_journal_practice").questions()));
        assertEquals(expected.get("lesson_sha256").getAsString(), fingerprint(data.lessons().forConcept("inventory.purchases_on_credit").orElseThrow()));
    }

    @Test
    public void unsupportedAndNonintegerSchemaVersionsAreRejected() throws Exception {
        for (int version : new int[] {0, 2, 99}) {
            var root = original(); root.addProperty("schema_version", version); invalid(root, "schema_version");
        }
        var future = original(); future.addProperty("schema_version", 2); future.addProperty("future_field", "value");
        invalid(future, "Unsupported curriculum schema_version");
        var root = original(); root.addProperty("schema_version", 1.5); invalid(root, "integer");
    }

    @Test
    public void missingAndBlankRequiredQuestionFieldsAreRejectedWithIdContext() throws Exception {
        var root = original(); first(root, "questions").remove("prompt"); invalid(root, "inventory.shovels");
        root = original(); first(root, "questions").addProperty("prompt", "  "); invalid(root, "prompt");
    }

    @Test
    public void duplicateEntityAndCrossTableIdsAreRejected() throws Exception {
        var root = original(); root.getAsJsonArray("questions").add(first(root, "questions").deepCopy()); invalid(root, "duplicate ID");
        root = original(); first(root, "courses").addProperty("id", first(root, "units").get("id").getAsString());
        invalid(root, "Duplicate entity ID");
    }

    @Test
    public void invalidAnswerKeysAndAmbiguousOptionsAreRejected() throws Exception {
        var root = original(); first(root, "questions").addProperty("correct_answer_id", "missing-option"); invalid(root, "question inventory.shovels");
        root = original(); var options = first(root, "questions").getAsJsonArray("answer_options"); options.remove(0); invalid(root, "four unique options");
        root = original(); options = first(root, "questions").getAsJsonArray("answer_options");
        options.get(0).getAsJsonObject().addProperty("id", options.get(1).getAsJsonObject().get("id").getAsString());
        invalid(root, "four unique options");
    }

    @Test
    public void missingConceptAndQuestionReferencesAreRejected() throws Exception {
        var root = original(); root.getAsJsonArray("assignments").get(3).getAsJsonObject().getAsJsonArray("concept_ids").set(0, new JsonPrimitive("missing-concept"));
        invalid(root, "missing-concept");
        root = original(); first(root, "concepts").getAsJsonArray("question_ids").set(0, new JsonPrimitive("missing-question"));
        invalid(root, "missing-question");
        root = original(); first(root, "questions").addProperty("concept_id", "wrong-concept"); invalid(root, "concept_id");
    }

    @Test
    public void insufficientOrRepeatedQuestionBanksCannotFakeMastery() throws Exception {
        var root = original(); first(root, "concepts").addProperty("required_correct_answers", 4); invalid(root, "insufficient distinct question bank");
        root = original(); var ids = first(root, "concepts").getAsJsonArray("question_ids");
        ids.set(1, ids.get(0)); invalid(root, "duplicate or multiply-owned");
        root = original(); first(root, "concepts").addProperty("required_correct_answers", 0); invalid(root, "required correct answers");
    }

    @Test
    public void unsupportedQuestionTypesNeverFallBackToMultipleChoice() throws Exception {
        var root = original(); first(root, "questions").addProperty("type", "essay"); invalid(root, "unsupported type essay");
    }

    @Test
    public void incorrectParentsAndUnitOrderAreRejected() throws Exception {
        var root = original(); first(root, "concepts").addProperty("assignment_id", "wrong-assignment"); invalid(root, "assignment_id");
        root = original(); first(root, "units").addProperty("parent_course_id", "wrong-course"); invalid(root, "course accounting101");
        root = original(); root.getAsJsonArray("units").get(1).getAsJsonObject().addProperty("order", 1); invalid(root, "increasing number order");
    }

    @Test
    public void missingOrUnrelatedLessonReferencesAreRejected() throws Exception {
        var root = original(); first(root, "concepts").addProperty("lesson_id", "missing-lesson"); invalid(root, "missing-lesson");
        root = original(); first(root, "lessons").addProperty("concept_id", "payables.cash_payments"); invalid(root, "incorrect or reused lesson");
    }

    @Test
    public void orphanedQuestionsAndLessonsAreNotSilentlyDiscarded() throws Exception {
        var root = original(); first(root, "concepts").getAsJsonArray("question_ids").remove(0); invalid(root, "Unreferenced question");
        root = original(); first(root, "concepts").add("lesson_id", JsonNull.INSTANCE); invalid(root, "Unreferenced lesson");
    }

    @Test
    public void labOwnershipAndSupportedAccountIdsAreValidated() throws Exception {
        var root = original(); root.getAsJsonArray("questions").get(6).getAsJsonObject().addProperty("lab_id", "wrong-lab"); invalid(root, "lab_id mismatch");
        root = original(); root.getAsJsonArray("questions").get(6).getAsJsonObject().addProperty("correct_debit_account", "FAKE_ACCOUNT"); invalid(root, "question journal.inventory_on_credit");
        root = original(); root.getAsJsonArray("questions").get(6).getAsJsonObject().addProperty("correct_amount", "0.001"); invalid(root, "question journal.inventory_on_credit");
    }

    @Test
    public void unknownFieldsAndUnsupportedComingSoonContentAreRejected() throws Exception {
        var root = original(); first(root, "questions").addProperty("correct_answer_position", 1); invalid(root, "unknown field");
        root = original(); root.getAsJsonArray("assignments").get(3).getAsJsonObject().addProperty("availability", "coming_soon");
        invalid(root, "assignment inventory_fundamentals");
    }

    @Test
    public void optionalDifficultyAndLessonExtrasAreSupportedWithoutInventingQuestionTypes() throws Exception {
        var root = original(); first(root, "questions").addProperty("difficulty", "introductory");
        first(root, "lessons").remove("common_mistakes"); first(root, "lessons").remove("takeaway");
        var loaded = load(root);
        assertEquals("introductory", loaded.questionDifficulties().get("inventory.shovels"));
        assertTrue(loaded.lessons().forConcept("inventory.purchases_on_credit").orElseThrow().commonMistakes().isEmpty());
        assertEquals("", loaded.lessons().forConcept("inventory.purchases_on_credit").orElseThrow().takeaway());
    }

    @Test
    public void duplicateJsonFieldsAndTrailingContentAreRejected() throws Exception {
        String original = resource(JsonCurriculumRepository.RESOURCE);
        String duplicate = original.replace("\"schema_version\": 1", "\"schema_version\": 1, \"schema_version\": 1");
        assertTrue(assertThrows(CurriculumValidationException.class,
                () -> new CurriculumLoader().load(new StringReader(duplicate), "duplicate.json")).getMessage().contains("Duplicate JSON field"));
        assertThrows(CurriculumValidationException.class, () -> new CurriculumLoader().load(new StringReader(original + "{}"), "trailing.json"));
    }

    @Test
    public void standaloneFileRepositoryMatchesBundledSourceAndNeverFallsBackOnMissingFile() throws Exception {
        var file = temporary.newFile("curriculum.json").toPath();
        Files.writeString(file, resource(JsonCurriculumRepository.RESOURCE));
        assertEquals(new JsonCurriculumRepository().load().courses().courses(), new JsonCurriculumRepository(file).load().courses().courses());
        assertThrows(java.io.IOException.class, () -> new JsonCurriculumRepository(file.resolveSibling("missing.json")).load());
    }
}
