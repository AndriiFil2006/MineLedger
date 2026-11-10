package com.mineledger.client.screen;

import java.util.EnumMap;
import java.util.stream.Collectors;
import com.mineledger.client.JournalSession;
import com.mineledger.accounting.mastery.InventoryCreditAssignment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class MineLedgerScreen extends Screen {
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
        panelWidth = Math.min(720, width - 24);
        panelHeight = Math.min(400, height - 24);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        navigation.clear();
        int tabWidth = (panelWidth - 32 - 12) / 3;
        for (Section section : Section.values()) {
            Button tab = addRenderableWidget(Button.builder(section.label(), button -> select(section))
                    .bounds(left + 16 + section.ordinal() * (tabWidth + 6), top + 40, tabWidth, 20).build());
            navigation.put(section, tab);
        }
        navigation.forEach((key, button) -> button.active = key != selected);
        var mastery = JournalSession.MASTERY.open(InventoryCreditAssignment.ASSIGNMENT);
        LinearLayout body = LinearLayout.vertical().spacing(10);
        Component message = switch (selected) {
            case COURSES -> Component.translatable(selected.key + ".placeholder");
            case ASSIGNMENTS -> Component.literal(mastery.assignment().title() + "\n"
                    + "Master credit purchases and paying suppliers. Each concept needs two distinct correct answers. "
                    + "Retry as often as needed. Earn up to " + mastery.assignment().concepts().size() * 20
                    + " learning XP.\n\nIntroductory Journal Entries\n"
                    + "Practice three Minecraft shop transactions. Choose a debit, a credit, and an emerald amount. "
                    + "Earn up to 30 learning XP per client session.");
            case PROGRESS -> Component.literal("Temporary client session progress\n"
                    + "Concept mastery: " + mastery.completedConcepts()
                    + " / " + mastery.assignment().concepts().size() + " concepts completed; " + mastery.xp()
                    + " / " + mastery.assignment().concepts().size() * 20 + " mastery XP\n"
                    + mastery.assignment().concepts().stream().map(concept -> concept.title() + ": "
                            + mastery.progress().concept(concept.id()).credits() + "/" + concept.requiredCorrectAnswers())
                            .collect(Collectors.joining("\n")) + "\n\nJournal practice:\n"
                    + JournalSession.PROGRESS.correctCount() + " / 3 questions correctly answered\n"
                    + JournalSession.PROGRESS.xp() + " / 30 learning XP\n"
                    + "Each question awards 10 XP only once. These are learning points, not Minecraft experience. "
                    + "Nothing is saved; progress resets when Minecraft closes.");
        };
        body.addChild(new MultiLineTextWidget(message.copy().withStyle(style -> style.withColor(0x35383D).withoutShadow()),
                font).setMaxWidth(panelWidth - 76));
        if (selected == Section.ASSIGNMENTS) {
            body.addChild(Button.builder(Component.literal("Open Concept Mastery"),
                    button -> minecraft.gui.setScreen(new ConceptAssignmentScreen(this,
                            mastery)))
                    .size(Math.min(220, panelWidth - 76), 20).build());
            body.addChild(Button.builder(Component.literal("Open Journal Entries"),
                    button -> minecraft.gui.setScreen(new JournalEntryScreen(this)))
                    .size(Math.min(220, panelWidth - 76), 20).build());
        }
        ScrollableLayout scroll = new ScrollableLayout(minecraft, body, Math.max(1, panelHeight - 153));
        scroll.arrangeElements();
        scroll.setX(left + 28);
        scroll.setY(top + 103);
        scroll.visitWidgets(this::addRenderableWidget);
        addRenderableWidget(Button.builder(Component.translatable("screen.mineledger.desktop"),
                button -> minecraft.gui.setScreen(desktop))
                .bounds(left + 16, top + panelHeight - 32, Math.min(160, panelWidth - 32), 20).build());
    }

    private void select(Section section) {
        selected = section;
        rebuildWidgets();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xFFEDEEF0);
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
    public boolean isPauseScreen() {
        return false;
    }
}
