package com.mineledger.accounting.mastery;

import java.util.HashMap;
import java.util.Map;

/** Reopening an assignment returns the same session service, independent of screen lifetimes. */
public final class LearningSession {
    private final Map<String, ConceptProgressionService> assignments = new HashMap<>();
    private final Map<String, AssignmentProgress> restored;
    private final Runnable onChange;

    public LearningSession() { this(Map.of(), () -> { }); }

    public LearningSession(Map<String, AssignmentProgress> restored, Runnable onChange) {
        this.restored = Map.copyOf(restored);
        this.onChange = onChange;
    }

    public ConceptProgressionService open(Assignment assignment) {
        ConceptProgressionService service = assignments.computeIfAbsent(assignment.id(), ignored -> new ConceptProgressionService(assignment,
                restored.getOrDefault(assignment.id(), new AssignmentProgress(assignment.id(), Map.of())), onChange));
        if (!service.assignment().equals(assignment)) throw new IllegalArgumentException("Assignment ID reused with different content");
        return service;
    }
}
