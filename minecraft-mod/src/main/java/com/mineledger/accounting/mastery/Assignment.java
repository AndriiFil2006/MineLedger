package com.mineledger.accounting.mastery;

import java.util.List;

/** Concepts are required and ordered; completion unlocks the next concept. */
public record Assignment(String id, String title, List<AccountingConcept> concepts) {
    public Assignment {
        concepts = List.copyOf(concepts);
        if (id == null || id.isBlank() || title == null || title.isBlank() || concepts.isEmpty()
                || concepts.stream().map(AccountingConcept::id).distinct().count() != concepts.size()) {
            throw new IllegalArgumentException("Assignment requires uniquely identified concepts");
        }
        var questions = concepts.stream().flatMap(concept -> concept.questions().stream()).toList();
        if (questions.stream().map(MultipleChoiceQuestion::id).distinct().count() != questions.size()) {
            throw new IllegalArgumentException("Question IDs must be unique across the assignment");
        }
    }
}
