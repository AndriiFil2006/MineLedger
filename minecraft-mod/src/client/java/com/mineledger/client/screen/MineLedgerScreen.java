package com.mineledger.client.screen;

import java.util.EnumMap;
import java.util.stream.Collectors;
import com.mineledger.client.JournalSession;
import com.mineledger.accounting.mastery.LearningSession;
import com.mineledger.accounting.IntroJournalAssignment;
import com.mineledger.education.Accounting101;
import com.mineledger.education.CourseProgress;
import com.mineledger.education.AssignmentDirectory;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

public final class MineLedgerScreen extends ScaledAppScreen {
    private enum Section {
        COURSES("courses"), ASSIGNMENTS("assignments"), PROGRESS("progress");

        private final String key;

        Section(String key) {
            this.key = "screen.mineledger." + key;
        }

        Component label() {
            return Component.translatable(key);
        }
    }

    private final MineOsDesktopScreen desktop;
    private final CourseBrowser courses = new CourseBrowser(Accounting101.CATALOG);
    private final AssignmentDirectory assignments = new AssignmentDirectory(Accounting101.CATALOG);
    private ScrollableLayout assignmentList;
    private double assignmentScroll;
    private final EnumMap<Section, Button> navigation = new EnumMap<>(Section.class);
    private Section selected = Section.COURSES;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;

    public MineLedgerScreen(MineOsDesktopScreen desktop) {
        super(Component.translatable("screen.mineledger.title"));
        this.desktop = desktop;
    }

    @Override
    protected void init() {
        if (assignmentList != null) assignmentScroll = assignmentList.getScrollAmount();
        assignmentList = null;
        panelWidth = 600;
        left = 0;
        top = 0;
        navigation.clear();
        int tabWidth = (panelWidth - 32 - 12) / 3;
        for (Section section : Section.values()) {
            Button tab = addRenderableWidget(Button.builder(section.label(), button -> select(section))
                    .bounds(left + 16 + section.ordinal() * (tabWidth + 6), top + 40, tabWidth, 20).build());
            navigation.put(section, tab);
        }
        navigation.forEach((key, button) -> button.active = key != selected);
        var session = JournalSession.mastery();
        LinearLayout body = LinearLayout.vertical().spacing(10);
        Component message = switch (selected) {
            case COURSES, ASSIGNMENTS -> Component.empty();
            case PROGRESS -> progressMessage(session);
        };
        if (selected == Section.COURSES) {
            courses.populate(body, panelWidth - 76, font, JournalSession.mastery(), this::rebuildWidgets,
                    (service, recharge) -> minecraft.gui.setScreen(new ConceptAssignmentScreen(this, service, recharge)));
        } else if (selected == Section.ASSIGNMENTS) {
            String previousCourse = null;
            for (var group : assignments.groups(session)) {
                if (!group.course().id().equals(previousCourse)) {
                    AssignmentRows.text(body, panelWidth - 76, font, group.course().title());
                    previousCourse = group.course().id();
                }
                AssignmentRows.text(body, panelWidth - 76, font, "Unit " + group.unit().number() + ": " + group.unit().title());
                AssignmentRows.append(body, panelWidth - 76, font, group.entries(), id -> {
                    assignments.select(id, session).ifPresent(item -> {
                        if (assignmentList != null) assignmentScroll = assignmentList.getScrollAmount();
                        minecraft.gui.setScreen(new ConceptAssignmentScreen(this, item.service(), item.recharge()));
                    });
                });
            }
            if (Accounting101.CATALOG.courses().isEmpty()) AssignmentRows.text(body, panelWidth - 76, font, "No assignments available yet.");
        } else {
            body.addChild(new MultiLineTextWidget(message.copy().withStyle(style -> style.withColor(0x35383D).withoutShadow()),
                    font).setMaxWidth(panelWidth - 76));
        }
        body.arrangeElements();
        panelHeight = selected == Section.ASSIGNMENTS ? Math.max(220, Math.min(360, body.getHeight() + 153))
                : Math.max(220, body.getHeight() + 153);
        fitWindow(panelWidth, panelHeight);
        if (selected == Section.ASSIGNMENTS && body.getHeight() > panelHeight - 153) {
            assignmentList = new ScrollableLayout(minecraft, body, panelHeight - 153, ScrollableLayout.ReserveStrategy.RIGHT);
            assignmentList.arrangeElements();
            assignmentList.setX(left + 28);
            assignmentList.setY(top + 103);
            assignmentList.visitWidgets(widget -> {
                addRenderableWidget(widget);
                if (widget instanceof AbstractScrollArea area) area.setScrollAmount(assignmentScroll);
            });
        } else {
            body.setX(left + 28);
            body.setY(top + 103);
            body.visitWidgets(this::addRenderableWidget);
        }
        if (selected == Section.ASSIGNMENTS) {
            addRenderableWidget(Button.builder(Component.literal("Practice / Lab: Journal Entries"),
                    button -> minecraft.gui.setScreen(new JournalEntryScreen(this)))
                    .bounds(left + panelWidth - 252, top + panelHeight - 32, 236, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("screen.mineledger.desktop"),
                button -> minecraft.gui.setScreen(desktop))
                .bounds(left + 16, top + panelHeight - 32, Math.min(160, panelWidth - 32), 20).build());
    }

    private Component progressMessage(LearningSession session) {
        CourseProgress totals = new CourseProgress(session);
        StringBuilder text = new StringBuilder("Local profile progress\n");
        for (var course : Accounting101.CATALOG.courses()) {
            text.append(course.title()).append('\n').append(totals.course(course).assignmentsLabel()).append("\n\n");
        }
        for (var assignment : Accounting101.CATALOG.availableAssignments()) {
            var service = session.open(assignment);
            text.append(assignment.title()).append('\n').append(service.completedConcepts()).append(" / ")
                    .append(assignment.concepts().size()).append(" concepts completed; ").append(service.xp()).append(" / ")
                    .append(assignment.concepts().size() * 20).append(" mastery XP\n")
                    .append(assignment.concepts().stream().map(concept -> concept.title() + ": "
                            + service.progress().concept(concept.id()).credits() + "/" + concept.requiredCorrectAnswers())
                            .collect(Collectors.joining("\n"))).append("\n\n");
        }
        text.append("Practice / Lab: Journal Entries\n").append(JournalSession.progress().correctCount()).append(" / ")
                .append(IntroJournalAssignment.QUESTIONS.size()).append(" questions correct; ").append(JournalSession.progress().xp())
                .append(" learning XP. Each lab question awards 10 XP only once.\n").append(JournalSession.storageStatus());
        return Component.literal(text.toString());
    }

    private void select(Section section) {
        selected = section;
        rebuildWidgets();
    }

    @Override
    protected void extractWindowBackground(GuiGraphicsExtractor graphics) {
        graphics.fill(left + 3, top + 3, left + panelWidth + 3, top + panelHeight + 3, 0xFFD5D6DA);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFFFFFFFF);
        graphics.outline(left, top, panelWidth, panelHeight, 0xFFCFD1D5);
        graphics.fill(left + 1, top + 1, left + panelWidth - 1, top + 30, 0xFFB6202D);
        graphics.fill(left + 16, top + 11, left + 20, top + 20, 0xFFFFFFFF);
        graphics.text(font, title, left + 28, top + 11, 0xFFFFFFFF, false);
        graphics.fill(left + 16, top + 72, left + panelWidth - 16, top + panelHeight - 44, 0xFFF7F7F8);
        graphics.text(font, selected.label(), left + 28, top + 84, 0xFFB6202D, false);
    }

    @Override
    public void onClose() {
        if (selected == Section.COURSES && courses.back()) rebuildWidgets();
        else minecraft.gui.setScreen(desktop);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
