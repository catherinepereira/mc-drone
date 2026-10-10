package com.catherinepereira.mcdrone.task;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** Work a player hands the drone in their own world, or a training arena's equivalent */
public interface DroneJob {
	/** The task's metrics, see ArenaRecord.metrics */
	int[] score(ServerLevel level);

	/** What the drone is told, it never includes anything the drone should find out with its camera */
	JsonObject toJson();

	/** Whether the drone may break or build at pos while doing this job, on top of the world's safe regions */
	boolean allows(BlockPos pos);

	/** The boxes the job works in, as inclusive min and max corners, for the geofence and the reach check */
	BlockPos[] corners();

	/** Whether the drone's beam may lock on to entity while doing this job, players and drones it never may */
	default boolean mayAttack(Entity entity) {
		return false;
	}

	/** Whether the job breaks or places blocks, two jobs that do can't share a block */
	default boolean changesBlocks() {
		return true;
	}

	/** Throws when a box of this size is longer than maxSide on any side, what names the box in the message */
	static void checkSize(Vec3i size, int maxSide, String what) {
		if (size.getX() > maxSide || size.getY() > maxSide || size.getZ() > maxSide) {
			throw new IllegalArgumentException(what + " " + size.toShortString() + " is larger than " + maxSide + " per side");
		}
	}

	/** Whether two inclusive boxes share a cell */
	static boolean overlaps(BlockPos aMin, BlockPos aMax, BlockPos bMin, BlockPos bMax) {
		return aMin.getX() <= bMax.getX() && bMin.getX() <= aMax.getX() && aMin.getY() <= bMax.getY() && bMin.getY() <= aMax.getY()
			&& aMin.getZ() <= bMax.getZ() && bMin.getZ() <= aMax.getZ();
	}

	static boolean inside(BlockPos p, BlockPos min, BlockPos max) {
		return p.getX() >= min.getX() && p.getX() <= max.getX() && p.getY() >= min.getY() && p.getY() <= max.getY() && p.getZ() >= min.getZ()
			&& p.getZ() <= max.getZ();
	}
}
