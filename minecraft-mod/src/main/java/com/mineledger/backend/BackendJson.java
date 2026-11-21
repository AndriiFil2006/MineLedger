package com.mineledger.backend;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mineledger.accounting.mastery.AnswerOption;
import com.mineledger.accounting.mastery.Assignment.Availability;
import com.mineledger.backend.RemoteCurriculum.*;
import com.mineledger.education.lesson.ConceptLesson;

/**
 * Strict mapping from backend response bodies to {@link RemoteCurriculum} records. Required fields must be
 * present with the right type; nothing is defaulted or invented. Fields this client does not know are ignored,
 * so an answer key could never be carried into a typed object even if a server sent one.
 * Every failure is a {@link BackendException.Kind#MALFORMED_RESPONSE}.
 */
public final class BackendJson {
    public static Health health(String body) { return parse(body, "health", root -> {
        JsonObject o = object(root, "health");
        return new Health(text(o, "status"), text(o, "database"));
    }); }

    public static List<CourseSummary> courses(String body) { return parse(body, "course list", root -> {
        List<CourseSummary> courses = new ArrayList<>();
        for (JsonObject o : objects(root, "course list")) {
            courses.add(new CourseSummary(text(o, "id"), text(o, "title"), text(o, "description"), ids(o, "unit_ids")));
        }
        unique(courses.stream().map(CourseSummary::id).toList(), "course");
        return List.copyOf(courses);
    }); }

    public static CourseDetail course(String body) { return parse(body, "course", root -> {
        JsonObject o = object(root, "course");
        List<UnitSummary> units = new ArrayList<>();
        for (JsonObject u : objects(field(o, "units"), "units")) {
            units.add(new UnitSummary(text(u, "id"), positive(u, "number"), text(u, "title"), text(u, "description"),
                    ids(u, "assignment_ids")));
        }
        unique(units.stream().map(UnitSummary::id).toList(), "unit");
        return new CourseDetail(text(o, "id"), text(o, "title"), text(o, "description"), units);
    }); }

    public static UnitDetail unit(String body) { return parse(body, "unit", root -> {
        JsonObject o = object(root, "unit");
        List<AssignmentSummary> assignments = new ArrayList<>();
        for (JsonObject a : objects(field(o, "assignments"), "assignments")) {
            assignments.add(new AssignmentSummary(text(a, "id"), text(a, "title"), text(a, "description"),
                    availability(a), ids(a, "concept_ids")));
        }
        unique(assignments.stream().map(AssignmentSummary::id).toList(), "assignment");
        return new UnitDetail(text(o, "id"), text(o, "course_id"), positive(o, "number"), text(o, "title"),
                text(o, "description"), assignments);
    }); }

    public static AssignmentDetail assignment(String body) { return parse(body, "assignment", root -> {
        JsonObject o = object(root, "assignment");
        List<ConceptSummary> concepts = new ArrayList<>();
        for (JsonObject c : objects(field(o, "concepts"), "concepts")) {
            concepts.add(new ConceptSummary(text(c, "id"), text(c, "title"), text(c, "description"),
                    positive(c, "required_correct_answers"), integer(c, "question_count"), nullableText(c, "lesson_id")));
        }
        unique(concepts.stream().map(ConceptSummary::id).toList(), "concept");
        return new AssignmentDetail(text(o, "id"), text(o, "unit_id"), text(o, "title"), text(o, "description"),
                availability(o), concepts);
    }); }

    public static ConceptDetail concept(String body) { return parse(body, "concept", root -> {
        JsonObject o = object(root, "concept");
        String id = text(o, "id");
        JsonElement lesson = field(o, "lesson");
        return new ConceptDetail(id, text(o, "assignment_id"), text(o, "title"), text(o, "description"),
                positive(o, "required_correct_answers"), ids(o, "question_ids"),
                lesson.isJsonNull() ? Optional.empty() : Optional.of(lesson(object(lesson, "lesson"), id)));
    }); }

    public static AssignmentQuestions questions(String body) { return parse(body, "assignment questions", root -> {
        JsonObject o = object(root, "assignment questions");
        List<Question> questions = new ArrayList<>();
        for (JsonObject q : objects(field(o, "questions"), "questions")) {
            String id = text(q, "id");
            if (!text(q, "type").equals("multiple_choice")) throw bad("question " + id + " has an unsupported type");
            List<AnswerOption> options = new ArrayList<>();
            for (JsonObject option : objects(field(q, "answer_options"), "answer_options")) {
                options.add(new AnswerOption(text(option, "id"), text(option, "text")));
            }
            if (options.isEmpty()) throw bad("question " + id + " has no answer options");
            unique(options.stream().map(AnswerOption::id).toList(), "option of question " + id);
            questions.add(new Question(id, text(q, "concept_id"), text(q, "prompt"), nullableText(q, "difficulty"), options));
        }
        unique(questions.stream().map(Question::id).toList(), "question");
        return new AssignmentQuestions(text(o, "assignment_id"), questions);
    }); }

    /** FastAPI's {@code {"detail": "..."}} error text, if this body has one. Never throws. */
    public static Optional<String> errorDetail(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            JsonElement detail = root.isJsonObject() ? root.getAsJsonObject().get("detail") : null;
            if (detail == null || !detail.isJsonPrimitive() || !detail.getAsJsonPrimitive().isString()) return Optional.empty();
            String text = detail.getAsString().strip();
            return text.isEmpty() ? Optional.empty() : Optional.of(text.length() > 160 ? text.substring(0, 160) + "..." : text);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static ConceptLesson lesson(JsonObject o, String conceptId) {
        List<ConceptLesson.WorkedExample> examples = new ArrayList<>();
        for (JsonObject e : objects(field(o, "worked_examples"), "worked_examples")) {
            examples.add(new ConceptLesson.WorkedExample(text(e, "title"), text(e, "scenario"), text(e, "calculation"),
                    text(e, "debit_entry"), text(e, "credit_entry"), text(e, "explanation")));
        }
        JsonElement takeaway = field(o, "takeaway");
        if (!isString(takeaway)) throw bad("\"takeaway\" must be a string");
        return new ConceptLesson(text(o, "id"), conceptId, text(o, "title"), text(o, "learning_objective"),
                text(o, "explanation"), ids(o, "accounting_rules"), examples, ids(o, "common_mistakes"), takeaway.getAsString());
    }

    private static <T> T parse(String body, String what, Function<JsonElement, T> mapper) {
        if (body.isBlank()) throw BackendException.malformed("the " + what + " is empty");
        JsonElement root;
        try (JsonReader reader = new JsonReader(new StringReader(body))) {
            reader.setStrictness(Strictness.STRICT);
            root = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new JsonParseException("trailing content");
        } catch (IOException | JsonParseException | IllegalStateException exception) {
            throw BackendException.malformed("the " + what + " is not valid JSON");
        }
        try {
            return mapper.apply(root);
        } catch (BackendException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Domain constructors (AnswerOption, ConceptLesson) reject blank or missing content.
            throw BackendException.malformed("the " + what + " has invalid content (" + exception.getMessage() + ")");
        }
    }

    private static BackendException bad(String message) { return BackendException.malformed(message); }

    private static JsonObject object(JsonElement element, String what) {
        if (element == null || !element.isJsonObject()) throw bad("the " + what + " must be an object");
        return element.getAsJsonObject();
    }

    private static List<JsonObject> objects(JsonElement element, String what) {
        if (element == null || !element.isJsonArray()) throw bad("the " + what + " must be a list");
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) result.add(object(item, "entry in " + what));
        return result;
    }

    private static JsonElement field(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null) throw bad("missing field \"" + key + "\"");
        return value;
    }

    private static boolean isString(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = field(object, key);
        if (!isString(value) || value.getAsString().isBlank()) throw bad("\"" + key + "\" must be non-blank text");
        return value.getAsString();
    }

    private static Optional<String> nullableText(JsonObject object, String key) {
        return field(object, key).isJsonNull() ? Optional.empty() : Optional.of(text(object, key));
    }

    private static int integer(JsonObject object, String key) {
        JsonElement value = field(object, key);
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            int number = new BigDecimal(value.getAsString()).intValueExact();
            if (number < 0) throw new ArithmeticException();
            return number;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw bad("\"" + key + "\" must be a non-negative whole number");
        }
    }

    private static int positive(JsonObject object, String key) {
        int number = integer(object, key);
        if (number < 1) throw bad("\"" + key + "\" must be at least 1");
        return number;
    }

    private static Availability availability(JsonObject object) {
        return switch (text(object, "availability")) {
            case "available" -> Availability.AVAILABLE;
            case "coming_soon" -> Availability.COMING_SOON;
            default -> throw bad("\"availability\" must be available or coming_soon");
        };
    }

    private static List<String> ids(JsonObject object, String key) {
        JsonElement value = field(object, key);
        if (!value.isJsonArray()) throw bad("\"" + key + "\" must be a list");
        List<String> result = new ArrayList<>();
        JsonArray array = value.getAsJsonArray();
        for (JsonElement item : array) {
            if (!isString(item) || item.getAsString().isBlank()) throw bad("\"" + key + "\" must contain non-blank text");
            result.add(item.getAsString());
        }
        return result;
    }

    private static void unique(List<String> ids, String what) {
        if (new HashSet<>(ids).size() != ids.size()) throw bad("duplicate " + what + " ID");
    }

    private BackendJson() { }
}
