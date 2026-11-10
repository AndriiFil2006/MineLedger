package com.mineledger.education.lesson;

import com.mineledger.curriculum.CurriculumContent;

/** Local lesson-source handle. The original lesson text exists only in JSON. */
public final class IntroConceptLessons {
    public static final ConceptLessonCatalog CATALOG = CurriculumContent.get().lessons();
    public static final ConceptLesson INVENTORY_ON_CREDIT = CATALOG.forConcept("inventory.purchases_on_credit").orElseThrow();
    private IntroConceptLessons() { }
}
