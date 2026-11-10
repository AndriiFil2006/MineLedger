package com.mineledger.education;

import java.util.List;
import com.mineledger.accounting.mastery.Assignment;

public record Unit(String id, String courseId, int number, String title, String description, List<Assignment> assignments) {
    public Unit {
        assignments = List.copyOf(assignments);
        if (id == null || id.isBlank() || courseId == null || courseId.isBlank() || number < 1
                || title == null || title.isBlank() || description == null || description.isBlank()
                || assignments.stream().anyMatch(assignment -> !assignment.unitId().equals(id))
                || assignments.stream().map(Assignment::id).distinct().count() != assignments.size()) {
            throw new IllegalArgumentException("Invalid unit or assignment parent links");
        }
    }
}
