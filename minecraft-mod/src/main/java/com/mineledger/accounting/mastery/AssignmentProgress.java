package com.mineledger.accounting.mastery;

import java.util.Map;

public record AssignmentProgress(String assignmentId, Map<String, ConceptProgress> concepts) {
    public AssignmentProgress {
        concepts = Map.copyOf(concepts);
    }

    public ConceptProgress concept(String conceptId) {
        return concepts.getOrDefault(conceptId, new ConceptProgress());
    }
}
