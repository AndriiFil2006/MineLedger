package com.mineledger.education;

import com.mineledger.curriculum.CurriculumContent;

/** Stable-ID compatibility handles; all definitions live in curriculum JSON. */
public final class Accounting101 {
    public static final String COURSE_ID = "accounting101";
    public static final String UNIT_1_ID = "accounting101.fundamentals";
    public static final String UNIT_2_ID = "accounting101.recording_transactions";
    public static final String UNIT_3_ID = "accounting101.adjusting_entries";
    public static final CourseCatalog CATALOG = CurriculumContent.get().courses();
    public static final Course COURSE = CATALOG.course(COURSE_ID).orElseThrow();
    private Accounting101() { }
}
