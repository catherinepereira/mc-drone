package com.catherinepereira.mcdrone.task;

import com.catherinepereira.mcdrone.Json;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Fly back to the drone's charging station. The client scores it, the job is done once the drone docks */
public final class DockJob implements DroneJob {
	private final BlockPos station;

	public DockJob(BlockPos station) {
		this.station = station.immutable();
	}

	@Override
	public int[] score(ServerLevel level) {
		return new int[0];
	}

	@Override
	public boolean allows(BlockPos pos) {
		return false;
	}

	@Override
	public BlockPos[] corners() {
		return new BlockPos[] {this.station, this.station.above()};
	}

	@Override
	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("kind", "return_home");
		json.add("station", Json.pos(this.station));
		return json;
	}
}
