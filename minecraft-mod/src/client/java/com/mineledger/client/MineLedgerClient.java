package com.mineledger.client;

import net.fabricmc.api.ClientModInitializer;
import com.mineledger.block.MineLedgerBlocks;
import com.mineledger.client.screen.MineOsDesktopScreen;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;

public class MineLedgerClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() && !player.isSpectator()
					&& level.getBlockState(hit.getBlockPos()).is(MineLedgerBlocks.COMPUTER)) {
				Minecraft.getInstance().gui.setScreen(new MineOsDesktopScreen());
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
	}
}
