package com.mineledger.curriculum;

import java.io.IOException;

/** Content-source boundary; no grading, progress storage or networking. */
public interface CurriculumRepository {
    Curriculum load() throws IOException;
}
