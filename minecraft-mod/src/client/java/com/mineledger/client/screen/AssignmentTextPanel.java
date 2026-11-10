package com.mineledger.client.screen;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Measured, fully wrapped card: content is never clipped to a fixed line count. */
final class AssignmentTextPanel extends AbstractWidget {
    private final Font font;
    private final List<FormattedCharSequence> heading;
    private final List<FormattedCharSequence> body;
    private final String eyebrow;
    private final int accent;

    AssignmentTextPanel(Font font, int width, String eyebrow, String heading, String body, int accent) {
        super(0, 0, width, 0, Component.literal(eyebrow + " " + heading + ". " + body));
        this.font = font;
        this.eyebrow = eyebrow;
        this.heading = font.split(Component.literal(heading), width - 20);
        this.body = font.split(Component.literal(body), width - 20);
        this.accent = accent;
        setHeight(12 + (eyebrow.isEmpty() ? 0 : font.lineHeight + 3)
                + this.heading.size() * font.lineHeight + 4 + this.body.size() * font.lineHeight);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int x = getX();
        int y = getY();
        graphics.fill(x, y, x + getWidth(), y + getHeight(), AssignmentStyle.CARD);
        graphics.fill(x, y, x + 2, y + getHeight(), accent);
        int textY = y + 6;
        if (!eyebrow.isEmpty()) {
            graphics.text(font, eyebrow, x + 10, textY, accent, false);
            textY += font.lineHeight + 3;
        }
        for (var line : heading) {
            graphics.text(font, line, x + 10, textY, AssignmentStyle.INK, false);
            textY += font.lineHeight;
        }
        textY += 4;
        for (var line : body) {
            graphics.text(font, line, x + 10, textY, AssignmentStyle.MUTED, false);
            textY += font.lineHeight;
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
