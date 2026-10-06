package com.catherinepereira.mcdrone.task;

import com.catherinepereira.mcdrone.Json;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/**
 * Mine every block of one kind inside a box. The drone is told the box and the block, it finds the blocks itself.
 * It may only break inside the box, so a mining job never digs out of its region
 */
public final class MineJob implements DroneJob {
	public static final int MAX_SIDE = 32;

	public final BlockPos min;
	public final BlockPos max;
	public final Block block;
	private final int initial;

	private MineJob(BlockPos min, BlockPos max, Block block, int initial) {
		this.min = min;
		this.max = max;
		this.block = block;
		this.initial = initial;
	}

	public static MineJob start(ServerLevel level, BlockPos a, BlockPos b, String blockName) {
		Identifier id = Identifier.tryParse(blockName);
		Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
		if (block == null || block.defaultBlockState().isAir()) {
			throw new IllegalArgumentException("unknown block '" + blockName + "'");
		}
		BlockPos min = BlockPos.min(a, b);
		BlockPos max = BlockPos.max(a, b);
		DroneJob.checkSize(max.subtract(min).offset(1, 1, 1), MAX_SIDE, "mining region");
		MineJob job = new MineJob(min, max, block, 0);
		int count = job.remaining(level);
		if (count == 0) {
			throw new IllegalArgumentException("there is no " + blockName + " in that region");
		}
		return new MineJob(min, max, block, count);
	}

	public int remaining(ServerLevel level) {
		int n = 0;
		for (BlockPos p : BlockPos.betweenClosed(this.min, this.max)) {
			n += level.getBlockState(p).is(this.block) ? 1 : 0;
		}
		return n;
	}

	/** Blocks of the kind still in the box, and how many there were at the start */
	@Override
	public int[] score(ServerLevel level) {
		return new int[] {this.remaining(level), this.initial};
	}

	@Override
	public boolean allows(BlockPos pos) {
		return DroneJob.inside(pos, this.min, this.max);
	}

	@Override
	public BlockPos[] corners() {
		return new BlockPos[] {this.min, this.max};
	}

	@Override
	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("kind", "mine");
		json.add("region", Json.box(this.min, this.max));
		json.addProperty("block", BuiltInRegistries.BLOCK.getKey(this.block).toString());
		return json;
	}
}
