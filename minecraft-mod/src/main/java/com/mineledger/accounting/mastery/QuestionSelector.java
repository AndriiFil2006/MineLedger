package com.mineledger.accounting.mastery;

import java.util.Optional;

/** Prefer unseen questions, then rotate missed questions until mastery. Never repeat credited questions. */
public final class QuestionSelector {
    public Optional<MultipleChoiceQuestion> next(AccountingConcept concept, ConceptProgress progress) {
        if (progress.completed(concept)) return Optional.empty();
        var eligible = concept.questions().stream()
                .filter(question -> !progress.correctQuestionIds().contains(question.id()))
                .toList();
        var unseen = eligible.stream()
                .filter(question -> !progress.attemptedQuestionIds().contains(question.id()))
                .findFirst();
        if (unseen.isPresent() || eligible.isEmpty()) return unseen;
        for (int i = 0; i < eligible.size(); i++) {
            if (eligible.get(i).id().equals(progress.lastAttemptedQuestionId())) {
                return Optional.of(eligible.get((i + 1) % eligible.size()));
            }
        }
        return Optional.of(eligible.getFirst());
    }
}
