package com.mineledger.block;

import com.mineledger.MineLedger;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class MineLedgerBlocks {
	public static final Block COMPUTER = Registry.register(
			BuiltInRegistries.BLOCK, MineLedger.id("computer"),
			new Block(BlockBehaviour.Properties.of()
					.setId(ResourceKey.create(Registries.BLOCK, MineLedger.id("computer")))
					.strength(1.5F).sound(SoundType.METAL)));

	public static final BlockItem COMPUTER_ITEM = Registry.register(
			BuiltInRegistries.ITEM, MineLedger.id("computer"),
			new BlockItem(COMPUTER, new Item.Properties()
					.setId(ResourceKey.create(Registries.ITEM, MineLedger.id("computer")))
					.useBlockDescriptionPrefix()));

	private MineLedgerBlocks() {
	}

	public static void initialize() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
				.register(output -> output.accept(COMPUTER_ITEM));

		// The client opens the local screen. Consume its normal interaction packet
		// on the server so a held block/item cannot also be used on the computer.
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() && !player.isSpectator()
					&& level.getBlockState(hit.getBlockPos()).is(COMPUTER)) {
				return InteractionResult.CONSUME;
			}
			return InteractionResult.PASS;
		});
	}
}
