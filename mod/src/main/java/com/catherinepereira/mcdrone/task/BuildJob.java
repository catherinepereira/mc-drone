package com.catherinepereira.mcdrone.task;

import com.catherinepereira.mcdrone.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Build a target at a paste point. A copy job's target is the source box, snapshotted when the job starts so scoring
 * compares against what was there then. A build job's target is a schematic file.
 * The drone is told the boxes and, for a build job, the schematic name, never the copy snapshot: it reads that with its camera
 */
public final class BuildJob implements DroneJob {
	public static final int MAX_SIZE = 16;

	public final Schematic target;
	public final BlockPos dest;
	public final @Nullable BlockPos sourceMin;
	public final @Nullable String schematic;
	// where the drone may dig for materials, when it has to gather them
	public @Nullable BlockPos gatherMin;
	public @Nullable BlockPos gatherMax;

	private BuildJob(Schematic target, BlockPos dest, @Nullable BlockPos sourceMin, @Nullable String schematic) {
		DroneJob.checkSize(target.size(), MAX_SIZE, "build");
		this.target = target;
		this.dest = dest;
		this.sourceMin = sourceMin;
		this.schematic = schematic;
	}

	/** Corners in any order, dest is where the source's min corner lands */
	public static BuildJob copy(ServerLevel level, BlockPos a, BlockPos b, BlockPos dest) {
		BlockPos min = BlockPos.min(a, b);
		BlockPos max = BlockPos.max(a, b);
		DroneJob.checkSize(max.subtract(min).offset(1, 1, 1), MAX_SIZE, "copy region");
		return new BuildJob(Schematic.fromWorld(level, min, max), dest, min, null);
	}

	public static BuildJob build(Schematic target, String name, BlockPos dest) {
		return new BuildJob(target, dest, null, name);
	}

	/** Corners in any order of the box the drone mines its materials from */
	public BuildJob gatherFrom(BlockPos a, BlockPos b) {
		this.gatherMin = BlockPos.min(a, b);
		this.gatherMax = BlockPos.max(a, b);
		return this;
	}

	public BlockPos destMax() {
		return this.dest.offset(this.target.size()).offset(-1, -1, -1);
	}

	/** Destination cells that match the target, non-air cells in the target, and destination blocks that don't belong */
	@Override
	public int[] score(ServerLevel level) {
		int correct = 0;
		int total = 0;
		int wrong = 0;
		for (int y = 0; y < this.target.height; y++) {
			for (int z = 0; z < this.target.length; z++) {
				for (int x = 0; x < this.target.width; x++) {
					BlockState expected = this.target.get(x, y, z);
					BlockState actual = level.getBlockState(this.dest.offset(x, y, z));
					boolean match = actual.is(expected.getBlock());
					if (!expected.isAir()) {
						total++;
						correct += match ? 1 : 0;
					}
					if (!actual.isAir() && !match) {
						wrong++;
					}
				}
			}
		}
		return new int[] {correct, total, wrong};
	}

	/** Building and clearing wrong blocks happens in the destination box, mining for materials in the gather box */
	@Override
	public boolean allows(BlockPos pos) {
		return DroneJob.inside(pos, this.dest, this.destMax()) || (this.gatherMin != null && DroneJob.inside(pos, this.gatherMin, this.gatherMax));
	}

	@Override
	public BlockPos[] corners() {
		List<BlockPos> out = new ArrayList<>(List.of(this.dest, this.destMax()));
		if (this.sourceMin != null) {
			out.add(this.sourceMin);
			out.add(this.sourceMin.offset(this.target.size()).offset(-1, -1, -1));
		}
		if (this.gatherMin != null) {
			out.add(this.gatherMin);
			out.add(this.gatherMax);
		}
		return out.toArray(BlockPos[]::new);
	}

	/** What the drone is told: the boxes as inclusive min and max corners, and the schematic for a build job */
	@Override
	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("kind", this.sourceMin != null ? "copy" : "build");
		if (this.sourceMin != null) {
			json.add("source", Json.box(this.sourceMin, this.sourceMin.offset(this.target.size()).offset(-1, -1, -1)));
		}
		if (this.schematic != null) {
			json.addProperty("schematic", this.schematic);
		}
		json.add("dest", Json.box(this.dest, this.destMax()));
		if (this.gatherMin != null) {
			json.add("gather", Json.box(this.gatherMin, this.gatherMax));
		}
		JsonArray size = new JsonArray();
		size.add(this.target.width);
		size.add(this.target.height);
		size.add(this.target.length);
		json.add("size", size);
		return json;
	}
}
