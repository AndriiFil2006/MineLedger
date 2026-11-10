package com.mineledger.accounting.mastery;

import java.util.List;

/** Concepts are required and ordered; completion unlocks the next concept. */
public record Assignment(String id, String unitId, String title, String description, Availability availability,
        List<AccountingConcept> concepts) {
    public enum Availability { AVAILABLE, COMING_SOON }

    /** Compatibility constructor for standalone exercises and existing domain tests. */
    public Assignment(String id, String title, List<AccountingConcept> concepts) {
        this(id, "standalone", title, title, Availability.AVAILABLE, concepts);
    }

    public boolean available() { return availability == Availability.AVAILABLE; }

    public Assignment {
        concepts = List.copyOf(concepts);
        if (id == null || id.isBlank() || unitId == null || unitId.isBlank() || title == null || title.isBlank()
                || description == null || description.isBlank() || availability == null
                || availability == Availability.AVAILABLE && concepts.isEmpty()
                || availability == Availability.COMING_SOON && !concepts.isEmpty()
                || concepts.stream().map(AccountingConcept::id).distinct().count() != concepts.size()) {
            throw new IllegalArgumentException("Assignment requires uniquely identified concepts");
        }
        var questions = concepts.stream().flatMap(concept -> concept.questions().stream()).toList();
        if (questions.stream().map(MultipleChoiceQuestion::id).distinct().count() != questions.size()) {
            throw new IllegalArgumentException("Question IDs must be unique across the assignment");
        }
    }
}
