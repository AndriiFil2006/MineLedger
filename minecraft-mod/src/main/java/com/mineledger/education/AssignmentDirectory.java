package com.mineledger.education;

import java.util.List;
import java.util.Optional;
import com.mineledger.accounting.mastery.Assignment;
import com.mineledger.accounting.mastery.ConceptProgressionService;
import com.mineledger.accounting.mastery.LearningSession;

/** Shared listing and selection for course browsing and the global Assignments tab. */
public final class AssignmentDirectory {
    public enum Action { START, CONTINUE, RECHARGE, COMING_SOON }
    public record Entry(Assignment assignment, Action action, int completedConcepts, int totalConcepts) { }
    public record Group(Course course, Unit unit, List<Entry> entries) {
        public Group { entries = List.copyOf(entries); }
    }
    public record Selection(ConceptProgressionService service, Action action) {
        public boolean recharge() { return action == Action.RECHARGE; }
    }

    private final CourseCatalog catalog;
    public AssignmentDirectory(CourseCatalog catalog) { this.catalog = catalog; }

    public List<Group> groups(LearningSession session) {
        return catalog.courses().stream().flatMap(course -> course.units().stream()
                .map(unit -> new Group(course, unit, entries(unit, session)))).toList();
    }

    public List<Entry> entries(Unit unit, LearningSession session) {
        return unit.assignments().stream().map(assignment -> entry(assignment, session)).toList();
    }

    public Optional<Selection> select(String assignmentId, LearningSession session) {
        return catalog.launch(assignmentId, session).map(service -> new Selection(service, action(service)));
    }

    private Entry entry(Assignment assignment, LearningSession session) {
        if (!assignment.available()) return new Entry(assignment, Action.COMING_SOON, 0, 0);
        var service = session.open(assignment);
        return new Entry(assignment, action(service), service.completedConcepts(), assignment.concepts().size());
    }

    private Action action(ConceptProgressionService service) {
        if (service.completed()) return Action.RECHARGE;
        return service.progress().concepts().isEmpty() ? Action.START : Action.CONTINUE;
    }
}
