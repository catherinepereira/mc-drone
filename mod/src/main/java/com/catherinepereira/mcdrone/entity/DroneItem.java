package com.catherinepereira.mcdrone.entity;

import com.catherinepereira.mcdrone.ModContent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

public class DroneItem extends Item {
	public DroneItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		if (player == null) {
			return InteractionResult.PASS;
		}
		if (context.getLevel() instanceof ServerLevel level) {
			Vec3 pos = Vec3.atCenterOf(context.getClickedPos().relative(context.getClickedFace()));
			spawnFor(level, player, pos, player.getYRot());
		}
		return InteractionResult.SUCCESS;
	}

	/** Each player has one drone, spawning a new one removes the old */
	public static DroneEntity spawnFor(ServerLevel level, Player player, Vec3 pos, float yaw) {
		for (DroneEntity existing : level.getEntities(ModContent.DRONE, d -> d.isOwnedBy(player))) {
			Containers.dropContents(level, existing, existing.inventory);
			existing.discard();
		}
		DroneEntity drone = new DroneEntity(ModContent.DRONE, level);
		drone.setOwner(player.getUUID());
		drone.snapTo(pos.x, pos.y - 0.2, pos.z, yaw, 0.0F);
		level.addFreshEntity(drone);
		return drone;
	}
}
