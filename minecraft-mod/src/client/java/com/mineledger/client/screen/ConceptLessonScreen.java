package com.mineledger.client.screen;

import com.mineledger.education.lesson.ConceptLesson;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

/** Read-only lesson view. The exact practice Screen is retained as the return target. */
final class ConceptLessonScreen extends ScaledAppScreen {
    private final ConceptAssignmentScreen practice;
    private final ConceptLesson lesson;
    private final String conceptTitle;
    private ScrollableLayout scroll;
    private double scrollAmount;

    ConceptLessonScreen(ConceptAssignmentScreen practice, ConceptLesson lesson, String conceptTitle) {
        super(Component.literal("MineLedger · Learn"));
        this.practice = practice;
        this.lesson = lesson;
        this.conceptTitle = conceptTitle;
    }

    @Override
    protected void init() {
        if (scroll != null) scrollAmount = scroll.getScrollAmount();
        fitWindow(600, 360);
        LinearLayout content = LinearLayout.vertical().spacing(6);
        card(content, "Learning objective", lesson.learningObjective());
        card(content, "Explanation", lesson.explanation());
        card(content, "Key accounting rules", bullets(lesson.keyRules()));
        for (var example : lesson.workedExamples()) {
            card(content, "Worked example: " + example.title(), example.scenario() + "\n\n" + example.calculation()
                    + "\n" + example.debitEntry() + "\n" + example.creditEntry() + "\n\n" + example.explanation());
        }
        if (!lesson.commonMistakes().isEmpty()) card(content, "Common mistakes", bullets(lesson.commonMistakes()));
        if (!lesson.takeaway().isBlank()) card(content, "Takeaway", lesson.takeaway());
        content.arrangeElements();
        scroll = new ScrollableLayout(minecraft, content, 268, ScrollableLayout.ReserveStrategy.RIGHT);
        scroll.arrangeElements();
        scroll.setX(14);
        scroll.setY(48);
        scroll.visitWidgets(widget -> {
            addRenderableWidget(widget);
            if (widget instanceof AbstractScrollArea area) area.setScrollAmount(scrollAmount);
        });
        addRenderableWidget(Button.builder(Component.literal("Back to Practice"), button -> onClose())
                .bounds(14, 328, 160, 20).build());
    }

    private void card(LinearLayout content, String heading, String text) {
        content.addChild(new AssignmentTextPanel(font, 560, "", heading, text, AssignmentStyle.ACCENT));
    }

    private String bullets(java.util.List<String> values) {
        return values.stream().map(value -> "• " + value).collect(java.util.stream.Collectors.joining("\n"));
    }

    @Override
    protected void extractWindowBackground(GuiGraphicsExtractor graphics) {
        graphics.fill(0, 0, canvasWidth, canvasHeight, 0xFFFFFFFF);
        graphics.outline(0, 0, canvasWidth, canvasHeight, AssignmentStyle.BORDER);
        graphics.fill(0, 0, canvasWidth, 3, AssignmentStyle.ACCENT);
        graphics.text(font, title, 14, 11, AssignmentStyle.ACCENT, false);
        graphics.text(font, font.plainSubstrByWidth(conceptTitle, 572), 14, 29, AssignmentStyle.INK, false);
        graphics.fill(14, 321, 586, 322, AssignmentStyle.BORDER);
        graphics.text(font, "No XP or mastery changes", 350, 334, AssignmentStyle.MUTED, false);
    }

    @Override
    public void onClose() { minecraft.gui.setScreen(practice); }

    @Override
    public boolean isPauseScreen() { return false; }
}
