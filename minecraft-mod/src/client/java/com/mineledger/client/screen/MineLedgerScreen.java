package com.mineledger.client.screen;

import java.util.EnumMap;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
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
    private MultiLineTextWidget content;
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
        content = addRenderableOnly(new MultiLineTextWidget(left + 28, top + 103,
                Component.empty(), font).setMaxWidth(panelWidth - 56)
                .setMaxRows(Math.max(1, (panelHeight - 153) / font.lineHeight)));
        addRenderableWidget(Button.builder(Component.translatable("screen.mineledger.desktop"),
                button -> minecraft.gui.setScreen(desktop))
                .bounds(left + 16, top + panelHeight - 32, Math.min(160, panelWidth - 32), 20).build());
        select(selected);
    }

    private void select(Section section) {
        selected = section;
        navigation.forEach((key, button) -> button.active = key != selected);
        content.setMessage(Component.translatable(section.key + ".placeholder")
                .withStyle(style -> style.withColor(0x35383D).withoutShadow()));
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
