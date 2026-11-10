package com.mineledger.accounting.mastery;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;

/** A session-owned service, never owned by a Minecraft Screen. */
public final class ConceptProgressionService {
    public record Submission(GradingResult grading, boolean creditEarned, int xpAwarded) { }

    private final Assignment assignment;
    private final MultipleChoiceGrader grader = new MultipleChoiceGrader();
    private final QuestionSelector selector = new QuestionSelector();
    private AssignmentProgress progress;
    private final Runnable onChange;

    public ConceptProgressionService(Assignment assignment) {
        this(assignment, new AssignmentProgress(assignment.id(), Map.of()), () -> { });
    }

    public ConceptProgressionService(Assignment assignment, AssignmentProgress restored, Runnable onChange) {
        this.assignment = assignment;
        validateRestored(assignment, restored);
        progress = restored;
        this.onChange = onChange;
    }

    public Assignment assignment() { return assignment; }
    public AssignmentProgress progress() { return progress; }

    public Optional<AccountingConcept> currentConcept() {
        return assignment.concepts().stream().filter(concept -> !progress.concept(concept.id()).completed(concept)).findFirst();
    }

    public Optional<MultipleChoiceQuestion> nextQuestion() {
        return currentConcept().flatMap(concept -> selector.next(concept, progress.concept(concept.id())));
    }

    public boolean completed() { return currentConcept().isEmpty(); }

    public int completedConcepts() {
        return (int) assignment.concepts().stream().filter(concept -> progress.concept(concept.id()).completed(concept)).count();
    }

    public int xp() { return completedConcepts() * 20; }

    public Submission submit(String questionId, String selectedOptionId) {
        AccountingConcept concept = assignment.concepts().stream()
                .filter(item -> item.questions().stream().anyMatch(question -> question.id().equals(questionId)))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown question ID"));
        ConceptProgress before = progress.concept(concept.id());
        if (!before.completed(concept) && currentConcept().filter(item -> item.id().equals(concept.id())).isEmpty()) {
            throw new IllegalStateException("This concept is locked");
        }
        MultipleChoiceQuestion question = concept.questions().stream().filter(item -> item.id().equals(questionId)).findFirst().orElseThrow();
        GradingResult grading = grader.grade(question, selectedOptionId);
        if (!grading.valid() || before.completed(concept) || before.correctQuestionIds().contains(question.id())) {
            return new Submission(grading, false, 0);
        }
        var attempted = new HashSet<>(before.attemptedQuestionIds());
        attempted.add(question.id());
        var correct = new HashSet<>(before.correctQuestionIds());
        boolean earned = grading.correct() && correct.add(question.id());
        ConceptProgress after = new ConceptProgress(correct, attempted, question.id());
        replace(concept.id(), after);
        return new Submission(grading, earned, after.completed(concept) ? 20 : 0);
    }

    private void replace(String conceptId, ConceptProgress value) {
        if (progress.concept(conceptId).equals(value)) return;
        var updated = new HashMap<>(progress.concepts());
        updated.put(conceptId, value);
        progress = new AssignmentProgress(assignment.id(), updated);
        onChange.run();
    }

    private static void validateRestored(Assignment assignment, AssignmentProgress progress) {
        if (!assignment.id().equals(progress.assignmentId()) || progress.concepts().keySet().stream()
                .anyMatch(id -> assignment.concepts().stream().noneMatch(concept -> concept.id().equals(id)))) {
            throw new IllegalArgumentException("Saved assignment or concept IDs do not match installed content");
        }
        boolean previousCompleted = true;
        for (var concept : assignment.concepts()) {
            var saved = progress.concept(concept.id());
            var ids = concept.questions().stream().map(MultipleChoiceQuestion::id).collect(java.util.stream.Collectors.toSet());
            if (!ids.containsAll(saved.attemptedQuestionIds()) || !saved.attemptedQuestionIds().containsAll(saved.correctQuestionIds())
                    || saved.credits() > concept.requiredCorrectAnswers()
                    || saved.lastAttemptedQuestionId() != null && !saved.attemptedQuestionIds().contains(saved.lastAttemptedQuestionId())
                    || !previousCompleted && !saved.attemptedQuestionIds().isEmpty()) {
                throw new IllegalArgumentException("Saved question credits or concept order do not match installed content");
            }
            previousCompleted = previousCompleted && saved.completed(concept);
        }
    }
}
