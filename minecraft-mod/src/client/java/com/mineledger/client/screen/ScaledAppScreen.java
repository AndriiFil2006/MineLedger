package com.mineledger.client.screen;

import com.mineledger.presentation.WindowViewport;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Shared local zoom for every MineOS window; the global Minecraft GUI scale stays unchanged. */
abstract class ScaledAppScreen extends AppScreen {
    protected WindowViewport viewport = new WindowViewport(1, 0, 0);
    protected int canvasWidth;
    protected int canvasHeight;

    protected ScaledAppScreen(Component title) { super(title); }

    protected final void fitWindow(int contentWidth, int contentHeight) {
        canvasWidth = contentWidth;
        canvasHeight = contentHeight;
        viewport = WindowViewport.fit(Math.max(1, width), Math.max(1, height), contentWidth, contentHeight);
    }

    @Override
    public final void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xE0ECE8E9);
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(viewport.x(), viewport.y());
            graphics.pose().scale(viewport.scale(), viewport.scale());
            extractWindowBackground(graphics);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    protected abstract void extractWindowBackground(GuiGraphicsExtractor graphics);

    @Override
    public final void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(viewport.x(), viewport.y());
            graphics.pose().scale(viewport.scale(), viewport.scale());
            super.extractRenderState(graphics, (int) Math.floor(viewport.localX(mouseX)),
                    (int) Math.floor(viewport.localY(mouseY)), delta);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private MouseButtonEvent localEvent(MouseButtonEvent event) {
        return new MouseButtonEvent(viewport.localX(event.x()), viewport.localY(event.y()), event.buttonInfo());
    }

    @Override
    public void mouseMoved(double x, double y) { super.mouseMoved(viewport.localX(x), viewport.localY(y)); }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return super.mouseClicked(localEvent(event), doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) { return super.mouseReleased(localEvent(event)); }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return super.mouseDragged(localEvent(event), dx / viewport.scale(), dy / viewport.scale());
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return super.mouseScrolled(viewport.localX(x), viewport.localY(y), horizontal, vertical);
    }

    @Override
    public ScreenRectangle getRectangle() { return new ScreenRectangle(0, 0, canvasWidth, canvasHeight); }
}
