package com.catherinepereira.mcdrone.entity;

import com.catherinepereira.mcdrone.ModContent;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Which of a player's drones jobs, arenas, and piloting use. A player can own any number of drones, the tablet picks
 * the active one. Without a pick, or when the picked one is gone, it's the nearest
 */
public final class Drones {
	private static final Map<UUID, UUID> ACTIVE = new HashMap<>();

	private Drones() {
	}

	public static List<DroneEntity> owned(ServerPlayer player) {
		ServerLevel level = player.level();
		return List.copyOf(level.getEntities(ModContent.DRONE, d -> d.isOwnedBy(player)));
	}

	public static @Nullable DroneEntity active(ServerPlayer player) {
		List<DroneEntity> owned = owned(player);
		UUID picked = ACTIVE.get(player.getUUID());
		for (DroneEntity drone : owned) {
			if (drone.getUUID().equals(picked)) {
				return drone;
			}
		}
		return owned.stream().min(Comparator.comparingDouble(d -> d.distanceToSqr(player))).orElse(null);
	}

	/** The drone a request names by entity id, or the active one for a negative id. Throws when the player doesn't own it */
	public static @Nullable DroneEntity resolve(ServerPlayer player, int entityId) {
		if (entityId < 0) {
			return active(player);
		}
		if (player.level().getEntity(entityId) instanceof DroneEntity drone && drone.isOwnedBy(player)) {
			return drone;
		}
		throw new IllegalArgumentException("drone " + entityId + " isn't one of yours or isn't loaded");
	}

	/** A loaded drone other than except whose home is the station at pos, or null */
	public static @Nullable DroneEntity homedAt(ServerLevel level, BlockPos pos, DroneEntity except) {
		for (DroneEntity drone : level.getEntities(ModContent.DRONE, d -> d != except && pos.equals(d.home()))) {
			return drone;
		}
		return null;
	}

	public static void setActive(ServerPlayer player, DroneEntity drone) {
		ACTIVE.put(player.getUUID(), drone.getUUID());
	}
}
