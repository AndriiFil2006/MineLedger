package com.mineledger.client.screen;

import com.mineledger.backend.RemoteCurriculumBrowser;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

/** Backend-mode Courses tab: presents {@link RemoteCurriculumBrowser} state. Read-only; nothing here grades. */
final class RemoteCourseBrowser {
    static String status(RemoteCurriculumBrowser browser) {
        return switch (browser.connection()) {
            case CONNECTING -> "Connecting...";
            case CONNECTED -> "Connected to backend";
            case UNAVAILABLE -> "Backend unavailable";
        };
    }

    static void populate(LinearLayout body, int width, Font font, RemoteCurriculumBrowser browser, Runnable refresh) {
        if (browser.error().isPresent()) {
            text(body, width, font, "Could not load from the backend.\n" + browser.error().get()
                    + "\nLocal content is not substituted in backend mode.");
            LinearLayout row = body.addChild(LinearLayout.horizontal().spacing(6));
            if (browser.canRetry()) {
                row.addChild(Button.builder(Component.literal("Retry"), button -> browser.retry()).size(120, 20).build());
            }
            if (browser.canGoBack()) row.addChild(back("Back", browser, refresh));
            return;
        }
        if (browser.loading()) {
            text(body, width, font, "Loading from the backend...");
            if (browser.canGoBack()) body.addChild(back("Cancel", browser, refresh));
            return;
        }
        switch (browser.view()) {
            case COURSES -> {
                text(body, width, font, "Backend courses (read-only)");
                for (var course : browser.courses()) {
                    body.addChild(Button.builder(Component.literal(course.title()), button -> browser.openCourse(course.id()))
                            .size(width, 20).build());
                    text(body, width, font, count(course.unitIds().size(), "unit"));
                }
                if (browser.courses().isEmpty()) {
                    text(body, width, font, "The backend has no courses. Import the curriculum on the server, then reload.");
                }
                body.addChild(Button.builder(Component.literal("Reload"), button -> browser.reloadCourses()).size(120, 20).build());
            }
            case COURSE -> {
                var course = browser.course().orElseThrow();
                body.addChild(back("Back to Courses", browser, refresh));
                text(body, width, font, course.title() + "\n" + course.description());
                for (var unit : course.units()) {
                    body.addChild(Button.builder(Component.literal("Unit " + unit.number() + ": " + unit.title()),
                            button -> browser.openUnit(unit.id())).size(width, 20).build());
                    text(body, width, font, count(unit.assignmentIds().size(), "assignment"));
                }
            }
            case UNIT -> {
                var unit = browser.unit().orElseThrow();
                body.addChild(back("Back to Units", browser, refresh));
                text(body, width, font, "Unit " + unit.number() + ": " + unit.title() + "\n" + unit.description());
                for (var assignment : unit.assignments()) {
                    text(body, width, font, assignment.title());
                    Button open = body.addChild(Button.builder(Component.literal(assignment.available()
                            ? "Preview  ·  Available  ·  " + count(assignment.conceptIds().size(), "concept") : "Coming Soon"),
                            button -> browser.openAssignment(assignment.id())).size(width, 20).build());
                    open.active = assignment.available();
                }
            }
            case ASSIGNMENT -> {
                var preview = browser.assignment().orElseThrow();
                body.addChild(back("Back to Assignments", browser, refresh));
                text(body, width, font, preview.assignment().title() + "\n" + preview.assignment().description());
                for (var concept : preview.concepts()) {
                    text(body, width, font, concept.title() + "\n" + concept.requiredCorrectAnswers() + " correct required  ·  "
                            + count(concept.questionIds().size(), "question") + "  ·  "
                            + concept.lesson().map(lesson -> "Lesson: " + lesson.title()).orElse("No lesson"));
                }
                Button questions = body.addChild(Button.builder(Component.literal("Preview Questions ("
                        + preview.questions().size() + ")"), button -> show(browser, 0, refresh)).size(width, 20).build());
                questions.active = !preview.questions().isEmpty();
                text(body, width, font, "Read-only preview: backend mode does not grade answers or change mastery or XP.");
            }
            case QUESTION -> {
                var preview = browser.assignment().orElseThrow();
                int index = browser.questionIndex();
                var question = preview.questions().get(index);
                body.addChild(back("Back to Assignment", browser, refresh));
                text(body, width, font, "Question " + (index + 1) + " of " + preview.questions().size() + "  ·  "
                        + preview.concept(question.conceptId()).map(concept -> concept.title()).orElse(question.conceptId()));
                text(body, width, font, question.prompt());
                for (var option : question.options()) text(body, width, font, "  -  " + option.text());
                LinearLayout row = body.addChild(LinearLayout.horizontal().spacing(6));
                Button previous = row.addChild(Button.builder(Component.literal("Previous"),
                        button -> show(browser, index - 1, refresh)).size(120, 20).build());
                previous.active = index > 0;
                Button next = row.addChild(Button.builder(Component.literal("Next"),
                        button -> show(browser, index + 1, refresh)).size(120, 20).build());
                next.active = index + 1 < preview.questions().size();
                text(body, width, font, "Read-only preview: answers are not graded in backend mode.");
            }
        }
    }

    private static void show(RemoteCurriculumBrowser browser, int index, Runnable refresh) {
        browser.showQuestion(index);
        refresh.run();
    }

    private static Button back(String label, RemoteCurriculumBrowser browser, Runnable refresh) {
        return Button.builder(Component.literal(label), button -> {
            browser.back();
            refresh.run();
        }).size(160, 20).build();
    }

    private static String count(int number, String noun) { return number + " " + noun + (number == 1 ? "" : "s"); }

    private static void text(LinearLayout body, int width, Font font, String message) {
        AssignmentRows.text(body, width, font, message);
    }

    private RemoteCourseBrowser() { }
}
