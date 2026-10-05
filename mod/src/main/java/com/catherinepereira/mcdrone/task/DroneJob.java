package com.catherinepereira.mcdrone.task;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

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

	static JsonArray box(BlockPos min, BlockPos max) {
		JsonArray a = new JsonArray();
		a.add(min.getX());
		a.add(min.getY());
		a.add(min.getZ());
		a.add(max.getX());
		a.add(max.getY());
		a.add(max.getZ());
		return a;
	}

	static boolean inside(BlockPos p, BlockPos min, BlockPos max) {
		return p.getX() >= min.getX() && p.getX() <= max.getX() && p.getY() >= min.getY() && p.getY() <= max.getY() && p.getZ() >= min.getZ()
			&& p.getZ() <= max.getZ();
	}
}
