package com.mineledger.education.lesson;

import java.util.Optional;

/** Lookup boundary for local content now, another content provider later. No networking here. */
public interface ConceptLessonSource {
    Optional<ConceptLesson> forConcept(String conceptId);
}
