package com.mineledger.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** One native button makes both the ledger symbol and its label clickable. */
final class MineLedgerAppIcon extends AbstractButton {
    private final Font font;
    private final Runnable open;

    MineLedgerAppIcon(int x, int y, Font font, Runnable open) {
        super(x, y, 100, 76, Component.translatable("screen.mineledger.title"));
        this.font = font;
        this.open = open;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        open.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int x = getX();
        int y = getY();
        graphics.fill(x, y, x + getWidth(), y + getHeight(),
                isHoveredOrFocused() ? 0xFFFBE9EB : 0xFFFFFFFF);
        if (isHoveredOrFocused()) {
            graphics.outline(x, y, getWidth(), getHeight(), 0xFFC62834);
        }
        // Original pixel ledger: spine, cover, ruled entries and amount column.
        int bookX = x + getWidth() / 2 - 17;
        int bookY = y + 9;
        graphics.fill(bookX + 3, bookY + 3, bookX + 36, bookY + 39, 0xFFD5D6DA);
        graphics.fill(bookX, bookY, bookX + 33, bookY + 36, 0xFFC62834);
        graphics.fill(bookX, bookY, bookX + 5, bookY + 36, 0xFF8E1822);
        graphics.fill(bookX + 9, bookY + 6, bookX + 28, bookY + 8, 0xFFFFFFFF);
        for (int row = 0; row < 3; row++) {
            int lineY = bookY + 14 + row * 6;
            graphics.fill(bookX + 9, lineY, bookX + 20, lineY + 2, 0xFFF6CCD0);
            graphics.fill(bookX + 24, lineY, bookX + 28, lineY + 2, 0xFFFFFFFF);
        }
        graphics.text(font, getMessage(), x + (getWidth() - font.width(getMessage())) / 2,
                y + 58, 0xFF252525, false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
