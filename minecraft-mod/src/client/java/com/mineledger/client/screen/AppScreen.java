package com.mineledger.client.screen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Minecraft assigns click focus after callbacks, which may already have rebuilt the widgets. */
abstract class AppScreen extends Screen {
    protected AppScreen(Component title) { super(title); }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        try {
            return super.mouseClicked(event, doubleClick);
        } finally {
            if (getFocused() != null && !children().contains(getFocused())) {
                clearFocus();
                setDragging(false);
            }
        }
    }
}
