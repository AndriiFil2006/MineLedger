package com.mineledger.curriculum;

import java.nio.file.Path;

/** Standalone validator: uses no Minecraft client/runtime classes and never opens player saves. */
public final class ValidateCurriculum {
    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Usage: ValidateCurriculum [curriculum.json]");
        Curriculum data = (args.length == 0 ? new JsonCurriculumRepository() : new JsonCurriculumRepository(Path.of(args[0]))).load();
        int units = data.courses().courses().stream().mapToInt(course -> course.units().size()).sum();
        int assignments = data.courses().courses().stream().flatMap(course -> course.units().stream()).mapToInt(unit -> unit.assignments().size()).sum();
        int journals = data.labs().values().stream().mapToInt(lab -> lab.questions().size()).sum();
        long lessons = data.concepts().keySet().stream().filter(id -> data.lessons().forConcept(id).isPresent()).count();
        System.out.println("Valid schema " + data.schemaVersion() + ": " + data.courses().courses().size() + " courses, "
                + units + " units, " + assignments + " assignments, " + data.concepts().size() + " concepts, "
                + data.questions().size() + " multiple-choice questions, " + journals + " journal-entry questions, " + lessons + " lessons.");
    }
    private ValidateCurriculum() { }
}
