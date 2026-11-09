package com.mineledger.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class MineLedgerScreen extends Screen {
	public MineLedgerScreen() {
		super(Component.translatable("screen.mineledger.title"));
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("screen.mineledger.close"),
				button -> onClose()).bounds(width / 2 - 50, height / 2 + 30, 100, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(font, title, width / 2, height / 2 - 50, 0xFFFFFFFF);
		graphics.centeredText(font, Component.translatable("screen.mineledger.welcome"),
				width / 2, height / 2 - 10, 0xFFFFFFFF);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
