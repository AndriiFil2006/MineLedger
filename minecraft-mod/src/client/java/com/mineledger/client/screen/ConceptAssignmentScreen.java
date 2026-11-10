package com.mineledger.client.screen;

import com.mineledger.accounting.mastery.AccountingConcept;
import com.mineledger.accounting.mastery.ConceptProgressionService;
import com.mineledger.accounting.mastery.ConceptProgressionService.Submission;
import com.mineledger.accounting.mastery.MultipleChoiceQuestion;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Screens present snapshots; the session service owns grading, selection and mastery. */
public final class ConceptAssignmentScreen extends Screen {
    private final MineLedgerScreen parent;
    private final ConceptProgressionService service;
    private MultipleChoiceQuestion question;
    private String selectedOptionId;
    private Submission submission;
    private Button submitAnswer;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;

    public ConceptAssignmentScreen(MineLedgerScreen parent, ConceptProgressionService service) {
        super(Component.literal("Concept Mastery"));
        this.parent = parent;
        this.service = service;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(720, width - 24);
        panelHeight = Math.min(440, height - 24);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int formWidth = panelWidth - 64;
        LinearLayout form = LinearLayout.vertical().spacing(8);
        text(form, service.assignment().title(), formWidth);
        text(form, "Assignment progress: " + service.completedConcepts() + "/"
                + service.assignment().concepts().size() + " concepts completed", formWidth);
        if (question == null) question = service.nextQuestion().orElse(null);
        if (question != null) {
            questionForm(form, formWidth);
        } else if (service.completed()) {
            text(form, "Assignment Completed", formWidth);
            text(form, "All " + service.completedConcepts() + " required concepts mastered. Earned: "
                    + service.xp() + " learning XP.", formWidth);
        } else {
            AccountingConcept concept = service.currentConcept().orElseThrow();
            conceptHeader(form, concept, formWidth);
            text(form, "End of question bank: this bank has insufficient distinct questions to complete the concept. "
                    + "Your mastery credits are retained. Return to Assignments.", formWidth);
        }
        text(form, "Session assignment XP: " + service.xp() + ". Progress is temporary and resets when Minecraft closes.", formWidth);
        ScrollableLayout scroll = new ScrollableLayout(minecraft, form, Math.max(1, panelHeight - 90));
        scroll.arrangeElements();
        scroll.setX(left + (panelWidth - scroll.getWidth()) / 2);
        scroll.setY(top + 42);
        scroll.visitWidgets(this::addRenderableWidget);
        addRenderableWidget(Button.builder(Component.literal("Back to Assignments"), button -> onClose())
                .bounds(left + 16, top + panelHeight - 32, Math.min(180, panelWidth - 32), 20).build());
    }

    private void conceptHeader(LinearLayout form, AccountingConcept concept, int formWidth) {
        text(form, concept.title(), formWidth);
        text(form, "Concept progress: " + service.progress().concept(concept.id()).credits()
                + "/" + concept.requiredCorrectAnswers(), formWidth);
        text(form, concept.description(), formWidth);
    }

    private void questionForm(LinearLayout form, int formWidth) {
        AccountingConcept concept = service.assignment().concepts().stream()
                .filter(item -> item.id().equals(question.conceptId())).findFirst().orElseThrow();
        conceptHeader(form, concept, formWidth);
        text(form, question.prompt(), formWidth);
        for (var option : question.options()) {
            AnswerOptionButton choice = form.addChild(new AnswerOptionButton(font, formWidth, option.text(),
                    () -> option.id().equals(selectedOptionId), () -> {
                        selectedOptionId = option.id();
                        submitAnswer.active = true;
                    }));
            choice.active = submission == null;
        }
        if (submission == null) {
            submitAnswer = form.addChild(Button.builder(Component.literal("Submit Answer"), button -> {
                submission = service.submit(question.id(), selectedOptionId);
                rebuildWidgets();
            }).size(formWidth, 20).build());
            submitAnswer.active = selectedOptionId != null;
        } else {
            text(form, submission.grading().correct() ? "Correct" : submission.grading().valid() ? "Incorrect" : "Invalid answer", formWidth);
            text(form, submission.grading().explanation(), formWidth);
            boolean mastered = service.progress().concept(concept.id()).completed(concept);
            if (mastered) text(form, "Concept Completed! +" + submission.xpAwarded() + " learning XP.", formWidth);
            if (!submission.grading().correct()) {
                form.addChild(Button.builder(Component.literal("Try Again"), button -> {
                    selectedOptionId = null;
                    submission = null;
                    rebuildWidgets();
                }).size(formWidth, 20).build());
            }
            form.addChild(Button.builder(Component.literal(mastered ? "Continue" : "Next Question"), button -> {
                question = null;
                selectedOptionId = null;
                submission = null;
                rebuildWidgets();
            }).size(formWidth, 20).build());
        }
    }

    private void text(LinearLayout form, String value, int maxWidth) {
        form.addChild(new MultiLineTextWidget(Component.literal(value)
                .withStyle(style -> style.withColor(0x35383D).withoutShadow()), font).setMaxWidth(maxWidth));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xFFEDEEF0);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFFFFFFFF);
        graphics.outline(left, top, panelWidth, panelHeight, 0xFFCFD1D5);
        graphics.fill(left + 1, top + 1, left + panelWidth - 1, top + 30, 0xFFB6202D);
        graphics.text(font, title, left + 16, top + 11, 0xFFFFFFFF, false);
    }

    @Override
    public void onClose() { minecraft.gui.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
