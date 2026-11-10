package com.mineledger.client.screen;

import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Native accessible button with wrapped text and a radio-style selection marker. */
final class AnswerOptionButton extends AbstractButton {
    private final Font font;
    private final List<FormattedCharSequence> lines;
    private final BooleanSupplier selected;
    private final Runnable select;

    AnswerOptionButton(Font font, int width, String text, BooleanSupplier selected, Runnable select) {
        super(0, 0, width, Math.max(26, font.split(Component.literal(text), width - 36).size() * font.lineHeight + 14),
                Component.literal(text));
        this.font = font;
        this.lines = font.split(getMessage(), width - 36);
        this.selected = selected;
        this.select = select;
    }

    @Override
    public void onPress(InputWithModifiers input) { select.run(); }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int x = getX();
        int y = getY();
        graphics.fill(x, y, x + getWidth(), y + getHeight(), selected.getAsBoolean() ? 0xFFF2DDDF : 0xFFF7F7F8);
        graphics.outline(x, y, getWidth(), getHeight(),
                selected.getAsBoolean() || isHoveredOrFocused() ? 0xFFB6202D : 0xFFCFD1D5);
        graphics.outline(x + 8, y + 8, 9, 9, 0xFF55585D);
        if (selected.getAsBoolean()) graphics.fill(x + 10, y + 10, x + 15, y + 15, 0xFFB6202D);
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(font, lines.get(i), x + 25, y + 7 + i * font.lineHeight, 0xFF35383D, false);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
        output.add(net.minecraft.client.gui.narration.NarratedElementType.USAGE,
                Component.literal(selected.getAsBoolean() ? "Selected answer" : "Select this answer"));
    }
}
