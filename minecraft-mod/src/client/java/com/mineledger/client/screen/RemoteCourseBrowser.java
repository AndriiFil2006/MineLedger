package com.mineledger.client.screen;

import com.mineledger.backend.RemoteCurriculumBrowser;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

/** Backend-mode Courses tab: presents {@link RemoteCurriculumBrowser} state. Read-only; nothing here grades. */
final class RemoteCourseBrowser {
    /**
     * @param connectionLost the screen already shows the connection problem and its Retry Connection button,
     *                       so a failed page only offers the way back to what is still loaded
     * @param showLoading    false keeps the current page on screen while the next one is being fetched, so a
     *                       fast response replaces it directly instead of flashing a loading page in between
     */
    static void populate(LinearLayout body, int width, Font font, RemoteCurriculumBrowser browser, Runnable refresh,
            boolean connectionLost, boolean showLoading) {
        if (browser.error().isPresent()) {
            text(body, width, font, connectionLost ? "This page could not be loaded."
                    : "Could not load from the server.\n" + browser.error().get()
                    + "\nOffline content is not shown in its place.");
            LinearLayout row = body.addChild(LinearLayout.horizontal().spacing(6));
            if (browser.canRetry() && !connectionLost) {
                row.addChild(Button.builder(Component.literal("Retry"), button -> browser.retry()).size(120, 20).build());
            }
            if (browser.canGoBack()) row.addChild(back("Back", browser, refresh));
            return;
        }
        if (browser.loading() && showLoading) {
            text(body, width, font, "Loading from the server...");
            if (browser.canGoBack()) body.addChild(back("Cancel", browser, refresh));
            return;
        }
        switch (browser.view()) {
            case COURSES -> {
                text(body, width, font, "Online courses (view-only)");
                for (var course : browser.courses()) {
                    body.addChild(Button.builder(Component.literal(course.title()), button -> browser.openCourse(course.id()))
                            .size(width, 20).build());
                    text(body, width, font, count(course.unitIds().size(), "unit"));
                }
                if (browser.courses().isEmpty()) {
                    text(body, width, font, "The server has no courses yet.");
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
                text(body, width, font, "View-only preview: online assignments are not graded yet and do not change mastery or XP.");
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
                text(body, width, font, "View-only preview: answers are not graded online yet.");
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
