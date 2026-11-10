package com.mineledger.education.lesson;

import java.util.List;

/** Immutable content DTO, independent of screens, grading and storage. */
public record ConceptLesson(String id, String conceptId, String title, String learningObjective,
        String explanation, List<String> keyRules, List<WorkedExample> workedExamples,
        List<String> commonMistakes, String takeaway) {
    public record WorkedExample(String title, String scenario, String calculation, String debitEntry,
            String creditEntry, String explanation) {
        public WorkedExample {
            requireText(title, scenario, calculation, debitEntry, creditEntry, explanation);
        }
    }

    public ConceptLesson {
        requireText(id, conceptId, title, learningObjective, explanation);
        keyRules = List.copyOf(keyRules);
        workedExamples = List.copyOf(workedExamples);
        commonMistakes = List.copyOf(commonMistakes);
        keyRules.forEach(rule -> requireText(rule));
        commonMistakes.forEach(mistake -> requireText(mistake));
        if (keyRules.isEmpty() || workedExamples.isEmpty() || takeaway == null) {
            throw new IllegalArgumentException("A lesson needs key rules, a worked example and a non-null optional takeaway");
        }
    }

    private static void requireText(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Lesson text and identifiers must not be blank");
        }
    }
}
