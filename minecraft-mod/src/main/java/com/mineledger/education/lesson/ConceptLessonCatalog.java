package com.mineledger.education.lesson;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ConceptLessonCatalog implements ConceptLessonSource {
    private final Map<String, ConceptLesson> byConcept = new HashMap<>();

    public ConceptLessonCatalog(List<ConceptLesson> lessons) {
        var ids = new HashSet<String>();
        for (ConceptLesson lesson : List.copyOf(lessons)) {
            if (!ids.add(lesson.id()) || byConcept.putIfAbsent(lesson.conceptId(), lesson) != null) {
                throw new IllegalArgumentException("Duplicate lesson ID or ambiguous concept lesson");
            }
        }
    }

    @Override
    public Optional<ConceptLesson> forConcept(String conceptId) { return Optional.ofNullable(byConcept.get(conceptId)); }
}
