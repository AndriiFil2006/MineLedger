package com.mineledger.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class MineOsDesktopScreen extends Screen {
    private int margin;
    private int taskbarY;

    public MineOsDesktopScreen() {
        super(Component.translatable("screen.mineos.title"));
    }

    @Override
    protected void init() {
        margin = Math.max(12, Math.min(width, height) / 16);
        taskbarY = height - 36;
        addRenderableWidget(new MineLedgerAppIcon(margin, Math.min(height / 3, taskbarY - 84), font,
                () -> minecraft.gui.setScreen(new MineLedgerScreen(this))));
        addRenderableWidget(Button.builder(Component.translatable("screen.mineos.shutdown"),
                button -> onClose()).bounds(width - margin - 100, taskbarY + 8, 100, 20).build());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fillGradient(0, 0, width, height, 0xFFFFFFFF, 0xFFF2F3F5);
        // Stepped terrain motif in scaled GUI units.
        int step = Math.max(16, Math.min(width, height) / 8);
        for (int i = 0; i < 4; i++) {
            int x = width - margin - (i + 1) * step;
            int y = taskbarY - margin - (4 - i) * step;
            graphics.fill(x, y, x + step - 3, taskbarY - margin, 0xFFF2DDDF);
        }
        graphics.fill(margin, margin, margin + 4, margin + 25, 0xFFC62834);
        graphics.text(font, title, margin + 12, margin + 2, 0xFF252525, false);
        graphics.text(font, Component.translatable("screen.mineos.subtitle"),
                margin + 12, margin + 16, 0xFF55585D, false);
        graphics.fill(0, taskbarY, width, height, 0xFFFFFFFF);
        graphics.fill(0, taskbarY, width, taskbarY + 1, 0xFFC62834);
        graphics.text(font, title, margin, taskbarY + 14, 0xFF55585D, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
