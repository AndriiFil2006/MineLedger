package com.mineledger.client;

import net.fabricmc.api.ClientModInitializer;
import com.mineledger.curriculum.CurriculumContent;
import com.mineledger.block.MineLedgerBlocks;
import com.mineledger.client.screen.MineOsDesktopScreen;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;

public class MineLedgerClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CurriculumContent.get(); // Fail clearly before opening any exercises if required content is invalid.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> JournalSession.save());
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
