package com.mineledger.education;

import java.util.List;

public record Course(String id, String title, String description, List<Unit> units) {
    public Course {
        units = List.copyOf(units);
        if (id == null || id.isBlank() || title == null || title.isBlank() || description == null || description.isBlank()
                || units.stream().anyMatch(unit -> !unit.courseId().equals(id))
                || units.stream().map(Unit::id).distinct().count() != units.size()) {
            throw new IllegalArgumentException("Invalid course or unit parent links");
        }
        int previous = 0;
        for (Unit unit : units) {
            if (unit.number() <= previous) throw new IllegalArgumentException("Units must be in increasing number order");
            previous = unit.number();
        }
    }
}
