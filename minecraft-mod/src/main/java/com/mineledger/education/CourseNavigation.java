package com.mineledger.education;

import java.util.Optional;

/** Small navigation state; invalid IDs leave the current location unchanged. */
public final class CourseNavigation {
    private final CourseCatalog catalog;
    private String courseId;
    private String unitId;

    public CourseNavigation(CourseCatalog catalog) { this.catalog = catalog; }
    public Optional<Course> course() { return catalog.course(courseId); }
    public Optional<Unit> unit() { return catalog.unit(unitId); }

    public boolean openCourse(String id) {
        if (catalog.course(id).isEmpty()) return false;
        courseId = id;
        unitId = null;
        return true;
    }

    public boolean openUnit(String id) {
        var unit = catalog.unit(id);
        if (unit.isEmpty() || !unit.get().courseId().equals(courseId)) return false;
        unitId = id;
        return true;
    }

    public boolean back() {
        if (unitId != null) { unitId = null; return true; }
        if (courseId != null) { courseId = null; return true; }
        return false;
    }
}
