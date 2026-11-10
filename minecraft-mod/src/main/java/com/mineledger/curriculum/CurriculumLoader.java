package com.mineledger.curriculum;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mineledger.accounting.Account;
import com.mineledger.accounting.AccountingQuestion;
import com.mineledger.accounting.mastery.*;
import com.mineledger.education.*;
import com.mineledger.education.lesson.*;

/** Resolves schema-1 tables into existing domain models, rejecting malformed or orphaned content. */
public final class CurriculumLoader {
    public static final int SCHEMA_VERSION = 1;

    public Curriculum load(Reader input, String source) {
        try (JsonReader reader = new JsonReader(input)) {
            reader.setStrictness(Strictness.STRICT);
            JsonObject root = read(reader, 0).getAsJsonObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw bad("Trailing JSON content");
            return resolve(root);
        } catch (IOException | RuntimeException exception) {
            throw new CurriculumValidationException(source + ": " + exception.getMessage(), exception);
        }
    }

    private Curriculum resolve(JsonObject root) {
        if (integer(root, "schema_version") != SCHEMA_VERSION) throw bad("Unsupported curriculum schema_version; expected 1");
        keys(root, "schema_version", "courses", "units", "assignments", "concepts", "questions", "lessons", "labs");
        var courseData = index(root, "courses");
        var unitData = index(root, "units");
        var assignmentData = index(root, "assignments");
        var conceptData = index(root, "concepts");
        var questionData = index(root, "questions");
        var lessonData = index(root, "lessons");
        var labData = index(root, "labs");
        var allIds = new HashSet<String>();
        for (var table : List.of(courseData, unitData, assignmentData, conceptData, questionData, lessonData, labData)) {
            for (String id : table.keySet()) if (!allIds.add(id)) throw bad("Duplicate entity ID across tables: " + id);
        }
        if (courseData.isEmpty()) throw bad("Curriculum requires at least one course");

        var questions = new LinkedHashMap<String, MultipleChoiceQuestion>();
        var journals = new LinkedHashMap<String, AccountingQuestion>();
        var difficulties = new HashMap<String, String>();
        for (var entry : questionData.entrySet()) {
            String id = entry.getKey(); var q = entry.getValue();
            if (q.has("difficulty")) difficulties.put(id, text(q, "difficulty"));
            switch (text(q, "type")) {
                case "multiple_choice" -> {
                    keys(q, "id", "concept_id", "type", "prompt", "answer_options", "correct_answer_id", "explanation", "difficulty");
                    List<AnswerOption> options = new ArrayList<>();
                    for (var item : array(q, "answer_options")) {
                        var option = item.getAsJsonObject(); keys(option, "id", "text");
                        options.add(new AnswerOption(text(option, "id"), text(option, "text")));
                    }
                    questions.put(id, checked("question " + id, () -> new MultipleChoiceQuestion(id, text(q, "concept_id"),
                            text(q, "prompt"), options, text(q, "correct_answer_id"), text(q, "explanation"))));
                }
                case "journal_entry" -> {
                    keys(q, "id", "lab_id", "type", "prompt", "debit_accounts", "credit_accounts", "correct_debit_account",
                            "correct_credit_account", "correct_amount", "explanation", "difficulty");
                    text(q, "lab_id");
                    journals.put(id, checked("question " + id, () -> new AccountingQuestion(id, text(q, "prompt"),
                            accounts(q, "debit_accounts"), accounts(q, "credit_accounts"), Account.valueOf(text(q, "correct_debit_account")),
                            Account.valueOf(text(q, "correct_credit_account")), new BigDecimal(text(q, "correct_amount")), text(q, "explanation"))));
                }
                default -> throw bad("question " + id + ": unsupported type " + text(q, "type"));
            }
        }

        var lessons = new LinkedHashMap<String, ConceptLesson>();
        for (var entry : lessonData.entrySet()) {
            String id = entry.getKey(); var l = entry.getValue();
            keys(l, "id", "concept_id", "title", "learning_objective", "explanation", "accounting_rules", "worked_examples", "common_mistakes", "takeaway");
            var examples = new ArrayList<ConceptLesson.WorkedExample>();
            for (var item : array(l, "worked_examples")) {
                var e = item.getAsJsonObject(); keys(e, "title", "scenario", "calculation", "debit_entry", "credit_entry", "explanation");
                examples.add(new ConceptLesson.WorkedExample(text(e, "title"), text(e, "scenario"), text(e, "calculation"),
                        text(e, "debit_entry"), text(e, "credit_entry"), text(e, "explanation")));
            }
            lessons.put(id, checked("lesson " + id, () -> new ConceptLesson(id, text(l, "concept_id"), text(l, "title"),
                    text(l, "learning_objective"), text(l, "explanation"), strings(l, "accounting_rules"), examples,
                    l.has("common_mistakes") ? strings(l, "common_mistakes") : List.of(), optionalText(l, "takeaway", ""))));
        }

        var usedQuestions = new HashSet<String>(); var usedLessons = new HashSet<String>();
        var concepts = new LinkedHashMap<String, AccountingConcept>();
        for (var entry : conceptData.entrySet()) {
            String id = entry.getKey(); var c = entry.getValue();
            keys(c, "id", "assignment_id", "title", "description", "required_correct_answers", "lesson_id", "question_ids");
            text(c, "assignment_id");
            var bank = references(c, "question_ids", questions, usedQuestions);
            int required = integer(c, "required_correct_answers");
            if (required < 1 || bank.size() < required) throw bad("concept " + id + ": insufficient distinct question bank for " + required + " required correct answers");
            for (var question : bank) if (!question.conceptId().equals(id)) throw bad("question " + question.id() + ": concept_id does not match " + id);
            if (c.has("lesson_id") && !c.get("lesson_id").isJsonNull()) {
                String lessonId = text(c, "lesson_id");
                ConceptLesson lesson = require(lessons, lessonId, "concept " + id + " lesson_id");
                if (!lesson.conceptId().equals(id) || !usedLessons.add(lessonId)) throw bad("concept " + id + ": incorrect or reused lesson reference " + lessonId);
            }
            concepts.put(id, checked("concept " + id, () -> new AccountingConcept(id, text(c, "title"), text(c, "description"), required, bank)));
        }

        var usedConcepts = new HashSet<String>();
        var assignments = new LinkedHashMap<String, Assignment>();
        for (var entry : assignmentData.entrySet()) {
            String id = entry.getKey(); var a = entry.getValue();
            keys(a, "id", "parent_unit_id", "title", "description", "availability", "concept_ids");
            var members = references(a, "concept_ids", concepts, usedConcepts);
            for (var concept : members) if (!text(conceptData.get(concept.id()), "assignment_id").equals(id)) {
                throw bad("concept " + concept.id() + ": assignment_id does not match " + id);
            }
            Assignment.Availability availability = switch (text(a, "availability")) {
                case "available" -> Assignment.Availability.AVAILABLE;
                case "coming_soon" -> Assignment.Availability.COMING_SOON;
                default -> throw bad("assignment " + id + ": unsupported availability");
            };
            assignments.put(id, checked("assignment " + id, () -> new Assignment(id, text(a, "parent_unit_id"), text(a, "title"),
                    text(a, "description"), availability, members)));
        }

        var usedAssignments = new HashSet<String>(); var units = new LinkedHashMap<String, Unit>();
        for (var entry : unitData.entrySet()) {
            String id = entry.getKey(); var u = entry.getValue();
            keys(u, "id", "parent_course_id", "order", "title", "description", "assignment_ids");
            var members = references(u, "assignment_ids", assignments, usedAssignments);
            if (members.isEmpty()) throw bad("unit " + id + ": assignment_ids is empty");
            units.put(id, checked("unit " + id, () -> new Unit(id, text(u, "parent_course_id"), integer(u, "order"),
                    text(u, "title"), text(u, "description"), members)));
        }

        var usedUnits = new HashSet<String>(); var courses = new ArrayList<Course>();
        for (var entry : courseData.entrySet()) {
            String id = entry.getKey(); var c = entry.getValue();
            keys(c, "id", "title", "description", "unit_ids");
            var members = references(c, "unit_ids", units, usedUnits);
            if (members.isEmpty()) throw bad("course " + id + ": unit_ids is empty");
            courses.add(checked("course " + id, () -> new Course(id, text(c, "title"), text(c, "description"), members)));
        }

        var labs = new LinkedHashMap<String, Curriculum.JournalLab>();
        for (var entry : labData.entrySet()) {
            String id = entry.getKey(); var l = entry.getValue(); keys(l, "id", "title", "question_ids");
            var bank = references(l, "question_ids", journals, usedQuestions);
            if (bank.isEmpty()) throw bad("lab " + id + ": question_ids is empty");
            for (var question : bank) if (!text(questionData.get(question.id()), "lab_id").equals(id)) throw bad("question " + question.id() + ": lab_id mismatch");
            labs.put(id, new Curriculum.JournalLab(id, text(l, "title"), bank));
        }
        allUsed("unit", unitData, usedUnits); allUsed("assignment", assignmentData, usedAssignments);
        allUsed("concept", conceptData, usedConcepts); allUsed("question", questionData, usedQuestions); allUsed("lesson", lessonData, usedLessons);
        return new Curriculum(SCHEMA_VERSION, new CourseCatalog(courses), new ConceptLessonCatalog(List.copyOf(lessons.values())),
                concepts, questions, labs, difficulties);
    }

    private static <T> T checked(String context, Supplier<T> construct) {
        try { return construct.get(); }
        catch (RuntimeException exception) { throw bad(context + ": " + exception.getMessage()); }
    }
    private static <T> T require(Map<String, T> values, String id, String context) {
        T value = values.get(id); if (value == null) throw bad(context + ": missing reference " + id); return value;
    }
    private static <T> List<T> references(JsonObject owner, String field, Map<String, T> values, Set<String> used) {
        var result = new ArrayList<T>();
        for (String id : strings(owner, field)) {
            T value = require(values, id, label(owner) + "." + field);
            if (!used.add(id)) throw bad(label(owner) + ": duplicate or multiply-owned reference " + id);
            result.add(value);
        }
        return List.copyOf(result);
    }
    private static void allUsed(String type, Map<String, ?> values, Set<String> used) {
        var orphaned = new TreeSet<>(values.keySet()); orphaned.removeAll(used);
        if (!orphaned.isEmpty()) throw bad("Unreferenced " + type + " IDs: " + orphaned);
    }
    private static Map<String, JsonObject> index(JsonObject root, String table) {
        var result = new LinkedHashMap<String, JsonObject>();
        for (JsonElement element : array(root, table)) {
            JsonObject object = element.getAsJsonObject(); String id = text(object, "id");
            if (result.putIfAbsent(id, object) != null) throw bad(table + ": duplicate ID " + id);
        }
        return result;
    }
    private static String label(JsonObject object) { return object.has("id") ? object.get("id").toString() : "root"; }
    private static JsonElement field(JsonObject object, String name) {
        if (!object.has(name)) throw bad(label(object) + ": missing required field " + name);
        return object.get(name);
    }
    private static JsonArray array(JsonObject object, String name) { return field(object, name).getAsJsonArray(); }
    private static String text(JsonObject object, String name) {
        JsonElement value = field(object, name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            throw bad(label(object) + ": " + name + " must be nonblank text");
        }
        return value.getAsString();
    }
    private static String optionalText(JsonObject object, String name, String fallback) {
        if (!object.has(name)) return fallback;
        JsonElement value = field(object, name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw bad(label(object) + ": " + name + " must be text");
        return value.getAsString();
    }
    private static int integer(JsonObject object, String name) {
        JsonElement value = field(object, name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw bad(label(object) + ": " + name + " must be an integer");
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException exception) { throw bad(label(object) + ": " + name + " must be an integer"); }
    }
    private static List<String> strings(JsonObject object, String name) {
        var result = new ArrayList<String>();
        for (var value : array(object, name)) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) throw bad(label(object) + ": invalid text in " + name);
            result.add(value.getAsString());
        }
        return List.copyOf(result);
    }
    private static List<Account> accounts(JsonObject object, String name) {
        var ids = strings(object, name);
        if (new HashSet<>(ids).size() != ids.size()) throw bad(label(object) + ": duplicate account in " + name);
        return ids.stream().map(Account::valueOf).toList();
    }
    private static void keys(JsonObject object, String... allowed) {
        Set<String> fields = Set.of(allowed);
        for (String name : object.keySet()) if (!fields.contains(name)) throw bad(label(object) + ": unknown field " + name);
    }
    private static CurriculumValidationException bad(String message) { return new CurriculumValidationException(message); }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 32) throw bad("JSON nesting exceeds 32 levels");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName(); if (object.has(name)) throw bad("Duplicate JSON field " + name);
                    object.add(name, read(reader, depth + 1));
                }
                reader.endObject(); return object;
            }
            case BEGIN_ARRAY -> {
                var array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1));
                reader.endArray(); return array;
            }
            case STRING -> { return new JsonPrimitive(reader.nextString()); }
            case NUMBER -> { return new JsonPrimitive(new BigDecimal(reader.nextString())); }
            case BOOLEAN -> { return new JsonPrimitive(reader.nextBoolean()); }
            case NULL -> { reader.nextNull(); return JsonNull.INSTANCE; }
            default -> throw bad("Unexpected JSON token " + reader.peek());
        }
    }
}
