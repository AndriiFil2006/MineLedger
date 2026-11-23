package com.mineledger.backend;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.Test;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineledger.accounting.mastery.Assignment.Availability;
import com.mineledger.backend.RemoteCurriculum.*;
import com.mineledger.curriculum.CurriculumContent;

public class BackendJsonTest {
    private static void assertMalformed(Runnable parse, String reason) {
        try {
            parse.run();
            fail("Accepted a malformed response");
        } catch (BackendException exception) {
            assertEquals(BackendException.Kind.MALFORMED_RESPONSE, exception.kind());
            assertTrue(exception.getMessage(), exception.getMessage().contains(reason));
        }
    }

    /** The fixture body with one edit applied to its parsed root. */
    private static String edited(String path, java.util.function.Consumer<JsonElement> edit) {
        JsonElement root = JsonParser.parseString(ApiFixtures.body(path));
        edit.accept(root);
        return root.toString();
    }

    @Test
    public void deserializesEveryRealBackendResponse() {
        assertTrue(BackendJson.health(ApiFixtures.body(ApiFixtures.HEALTH)).ok());

        List<CourseSummary> courses = BackendJson.courses(ApiFixtures.body(ApiFixtures.COURSES));
        assertEquals(1, courses.size());
        assertEquals("accounting101", courses.getFirst().id());
        assertEquals("Accounting 101 — Financial Accounting", courses.getFirst().title());
        assertEquals(List.of("accounting101.fundamentals", "accounting101.recording_transactions",
                "accounting101.adjusting_entries"), courses.getFirst().unitIds());

        CourseDetail course = BackendJson.course(ApiFixtures.body(ApiFixtures.COURSE));
        assertEquals(List.of(1, 2, 3), course.units().stream().map(UnitSummary::number).toList());
        assertEquals(List.of("inventory_fundamentals", "accounting101.posting_ledger", "accounting101.trial_balance"),
                course.units().get(1).assignmentIds());

        UnitDetail unit = BackendJson.unit(ApiFixtures.body(ApiFixtures.UNIT));
        assertEquals("accounting101", unit.courseId());
        assertEquals(2, unit.number());
        assertEquals(List.of(Availability.AVAILABLE, Availability.COMING_SOON, Availability.COMING_SOON),
                unit.assignments().stream().map(AssignmentSummary::availability).toList());
        assertEquals(List.of("inventory.purchases_on_credit", "payables.cash_payments"), unit.assignments().getFirst().conceptIds());
        assertTrue(unit.assignments().get(1).conceptIds().isEmpty());

        AssignmentDetail assignment = BackendJson.assignment(ApiFixtures.body(ApiFixtures.ASSIGNMENT));
        assertEquals("accounting101.recording_transactions", assignment.unitId());
        assertEquals(2, assignment.concepts().getFirst().requiredCorrectAnswers());
        assertEquals(3, assignment.concepts().getFirst().questionCount());
        assertEquals(Optional.of("lesson.inventory.purchases_on_credit.perpetual"), assignment.concepts().getFirst().lessonId());
        assertEquals(Optional.empty(), assignment.concepts().get(1).lessonId());

        ConceptDetail withLesson = BackendJson.concept(ApiFixtures.body(ApiFixtures.CONCEPT_1));
        assertEquals(List.of("inventory.shovels", "inventory.books_discount", "inventory.lanterns_delivery"), withLesson.questionIds());
        assertEquals("The API lesson is the bundled lesson, field for field",
                CurriculumContent.get().lessons().forConcept("inventory.purchases_on_credit").orElseThrow(), withLesson.lesson().orElseThrow());
        assertTrue(BackendJson.concept(ApiFixtures.body(ApiFixtures.CONCEPT_2)).lesson().isEmpty());

        AssignmentQuestions questions = BackendJson.questions(ApiFixtures.body(ApiFixtures.QUESTIONS));
        assertEquals("inventory_fundamentals", questions.assignmentId());
        assertEquals(6, questions.questions().size());
        Question first = questions.questions().getFirst();
        assertEquals("inventory.shovels", first.id());
        assertEquals("inventory.purchases_on_credit", first.conceptId());
        assertEquals(Optional.empty(), first.difficulty());
        assertEquals(List.of("shovels.cash", "shovels.inventory_payable", "shovels.expense", "shovels.reversed"),
                first.options().stream().map(option -> option.id()).toList());
    }

    @Test
    public void remoteQuestionsCannotCarryAnAnswerKey() {
        assertEquals(List.of("id", "conceptId", "prompt", "difficulty", "options"),
                Arrays.stream(Question.class.getRecordComponents()).map(component -> component.getName()).toList());
        // Even a misbehaving server cannot push a key or explanation into the typed model.
        String leaking = edited(ApiFixtures.QUESTIONS, root -> {
            for (JsonElement question : root.getAsJsonObject().getAsJsonArray("questions")) {
                question.getAsJsonObject().addProperty("correct_answer_id", "shovels.inventory_payable");
                question.getAsJsonObject().addProperty("explanation", "SECRET-EXPLANATION");
                for (JsonElement option : question.getAsJsonObject().getAsJsonArray("answer_options")) {
                    option.getAsJsonObject().addProperty("is_correct", true);
                }
            }
        });
        AssignmentQuestions parsed = BackendJson.questions(leaking);
        assertEquals(BackendJson.questions(ApiFixtures.body(ApiFixtures.QUESTIONS)), parsed);
        assertFalse(parsed.toString().contains("SECRET-EXPLANATION"));
        assertFalse(parsed.toString().contains("is_correct"));
    }

    @Test
    public void everyRequiredFieldIsRequiredInEveryResponse() {
        record Case(String path, Function<String, ?> parser) { }
        List<Case> cases = List.of(new Case(ApiFixtures.HEALTH, BackendJson::health), new Case(ApiFixtures.META, BackendJson::meta),
                new Case(ApiFixtures.COURSES, BackendJson::courses),
                new Case(ApiFixtures.COURSE, BackendJson::course), new Case(ApiFixtures.UNIT, BackendJson::unit),
                new Case(ApiFixtures.ASSIGNMENT, BackendJson::assignment), new Case(ApiFixtures.CONCEPT_1, BackendJson::concept),
                new Case(ApiFixtures.CONCEPT_2, BackendJson::concept), new Case(ApiFixtures.QUESTIONS, BackendJson::questions));
        int removed = 0;
        for (Case item : cases) {
            item.parser().apply(ApiFixtures.body(item.path())); // control: the intact body parses
            int fields = countFields(JsonParser.parseString(ApiFixtures.body(item.path())));
            for (int target = 0; target < fields; target++) {
                JsonElement root = JsonParser.parseString(ApiFixtures.body(item.path()));
                String name = removeField(root, new int[] {target});
                String body = root.toString();
                assertMalformed(() -> item.parser().apply(body), "\"" + name + "\"");
                removed++;
            }
        }
        assertTrue("Expected to exercise many fields, got " + removed, removed > 120);
    }

    private static int countFields(JsonElement element) {
        int count = 0;
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) count += 1 + countFields(entry.getValue());
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) count += countFields(item);
        }
        return count;
    }

    /** Removes the target-th field in document order and returns its name. */
    private static String removeField(JsonElement element, int[] target) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String key : List.copyOf(object.keySet())) {
                if (target[0]-- == 0) {
                    object.remove(key);
                    return key;
                }
                String found = removeField(object.get(key), target);
                if (found != null) return found;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                String found = removeField(item, target);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    public void rejectsBodiesThatAreNotTheExpectedJson() {
        for (String body : List.of("{", "<html>Bad Gateway</html>", "{\"id\": \"a\",}", "{'id': 'a'}", "NaN",
                ApiFixtures.body(ApiFixtures.COURSE) + "{}", ApiFixtures.body(ApiFixtures.COURSE).substring(0, 200))) {
            assertMalformed(() -> BackendJson.course(body), "not valid JSON");
        }
        assertMalformed(() -> BackendJson.course(""), "is empty");
        assertMalformed(() -> BackendJson.course("   "), "is empty");
        assertMalformed(() -> BackendJson.course("[]"), "must be an object");
        assertMalformed(() -> BackendJson.course("null"), "must be an object");
        assertMalformed(() -> BackendJson.course("\"accounting101\""), "must be an object");
        assertMalformed(() -> BackendJson.courses("{}"), "must be a list");
        assertMalformed(() -> BackendJson.courses("[null]"), "must be an object");
        assertMalformed(() -> BackendJson.courses(ApiFixtures.body(ApiFixtures.COURSE)), "must be a list");
        assertMalformed(() -> BackendJson.questions(ApiFixtures.body(ApiFixtures.ASSIGNMENT)), "\"questions\"");
        assertTrue(BackendJson.courses("[]").isEmpty());
    }

    @Test
    public void rejectsWrongTypesBlankIdsAndUnsupportedValues() {
        assertMalformed(() -> BackendJson.course(edited(ApiFixtures.COURSE, root -> root.getAsJsonObject().addProperty("id", 7))), "\"id\"");
        assertMalformed(() -> BackendJson.course(edited(ApiFixtures.COURSE, root -> root.getAsJsonObject().addProperty("id", "  "))), "\"id\"");
        assertMalformed(() -> BackendJson.course(edited(ApiFixtures.COURSE, root -> root.getAsJsonObject().add("title", null))), "\"title\"");
        assertMalformed(() -> BackendJson.course(edited(ApiFixtures.COURSE, root -> root.getAsJsonObject().addProperty("units", "none"))), "must be a list");
        assertMalformed(() -> BackendJson.course(edited(ApiFixtures.COURSE, root -> unit(root, 0).addProperty("number", 0))), "at least 1");
        assertMalformed(() -> BackendJson.course(edited(ApiFixtures.COURSE, root -> unit(root, 0).addProperty("number", 1.5))), "whole number");
        assertMalformed(() -> BackendJson.course(edited(ApiFixtures.COURSE, root -> unit(root, 0).addProperty("number", "1"))), "whole number");
        assertMalformed(() -> BackendJson.course(edited(ApiFixtures.COURSE, root -> unit(root, 1).addProperty("id", "accounting101.fundamentals"))),
                "duplicate unit ID");
        assertMalformed(() -> BackendJson.course(edited(ApiFixtures.COURSE, root -> unit(root, 0).getAsJsonArray("assignment_ids").add(3))),
                "\"assignment_ids\"");
        assertMalformed(() -> BackendJson.unit(edited(ApiFixtures.UNIT, root -> root.getAsJsonObject().getAsJsonArray("assignments")
                .get(0).getAsJsonObject().addProperty("availability", "retired"))), "availability");
        assertMalformed(() -> BackendJson.assignment(edited(ApiFixtures.ASSIGNMENT, root -> root.getAsJsonObject().getAsJsonArray("concepts")
                .get(0).getAsJsonObject().addProperty("question_count", -1))), "\"question_count\"");
        assertMalformed(() -> BackendJson.questions(edited(ApiFixtures.QUESTIONS, root -> question(root).addProperty("type", "journal_entry"))),
                "unsupported type");
        assertMalformed(() -> BackendJson.questions(edited(ApiFixtures.QUESTIONS, root -> question(root).add("answer_options", new com.google.gson.JsonArray()))),
                "no answer options");
        assertMalformed(() -> BackendJson.questions(edited(ApiFixtures.QUESTIONS, root -> question(root).getAsJsonArray("answer_options")
                .get(1).getAsJsonObject().addProperty("id", "shovels.cash"))), "duplicate option");
        assertMalformed(() -> BackendJson.questions(edited(ApiFixtures.QUESTIONS, root -> question(root).getAsJsonArray("answer_options")
                .get(0).getAsJsonObject().addProperty("text", ""))), "\"text\"");
        assertMalformed(() -> BackendJson.questions(edited(ApiFixtures.QUESTIONS, root -> root.getAsJsonObject().getAsJsonArray("questions")
                .get(1).getAsJsonObject().addProperty("id", "inventory.shovels"))), "duplicate question ID");
        assertMalformed(() -> BackendJson.concept(edited(ApiFixtures.CONCEPT_1, root -> root.getAsJsonObject().getAsJsonObject("lesson")
                .add("worked_examples", new com.google.gson.JsonArray()))), "invalid content");
        assertMalformed(() -> BackendJson.concept(edited(ApiFixtures.CONCEPT_1, root -> root.getAsJsonObject().addProperty("lesson", "none"))),
                "must be an object");
    }

    private static JsonObject unit(JsonElement root, int index) {
        return root.getAsJsonObject().getAsJsonArray("units").get(index).getAsJsonObject();
    }

    private static JsonObject question(JsonElement root) {
        return root.getAsJsonObject().getAsJsonArray("questions").get(0).getAsJsonObject();
    }

    @Test
    public void serverDescriptionIsParsedStrictly() {
        assertEquals(new Meta(1, List.of(1), 1), BackendJson.meta(ApiFixtures.body(ApiFixtures.META)));
        assertEquals(new Meta(7, List.of(), 0), BackendJson.meta("{\"api_version\":7,\"curriculum_schema_versions\":[],\"course_count\":0}"));
        assertMalformed(() -> BackendJson.meta("{\"api_version\":\"1\",\"curriculum_schema_versions\":[1],\"course_count\":1}"), "\"api_version\"");
        assertMalformed(() -> BackendJson.meta("{\"api_version\":1,\"curriculum_schema_versions\":[\"1\"],\"course_count\":1}"), "\"curriculum_schema_versions\"");
        assertMalformed(() -> BackendJson.meta("{\"api_version\":1,\"curriculum_schema_versions\":1,\"course_count\":1}"), "must be a list");
        assertMalformed(() -> BackendJson.meta("{\"api_version\":1,\"curriculum_schema_versions\":[1],\"course_count\":-1}"), "\"course_count\"");
        assertMalformed(() -> BackendJson.meta(ApiFixtures.body(ApiFixtures.HEALTH)), "missing field");
    }

    @Test
    public void errorDetailIsExtractedDefensively() {
        assertEquals(Optional.of("Course not found: nope"), BackendJson.errorDetail("{\"detail\":\"Course not found: nope\"}"));
        assertEquals(Optional.empty(), BackendJson.errorDetail("Internal Server Error"));
        assertEquals(Optional.empty(), BackendJson.errorDetail(""));
        assertEquals(Optional.empty(), BackendJson.errorDetail("{\"detail\":[{\"msg\":\"x\"}]}"));
        assertEquals(Optional.empty(), BackendJson.errorDetail("[1]"));
        assertEquals(163, BackendJson.errorDetail("{\"detail\":\"" + "x".repeat(5000) + "\"}").orElseThrow().length());
    }
}
