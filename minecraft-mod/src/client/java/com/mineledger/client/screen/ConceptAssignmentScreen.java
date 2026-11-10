package com.mineledger.client.screen;

import com.mineledger.accounting.mastery.AccountingConcept;
import com.mineledger.accounting.mastery.ConceptProgressionService;
import com.mineledger.accounting.mastery.RechargePractice;
import com.mineledger.client.JournalSession;
import com.mineledger.education.lesson.IntroConceptLessons;
import com.mineledger.presentation.PracticeQuestionState;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;



/** Compact learning window. Grading, selection and mastery remain in the session service. */
public final class ConceptAssignmentScreen extends ScaledAppScreen {
    private final MineLedgerScreen parent;
    private final ConceptProgressionService service;
    private RechargePractice practice;
    private final PracticeQuestionState state = new PracticeQuestionState();
    private Button submitAnswer;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private String conceptPosition;


    public ConceptAssignmentScreen(MineLedgerScreen parent, ConceptProgressionService service) {
        this(parent, service, false);
    }

    public ConceptAssignmentScreen(MineLedgerScreen parent, ConceptProgressionService service, boolean recharge) {
        super(Component.literal("MineLedger"));
        this.parent = parent;
        this.service = service;
        if (recharge) practice = new RechargePractice(service);
    }

    @Override
    protected void init() {
        if (state.question() == null) state.show((practice == null ? service.nextQuestion() : practice.currentQuestion()).orElse(null));
        // Layout in a readable logical canvas, then zoom this window to fit the actual GUI.
        panelWidth = 600;
        int innerWidth = panelWidth - 28;
        AccountingConcept concept = displayedConcept();
        int conceptNumber = concept == null ? service.assignment().concepts().size()
                : service.assignment().concepts().indexOf(concept) + 1;
        conceptPosition = "Concept " + conceptNumber + "/" + service.assignment().concepts().size();
        ConceptProgressStripWidget strip = new ConceptProgressStripWidget(font, innerWidth, service,
                concept == null ? null : concept.id(), () -> viewport);

        LinearLayout content = body(innerWidth, concept);
        content.arrangeElements();
        int overhead = 42 + strip.getHeight() + 6 + 36;
        panelHeight = overhead + content.getHeight();
        fitWindow(panelWidth, panelHeight);
        left = 0;
        top = 0;
        strip.setPosition(left + 14, top + 42);
        addRenderableWidget(strip);
        int bodyY = top + 42 + strip.getHeight() + 6;
        content.setX(left + 14);
        content.setY(bodyY);
        content.visitWidgets(this::addRenderableWidget);
        addRenderableWidget(Button.builder(Component.literal("Exit Assignment"), button -> onClose())
                .bounds(left + panelWidth - 110, top + 9, 96, 20).build());
        actions();
    }

    private AccountingConcept displayedConcept() {
        var question = state.question();
        return question == null ? service.currentConcept().orElse(null) : service.assignment().concepts().stream()
                .filter(concept -> concept.id().equals(question.conceptId())).findFirst().orElseThrow();
    }

    private LinearLayout body(int contentWidth, AccountingConcept concept) {
        var question = state.question();
        var submission = state.submission();
        LinearLayout body = LinearLayout.vertical().spacing(4);
        if (question == null) {
            if (practice != null) {
                body.addChild(new AssignmentTextPanel(font, contentWidth, "RECHARGE PRACTICE", "Practice Complete",
                        "You reviewed all " + practice.reviewedQuestions() + " questions correctly. No XP was awarded. "
                                + "Your assignment mastery and saved progress are unchanged.", AssignmentStyle.SUCCESS));
                return body;
            }
            body.addChild(new AssignmentTextPanel(font, contentWidth, "PROFILE SUMMARY",
                    service.completed() ? "Assignment Completed" : "Question bank unavailable",
                    service.completed() ? "All " + service.completedConcepts() + " concepts mastered. You earned " + service.xp()
                            + " learning XP. " + JournalSession.storageStatus()
                            : "There are not enough distinct questions to finish this concept. Your earned credits are retained.",
                    service.completed() ? AssignmentStyle.SUCCESS : AssignmentStyle.WARNING));
            return body;
        }
        body.addChild(new AssignmentTextPanel(font, contentWidth, practice == null ? "MULTIPLE CHOICE" : "RECHARGE PRACTICE", concept.title(),
                question.prompt(), AssignmentStyle.ACCENT));
        for (var option : question.options()) {
            AnswerOptionButton choice = body.addChild(new AnswerOptionButton(font, contentWidth, option.text(),
                    () -> option.id().equals(state.selectedOptionId()), () -> {
                        state.select(option.id());
                        submitAnswer.active = true;
                    }));
            choice.active = submission == null;
        }
        int credits = service.progress().concept(concept.id()).credits();
        String progress = credits + "/" + concept.requiredCorrectAnswers() + " correct";
        String heading;
        String explanation;
        int accent;
        if (submission == null) {
            heading = practice == null ? "Your next step  ·  " + progress
                    : "Practice only  ·  " + practice.reviewedQuestions() + "/" + practice.totalQuestions() + " reviewed";
            explanation = practice == null ? "Select one answer. Two distinct correct questions complete this concept. Missed questions return later."
                    : "Review the full question bank. Missed questions return later. No extra XP or mastery credits are awarded.";
            accent = AssignmentStyle.ACCENT;
        } else {
            boolean mastered = service.progress().concept(concept.id()).completed(concept);
            heading = submission.grading().correct() ? "Correct  ·  " + progress : "Not quite  ·  " + progress;
            if (practice != null) heading = submission.grading().correct() ? "Correct  ·  Practice only, no XP" : "Not quite  ·  This question will return later";
            else if (mastered) heading += "  ·  Concept Completed! +" + submission.xpAwarded() + " XP";
            explanation = submission.grading().explanation();
            accent = submission.grading().correct() ? AssignmentStyle.SUCCESS : AssignmentStyle.WARNING;
        }
        body.addChild(new AssignmentTextPanel(font, contentWidth, "", heading, explanation, accent));
        return body;
    }

    private void actions() {
        var question = state.question();
        var submission = state.submission();
        int y = top + panelHeight - 27;
        int actionWidth = Math.min(124, (panelWidth - 86) / 2);
        int right = left + panelWidth - 14;
        if (question != null) {
            var lesson = state.lesson(IntroConceptLessons.CATALOG);
            Button learn = addRenderableWidget(Button.builder(Component.literal(lesson.isPresent() ? "Learn / Review Concept" : "Lesson Unavailable"), button -> {
                state.lesson(IntroConceptLessons.CATALOG).ifPresent(resource -> minecraft.gui.setScreen(
                        new ConceptLessonScreen(this, resource, displayedConcept().title())));
            }).bounds(right - actionWidth * 2 - 6, y, actionWidth, 20).build());
            learn.active = lesson.isPresent();
        }
        if (question == null) {
            if (service.completed()) {
                addRenderableWidget(Button.builder(Component.literal(practice == null ? "Recharge Practice" : "Practice Again"), button -> {
                    practice = new RechargePractice(service);
                    state.clear();
                    rebuildWidgets();
                }).bounds(right - actionWidth * 2 - 6, y, actionWidth, 20).build());
            }
            addRenderableWidget(Button.builder(Component.literal("Assignments"), button -> onClose())
                    .bounds(right - actionWidth, y, actionWidth, 20).build());
        } else if (submission == null) {
            submitAnswer = addRenderableWidget(Button.builder(Component.literal("Submit Answer"), button -> {
                if (state.submission() != null || state.selectedOptionId() == null) return;
                state.recordSubmission(practice == null ? service.submit(question.id(), state.selectedOptionId())
                        : new ConceptProgressionService.Submission(practice.submit(state.selectedOptionId()), false, 0));
                rebuildWidgets();
            }).bounds(right - actionWidth, y, actionWidth, 20).build());
            submitAnswer.active = state.selectedOptionId() != null;
        } else {
            addRenderableWidget(Button.builder(Component.literal("Continue"), button -> {
                if (state.submission() == null) return;
                if (practice != null) practice.continuePractice();
                state.clear();
                rebuildWidgets();
            }).bounds(right - actionWidth, y, actionWidth, 20).build());
        }
    }

    @Override
    protected void extractWindowBackground(GuiGraphicsExtractor graphics) {
        graphics.fill(left + 3, top + 4, left + panelWidth + 3, top + panelHeight + 4, 0x55101E25);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFFFFFFFF);
        graphics.outline(left, top, panelWidth, panelHeight, AssignmentStyle.BORDER);
        graphics.fill(left, top, left + panelWidth, top + 3, AssignmentStyle.ACCENT);
        graphics.text(font, title, left + 14, top + 11, AssignmentStyle.ACCENT, false);
        int positionX = left + 14 + font.width(title) + 12;
        boolean indexFits = positionX + font.width(conceptPosition) < left + panelWidth - 116;
        if (indexFits) graphics.text(font, conceptPosition, positionX, top + 11, AssignmentStyle.MUTED, false);
        String subtitle = (indexFits ? "" : conceptPosition + " · ") + service.assignment().title();
        String fitted = font.plainSubstrByWidth(subtitle, panelWidth - 28);
        graphics.text(font, fitted, left + 14, top + 29, AssignmentStyle.INK, false);
        graphics.fill(left + 14, top + panelHeight - 34, left + panelWidth - 14, top + panelHeight - 33, AssignmentStyle.BORDER);
        graphics.text(font, practice == null ? service.xp() + " XP" : "Practice · +0 XP",
                left + 14, top + panelHeight - 20, AssignmentStyle.MUTED, false);
    }

    @Override
    public void onClose() { minecraft.gui.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
