package com.mineledger.presentation;

import java.util.Optional;
import com.mineledger.accounting.mastery.MultipleChoiceQuestion;
import com.mineledger.accounting.mastery.ConceptProgressionService.Submission;
import com.mineledger.education.lesson.ConceptLesson;
import com.mineledger.education.lesson.ConceptLessonSource;

/** Transient view state survives a lesson detour; it owns no grading/progress/save services. */
public final class PracticeQuestionState {
    public record Snapshot(MultipleChoiceQuestion question, String selectedOptionId, Submission submission) { }
    private Snapshot snapshot = new Snapshot(null, null, null);

    public Snapshot snapshot() { return snapshot; }
    public MultipleChoiceQuestion question() { return snapshot.question(); }
    public String selectedOptionId() { return snapshot.selectedOptionId(); }
    public Submission submission() { return snapshot.submission(); }

    public void show(MultipleChoiceQuestion question) {
        if (snapshot.question() != null) throw new IllegalStateException("Clear the current question before advancing");
        snapshot = new Snapshot(question, null, null);
    }

    public void select(String optionId) {
        if (question() == null || submission() != null || question().options().stream().noneMatch(option -> option.id().equals(optionId))) {
            throw new IllegalStateException("Cannot select that answer in the current view state");
        }
        snapshot = new Snapshot(question(), optionId, null);
    }

    public void recordSubmission(Submission submission) {
        if (question() == null || selectedOptionId() == null || submission == null || submission() != null) {
            throw new IllegalStateException("Cannot record submission in the current view state");
        }
        snapshot = new Snapshot(question(), selectedOptionId(), submission);
    }

    public void clear() { snapshot = new Snapshot(null, null, null); }

    public Optional<ConceptLesson> lesson(ConceptLessonSource source) {
        return question() == null ? Optional.empty() : source.forConcept(question().conceptId())
                .filter(lesson -> lesson.conceptId().equals(question().conceptId()));
    }
}
