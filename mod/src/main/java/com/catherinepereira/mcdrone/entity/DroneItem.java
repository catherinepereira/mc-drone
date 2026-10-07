package com.catherinepereira.mcdrone.entity;

import com.catherinepereira.mcdrone.ModContent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

/** Places a drone of its tier, owned by the player, which becomes their active drone */
public class DroneItem extends Item {
	public final DroneTier tier;

	public DroneItem(Properties properties, DroneTier tier) {
		super(properties);
		this.tier = tier;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		if (player == null) {
			return InteractionResult.PASS;
		}
		if (context.getLevel() instanceof ServerLevel level && player instanceof ServerPlayer serverPlayer) {
			Vec3 pos = Vec3.atCenterOf(context.getClickedPos().relative(context.getClickedFace()));
			spawnFor(level, serverPlayer, pos, player.getYRot(), this.tier);
			context.getItemInHand().consume(1, player);
		}
		return InteractionResult.SUCCESS;
	}

	public static DroneEntity spawnFor(ServerLevel level, ServerPlayer player, Vec3 pos, float yaw, DroneTier tier) {
		DroneEntity drone = new DroneEntity(ModContent.DRONE, level);
		drone.setOwner(player);
		drone.setTier(tier);
		drone.snapTo(pos.x, pos.y - 0.2, pos.z, yaw, 0.0F);
		level.addFreshEntity(drone);
		Drones.setActive(player, drone);
		return drone;
	}
}
