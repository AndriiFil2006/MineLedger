package com.mineledger.education;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.mineledger.accounting.mastery.Assignment;
import com.mineledger.accounting.mastery.ConceptProgressionService;
import com.mineledger.accounting.mastery.LearningSession;

/** Stable-ID lookup and safe exercise routing; placeholders never become progress services. */
public final class CourseCatalog {
    private final List<Course> courses;
    private final Map<String, Course> coursesById = new HashMap<>();
    private final Map<String, Unit> unitsById = new HashMap<>();
    private final Map<String, Assignment> assignmentsById = new HashMap<>();

    public CourseCatalog(List<Course> courses) {
        this.courses = List.copyOf(courses);
        var questionIds = new java.util.HashSet<String>();
        for (Course course : courses) {
            unique(coursesById, course.id(), course);
            for (Unit unit : course.units()) {
                unique(unitsById, unit.id(), unit);
                for (Assignment assignment : unit.assignments()) {
                    unique(assignmentsById, assignment.id(), assignment);
                    assignment.concepts().forEach(concept -> concept.questions().forEach(question -> {
                        if (!questionIds.add(question.id())) throw new IllegalArgumentException("Duplicate catalog question ID");
                    }));
                }
            }
        }
    }

    private static <T> void unique(Map<String, T> map, String id, T value) {
        if (map.putIfAbsent(id, value) != null) throw new IllegalArgumentException("Duplicate catalog ID: " + id);
    }

    public List<Course> courses() { return courses; }
    public Optional<Course> course(String id) { return Optional.ofNullable(coursesById.get(id)); }
    public Optional<Unit> unit(String id) { return Optional.ofNullable(unitsById.get(id)); }
    public Optional<Assignment> assignment(String id) { return Optional.ofNullable(assignmentsById.get(id)); }
    public List<Assignment> availableAssignments() {
        return courses.stream().flatMap(course -> course.units().stream()).flatMap(unit -> unit.assignments().stream())
                .filter(Assignment::available).toList();
    }

    public Optional<ConceptProgressionService> launch(String assignmentId, LearningSession session) {
        return assignment(assignmentId).filter(Assignment::available).map(session::open);
    }
}
