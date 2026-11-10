package com.mineledger.curriculum;

import java.util.List;
import java.util.Map;
import com.mineledger.accounting.AccountingQuestion;
import com.mineledger.accounting.mastery.AccountingConcept;
import com.mineledger.accounting.mastery.MultipleChoiceQuestion;
import com.mineledger.education.CourseCatalog;
import com.mineledger.education.lesson.ConceptLessonCatalog;

/** Resolved immutable curriculum; player progress is deliberately absent. */
public record Curriculum(int schemaVersion, CourseCatalog courses, ConceptLessonCatalog lessons,
        Map<String, AccountingConcept> concepts, Map<String, MultipleChoiceQuestion> questions,
        Map<String, JournalLab> labs, Map<String, String> questionDifficulties) {
    public record JournalLab(String id, String title, List<AccountingQuestion> questions) {
        public JournalLab { questions = List.copyOf(questions); }
    }
    public Curriculum {
        concepts = Map.copyOf(concepts);
        questions = Map.copyOf(questions);
        labs = Map.copyOf(labs);
        questionDifficulties = Map.copyOf(questionDifficulties);
    }
}
