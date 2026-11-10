package com.mineledger.education;

import java.util.List;
import com.mineledger.accounting.mastery.Assignment;
import com.mineledger.accounting.mastery.LearningSession;

/** Derived counts only; never save percentages or create progress for Coming Soon content. */
public final class CourseProgress {
    public record Count(int completed, int total) {
        public int percent() { return total == 0 ? 0 : completed * 100 / total; }
        public String assignmentsLabel() {
            return total == 0 ? "No available assignments" : completed + "/" + total + " available assignments completed";
        }
    }

    private final LearningSession session;
    public CourseProgress(LearningSession session) { this.session = session; }

    public Count assignment(Assignment assignment) {
        if (!assignment.available()) return new Count(0, 0);
        return new Count(session.open(assignment).completedConcepts(), assignment.concepts().size());
    }

    public Count unit(Unit unit) { return available(unit.assignments()); }
    public Count course(Course course) { return available(course.units().stream().flatMap(unit -> unit.assignments().stream()).toList()); }

    private Count available(List<Assignment> assignments) {
        var available = assignments.stream().filter(Assignment::available).toList();
        return new Count((int) available.stream().filter(assignment -> session.open(assignment).completed()).count(), available.size());
    }
}
