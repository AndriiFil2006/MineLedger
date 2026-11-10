package com.mineledger.client.screen;

import com.mineledger.accounting.mastery.ConceptProgressionService;
import com.mineledger.presentation.WindowViewport;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Read-only mastery overview. Bars fill from the bottom and wrap before becoming too narrow. */
final class ConceptProgressStripWidget extends AbstractWidget {
    private final Font font;
    private final ConceptProgressionService service;
    private final String currentConceptId;
    private final int columns;
    private final int barWidth;
    private int hoveredConcept = -1;
    private final Supplier<WindowViewport> viewport;
    private final WidgetTooltipHolder scaledTooltip = new WidgetTooltipHolder();

    ConceptProgressStripWidget(Font font, int width, ConceptProgressionService service, String currentConceptId,
            Supplier<WindowViewport> viewport) {
        super(0, 0, width, 0, Component.literal(service.completedConcepts() + " of "
                + service.assignment().concepts().size() + " concepts completed"));
        this.font = font;
        this.service = service;
        this.currentConceptId = currentConceptId;
        this.viewport = viewport;
        int count = service.assignment().concepts().size();
        int maxColumns = Math.max(1, width / 12);
        columns = count <= maxColumns ? count : Math.min(maxColumns, (count + 1) / 2);
        barWidth = Math.min(24, (width - (columns - 1) * 4) / columns);
        setHeight(13 + ((count + columns - 1) / columns) * 14);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.text(font, getMessage(), getX(), getY(), AssignmentStyle.MUTED, false);
        Component hovered = null;
        int hoveredIndex = -1;
        for (int i = 0; i < service.assignment().concepts().size(); i++) {
            var concept = service.assignment().concepts().get(i);
            var progress = service.progress().concept(concept.id());
            int x = getX() + (i % columns) * (barWidth + 4);
            int y = getY() + 12 + (i / columns) * 14;
            int color = progress.completed(concept) ? AssignmentStyle.SUCCESS : AssignmentStyle.ACCENT;
            graphics.fill(x, y, x + barWidth, y + 10, 0xFFF0E8EA);
            int fillHeight = Math.min(8, 8 * progress.credits() / concept.requiredCorrectAnswers());
            if (fillHeight > 0) graphics.fill(x + 1, y + 9 - fillHeight, x + barWidth - 1, y + 9, color);
            boolean current = concept.id().equals(currentConceptId);
            graphics.outline(x, y, barWidth, 10, current ? AssignmentStyle.ACCENT : AssignmentStyle.BORDER);
            if (current) graphics.fill(x, y + 11, x + barWidth, y + 13, AssignmentStyle.ACCENT);
            if (mouseX >= x && mouseX < x + barWidth && mouseY >= y && mouseY < y + 13) {
                hoveredIndex = i;
                hovered = Component.literal((i + 1) + ". " + concept.title() + " — " + progress.credits()
                        + "/" + concept.requiredCorrectAnswers() + (current ? " (current)" : ""));
            }
        }
        if (hoveredIndex != hoveredConcept) {
            hoveredConcept = hoveredIndex;
            setTooltip(hovered == null ? null : Tooltip.create(hovered));
        }
    }

    @Override
    public void setTooltip(Tooltip tooltip) { scaledTooltip.set(tooltip); }

    @Override
    protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // Deferred tooltips render after the window pose has been popped: use screen coordinates.
        WindowViewport view = viewport.get();
        ScreenRectangle bounds = new ScreenRectangle((int) Math.floor(view.screenX(getX())),
                (int) Math.floor(view.screenY(getY())), (int) Math.ceil(getWidth() * view.scale()),
                (int) Math.ceil(getHeight() * view.scale()));
        scaledTooltip.refreshTooltipForNextRenderPass(graphics, (int) view.screenX(mouseX), (int) view.screenY(mouseY),
                isHovered(), isFocused(), bounds);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
        scaledTooltip.updateNarration(output);
    }
}
