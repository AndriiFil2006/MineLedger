package com.mineledger.accounting.mastery;

import java.util.Set;

/** Immutable snapshot: callers cannot alter credits or selection history. */
public record ConceptProgress(Set<String> correctQuestionIds, Set<String> attemptedQuestionIds, String lastAttemptedQuestionId) {
    public ConceptProgress {
        correctQuestionIds = Set.copyOf(correctQuestionIds);
        attemptedQuestionIds = Set.copyOf(attemptedQuestionIds);
    }

    public ConceptProgress() {
        this(Set.of(), Set.of(), null);
    }

    public int credits() {
        return correctQuestionIds.size();
    }

    public boolean completed(AccountingConcept concept) {
        return credits() >= concept.requiredCorrectAnswers();
    }
}
