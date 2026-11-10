package com.mineledger.accounting.mastery;

import java.util.HashMap;
import java.util.Map;

/** Reopening an assignment returns the same session service, independent of screen lifetimes. */
public final class LearningSession {
    private final Map<String, ConceptProgressionService> assignments = new HashMap<>();

    public ConceptProgressionService open(Assignment assignment) {
        ConceptProgressionService service = assignments.computeIfAbsent(assignment.id(), ignored -> new ConceptProgressionService(assignment));
        if (!service.assignment().equals(assignment)) throw new IllegalArgumentException("Assignment ID reused with different content");
        return service;
    }
}
