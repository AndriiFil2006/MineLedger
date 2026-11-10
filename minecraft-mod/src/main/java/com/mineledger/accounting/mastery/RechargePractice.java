package com.mineledger.accounting.mastery;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/** Disposable practice queue: intentionally holds no progress service, save callback or reward state. */
public final class RechargePractice {
    private final Deque<MultipleChoiceQuestion> remaining;
    private final MultipleChoiceGrader grader = new MultipleChoiceGrader();
    private final int totalQuestions;
    private GradingResult result;

    public RechargePractice(ConceptProgressionService completedAssignment) {
        if (!completedAssignment.completed()) throw new IllegalStateException("Complete the assignment before starting Recharge Practice");
        var questions = completedAssignment.assignment().concepts().stream().flatMap(concept -> concept.questions().stream()).toList();
        remaining = new ArrayDeque<>(questions);
        totalQuestions = questions.size();
    }

    public Optional<MultipleChoiceQuestion> currentQuestion() { return Optional.ofNullable(remaining.peekFirst()); }
    public int totalQuestions() { return totalQuestions; }
    public int reviewedQuestions() { return totalQuestions - remaining.size(); }
    public boolean completed() { return remaining.isEmpty(); }

    public GradingResult submit(String optionId) {
        if (result != null) return result;
        GradingResult grading = grader.grade(currentQuestion().orElseThrow(() -> new IllegalStateException("Practice completed")), optionId);
        if (grading.valid()) result = grading;
        return grading;
    }

    public void continuePractice() {
        if (result == null) throw new IllegalStateException("Submit an answer before continuing");
        MultipleChoiceQuestion question = remaining.removeFirst();
        if (!result.correct()) remaining.addLast(question);
        result = null;
    }
}
