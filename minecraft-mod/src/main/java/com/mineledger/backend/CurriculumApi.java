package com.mineledger.backend;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import com.mineledger.backend.RemoteCurriculum.*;

/**
 * Asynchronous read-only curriculum operations. Calls return immediately; futures complete on a background
 * thread and fail with a {@link BackendException} (possibly wrapped; see {@link BackendException#from}).
 */
public interface CurriculumApi {
    CompletableFuture<Health> health();
    CompletableFuture<List<CourseSummary>> courses();
    CompletableFuture<CourseDetail> course(String courseId);
    CompletableFuture<UnitDetail> unit(String unitId);
    CompletableFuture<AssignmentDetail> assignment(String assignmentId);
    CompletableFuture<ConceptDetail> concept(String conceptId);
    CompletableFuture<AssignmentQuestions> assignmentQuestions(String assignmentId);
}
