package com.catherinepereira.mcdrone;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import org.jspecify.annotations.Nullable;

/**
 * The drone remote selects instead of mining or placing: left click sets source corner A, right click corner B,
 * sneak and right click the paste point on the clicked face, right click into the air opens the job screen.
 * Both sides cancel the vanilla action, the client side reports the click to the selection
 */
public final class RemoteInput {
	public enum Target {
		CORNER_A,
		CORNER_B,
		DEST
	}

	public interface Handler {
		void select(Target target, BlockPos pos);

		/** Right click into the air, opens the job screen */
		void openJobs();
	}

	public static @Nullable Handler handler;

	private RemoteInput() {
	}

	public static void register() {
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (!player.getItemInHand(hand).is(ModContent.REMOTE)) {
				return InteractionResult.PASS;
			}
			if (level.isClientSide() && handler != null) {
				handler.select(Target.CORNER_A, pos.immutable());
			}
			return InteractionResult.SUCCESS;
		});
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!player.getItemInHand(hand).is(ModContent.REMOTE)) {
				return InteractionResult.PASS;
			}
			if (level.isClientSide() && handler != null) {
				handler.openJobs();
			}
			return InteractionResult.SUCCESS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!player.getItemInHand(hand).is(ModContent.REMOTE)) {
				return InteractionResult.PASS;
			}
			if (level.isClientSide() && handler != null) {
				boolean paste = player.isShiftKeyDown();
				handler.select(paste ? Target.DEST : Target.CORNER_B, paste ? hit.getBlockPos().relative(hit.getDirection()) : hit.getBlockPos().immutable());
			}
			return InteractionResult.SUCCESS;
		});
	}
}
