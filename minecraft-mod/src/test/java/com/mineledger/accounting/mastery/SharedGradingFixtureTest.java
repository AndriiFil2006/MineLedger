package com.mineledger.accounting.mastery;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineledger.accounting.mastery.GradingResult.Status;
import com.mineledger.curriculum.CurriculumContent;

/** Runs ../shared/fixtures/multiple_choice_grading.json; the backend's Python grader runs the same cases. */
public class SharedGradingFixtureTest {
    static final Path FIXTURE = Path.of("..", "shared", "fixtures", "multiple_choice_grading.json");
    private static final String INVALID = "Select one of this question's four answers before submitting.";

    private static JsonObject fixture() throws IOException {
        return JsonParser.parseString(Files.readString(FIXTURE, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** Every fixture case the given grader gets wrong (empty means it agrees with the fixture). */
    static List<String> mismatches(BiFunction<MultipleChoiceQuestion, String, GradingResult> grader) throws IOException {
        JsonObject fixture = fixture();
        String invalidMessage = fixture.getAsJsonObject("messages").get("invalid_option").getAsString();
        var questions = CurriculumContent.get().questions();
        var result = new ArrayList<String>();
        for (var element : fixture.getAsJsonArray("cases")) {
            JsonObject item = element.getAsJsonObject();
            String name = item.get("name").getAsString();
            MultipleChoiceQuestion question = questions.get(item.get("question_id").getAsString());
            if (question == null) { result.add(name + ": unknown question"); continue; }
            String selected = item.get("selected_option_id").isJsonNull() ? null : item.get("selected_option_id").getAsString();
            GradingResult actual = grader.apply(question, selected);
            Status expected = Status.valueOf(item.get("expected_status").getAsString().toUpperCase(Locale.ROOT));
            String feedback = item.get("expected_feedback").getAsString().equals("question_explanation") ? question.explanation() : invalidMessage;
            if (actual.status() != expected || !actual.explanation().equals(feedback)) {
                result.add(name + ": expected " + expected + " but got " + actual.status());
            }
        }
        return result;
    }

    @Test
    public void javaGraderMatchesSharedFixture() throws IOException {
        JsonObject fixture = fixture();
        assertEquals(1, fixture.get("fixture_version").getAsInt());
        var grader = new MultipleChoiceGrader();
        assertEquals(List.of(), mismatches(grader::grade));

        var graded = new HashSet<String>();
        for (var element : fixture.getAsJsonArray("cases")) {
            JsonObject item = element.getAsJsonObject();
            if (!item.get("expected_status").getAsString().equals("invalid_option")) {
                graded.add(item.get("question_id").getAsString() + "/" + item.get("selected_option_id").getAsString());
            }
        }
        int options = CurriculumContent.get().questions().values().stream().mapToInt(question -> question.options().size()).sum();
        assertEquals("every bundled answer option must have a valid-selection case", options, graded.size());
    }

    /** Deliberately broken graders: the fixture must catch every one, proving it is not a rubber stamp. */
    @Test
    public void fixtureCatchesBrokenGraders() throws IOException {
        Map<String, BiFunction<MultipleChoiceQuestion, String, GradingResult>> mutants = Map.of(
                "case-insensitive IDs", (q, s) -> s != null && q.options().stream().anyMatch(o -> o.id().equalsIgnoreCase(s))
                        ? new GradingResult(q.correctOptionId().equalsIgnoreCase(s) ? Status.CORRECT : Status.INCORRECT, q.explanation())
                        : new GradingResult(Status.INVALID_OPTION, INVALID),
                "trims whitespace", (q, s) -> new MultipleChoiceGrader().grade(q, s == null ? null : s.strip()),
                "unknown option counts as incorrect", (q, s) -> s == null ? new GradingResult(Status.INVALID_OPTION, INVALID)
                        : new GradingResult(q.correctOptionId().equals(s) ? Status.CORRECT : Status.INCORRECT, q.explanation()),
                "explanation leaked on invalid", (q, s) -> {
                    GradingResult real = new MultipleChoiceGrader().grade(q, s);
                    return real.valid() ? real : new GradingResult(Status.INVALID_OPTION, q.explanation());
                },
                "first option is always correct", (q, s) -> {
                    GradingResult real = new MultipleChoiceGrader().grade(q, s);
                    return !real.valid() ? real : new GradingResult(q.options().getFirst().id().equals(s) ? Status.CORRECT : Status.INCORRECT, q.explanation());
                },
                "matches by answer text", (q, s) -> q.options().stream().filter(o -> o.text().equals(s)).findFirst()
                        .map(o -> new GradingResult(o.id().equals(q.correctOptionId()) ? Status.CORRECT : Status.INCORRECT, q.explanation()))
                        .orElseGet(() -> new MultipleChoiceGrader().grade(q, s)));
        for (var mutant : mutants.entrySet()) {
            assertFalse("fixture failed to detect broken grader: " + mutant.getKey(), mismatches(mutant.getValue()).isEmpty());
        }
    }
}
