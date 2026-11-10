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
        super(0, 0, width, Math.max(22, font.split(Component.literal(text), width - 34).size() * font.lineHeight + 10),
                Component.literal(text));
        this.font = font;
        this.lines = font.split(getMessage(), width - 34);
        this.selected = selected;
        this.select = select;
    }

    @Override
    public void onPress(InputWithModifiers input) { select.run(); }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int x = getX();
        int y = getY();
        graphics.fill(x, y, x + getWidth(), y + getHeight(),
                selected.getAsBoolean() ? AssignmentStyle.SELECTED : isHoveredOrFocused() && active ? 0xFFFFF4F5 : 0xFFFFFFFF);
        graphics.outline(x, y, getWidth(), getHeight(),
                selected.getAsBoolean() || isHoveredOrFocused() && active ? AssignmentStyle.ACCENT : AssignmentStyle.BORDER);
        int markerY = y + (getHeight() - 8) / 2;
        graphics.outline(x + 8, markerY, 8, 8, AssignmentStyle.MUTED);
        if (selected.getAsBoolean()) graphics.fill(x + 10, markerY + 2, x + 14, markerY + 6, AssignmentStyle.ACCENT);
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(font, lines.get(i), x + 24, y + (getHeight() - lines.size() * font.lineHeight) / 2
                    + i * font.lineHeight, AssignmentStyle.INK, false);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
        output.add(net.minecraft.client.gui.narration.NarratedElementType.USAGE,
                Component.literal(selected.getAsBoolean() ? "Selected answer" : "Select this answer"));
    }
}
