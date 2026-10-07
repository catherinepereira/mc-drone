package com.catherinepereira.mcdrone;

import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.entity.Drones;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import org.jspecify.annotations.Nullable;

/**
 * The tablet selects instead of mining or placing: left click sets corner A, right click corner B, sneak and right click
 * the paste point on the clicked face. Right clicking a charging station makes it the active drone's home, and right
 * clicking into the air opens the tablet screen. Both sides cancel the vanilla action, the client side reports the click
 */
public final class TabletInput {
	public enum Target {
		CORNER_A,
		CORNER_B,
		DEST
	}

	public interface Handler {
		void select(Target target, BlockPos pos);

		/** Right click into the air, opens the tablet screen */
		void openTablet();
	}

	public static @Nullable Handler handler;

	private TabletInput() {
	}

	public static void register() {
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (!player.getItemInHand(hand).is(ModContent.TABLET)) {
				return InteractionResult.PASS;
			}
			if (level.isClientSide() && handler != null) {
				handler.select(Target.CORNER_A, pos.immutable());
			}
			return InteractionResult.SUCCESS;
		});
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!player.getItemInHand(hand).is(ModContent.TABLET)) {
				return InteractionResult.PASS;
			}
			if (level.isClientSide() && handler != null) {
				handler.openTablet();
			}
			return InteractionResult.SUCCESS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!player.getItemInHand(hand).is(ModContent.TABLET)) {
				return InteractionResult.PASS;
			}
			BlockPos pos = hit.getBlockPos().immutable();
			if (level.getBlockState(pos).is(ModContent.CHARGING_STATION)) {
				if (player instanceof ServerPlayer serverPlayer) {
					DroneEntity drone = Drones.active(serverPlayer);
					if (drone != null) {
						drone.setHome(pos);
					}
					serverPlayer.sendOverlayMessage(Component.literal(
						drone == null ? "Place a drone first" : drone.getCustomName().getString() + " charges at " + pos.toShortString()
					));
				}
				return InteractionResult.SUCCESS;
			}
			if (level.isClientSide() && handler != null) {
				boolean paste = player.isShiftKeyDown();
				handler.select(paste ? Target.DEST : Target.CORNER_B, paste ? pos.relative(hit.getDirection()) : pos);
			}
			return InteractionResult.SUCCESS;
		});
	}
}
