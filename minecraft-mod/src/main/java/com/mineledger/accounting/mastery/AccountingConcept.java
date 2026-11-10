package com.mineledger.accounting.mastery;

import java.util.List;

public record AccountingConcept(String id, String title, String description, int requiredCorrectAnswers,
        List<MultipleChoiceQuestion> questions) {
    public AccountingConcept(String id, String title, String description, List<MultipleChoiceQuestion> questions) {
        this(id, title, description, 2, questions);
    }

    public AccountingConcept {
        questions = List.copyOf(questions);
        if (id == null || id.isBlank() || title == null || title.isBlank()
                || description == null || description.isBlank() || requiredCorrectAnswers < 1
                || questions.stream().anyMatch(question -> !question.conceptId().equals(id))
                || questions.stream().map(MultipleChoiceQuestion::id).distinct().count() != questions.size()) {
            throw new IllegalArgumentException("Invalid concept or question bank");
        }
        // A short bank is allowed so selection can explicitly report exhausted content.
    }
}
