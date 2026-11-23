package com.mineledger.backend;

import java.util.List;
import java.util.Optional;
import com.mineledger.accounting.mastery.AnswerOption;
import com.mineledger.accounting.mastery.Assignment.Availability;
import com.mineledger.education.lesson.ConceptLesson;

/**
 * Typed views of the backend's read-only curriculum responses, one record per endpoint shape.
 *
 * <p>These are not the local {@code Course}/{@code Assignment}/{@code MultipleChoiceQuestion} models on purpose:
 * the API pages the hierarchy across endpoints and withholds answer keys and explanations, which the local
 * models require. Nothing here can therefore be graded or passed to the mastery service. Shapes the API does
 * share with the local domain ({@link AnswerOption}, {@link Availability}, {@link ConceptLesson}) are reused.
 */
public final class RemoteCurriculum {
    public record Health(String status, String database) {
        public boolean ok() { return "ok".equals(status) && "ok".equals(database); }
    }

    /** The backend's compatibility statement: its API contract version and what curriculum it holds. */
    public record Meta(int apiVersion, List<Integer> curriculumSchemaVersions, int courseCount) {
        public Meta { curriculumSchemaVersions = List.copyOf(curriculumSchemaVersions); }
    }

    public record CourseSummary(String id, String title, String description, List<String> unitIds) {
        public CourseSummary { unitIds = List.copyOf(unitIds); }
    }

    public record UnitSummary(String id, int number, String title, String description, List<String> assignmentIds) {
        public UnitSummary { assignmentIds = List.copyOf(assignmentIds); }
    }

    public record CourseDetail(String id, String title, String description, List<UnitSummary> units) {
        public CourseDetail { units = List.copyOf(units); }
    }

    public record AssignmentSummary(String id, String title, String description, Availability availability,
            List<String> conceptIds) {
        public AssignmentSummary { conceptIds = List.copyOf(conceptIds); }
        public boolean available() { return availability == Availability.AVAILABLE; }
    }

    public record UnitDetail(String id, String courseId, int number, String title, String description,
            List<AssignmentSummary> assignments) {
        public UnitDetail { assignments = List.copyOf(assignments); }
    }

    public record ConceptSummary(String id, String title, String description, int requiredCorrectAnswers,
            int questionCount, Optional<String> lessonId) { }

    public record AssignmentDetail(String id, String unitId, String title, String description,
            Availability availability, List<ConceptSummary> concepts) {
        public AssignmentDetail { concepts = List.copyOf(concepts); }
    }

    public record ConceptDetail(String id, String assignmentId, String title, String description,
            int requiredCorrectAnswers, List<String> questionIds, Optional<ConceptLesson> lesson) {
        public ConceptDetail { questionIds = List.copyOf(questionIds); }
    }

    /** A question as shown to a learner. There is deliberately no answer key or explanation component. */
    public record Question(String id, String conceptId, String prompt, Optional<String> difficulty,
            List<AnswerOption> options) {
        public Question { options = List.copyOf(options); }
    }

    public record AssignmentQuestions(String assignmentId, List<Question> questions) {
        public AssignmentQuestions { questions = List.copyOf(questions); }
    }

    private RemoteCurriculum() { }
}
