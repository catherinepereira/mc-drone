package com.catherinepereira.mcdrone.task;

import com.catherinepereira.mcdrone.Json;
import com.catherinepereira.mcdrone.entity.DroneTier;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/**
 * Mine every block of the chosen kinds inside a box. The drone is told the box and the kinds, it finds the blocks itself.
 * It may only break inside the box, so a mining job never digs out of its region
 */
public final class MineJob implements DroneJob {
	public static final int MAX_SIDE = 32;

	public final BlockPos min;
	public final BlockPos max;
	public final Set<Block> blocks;
	private final int initial;

	private MineJob(BlockPos min, BlockPos max, Set<Block> blocks, int initial) {
		this.min = min;
		this.max = max;
		this.blocks = blocks;
		this.initial = initial;
	}

	/**
	 * blockNames lists the kinds to mine separated by commas or spaces, such as "coal_ore, minecraft:iron_ore".
	 * Kinds the tier breaks without a drop are left out, and the job fails when that leaves none
	 */
	public static MineJob start(ServerLevel level, BlockPos a, BlockPos b, String blockNames, DroneTier tier) {
		Set<Block> blocks = new HashSet<>();
		List<String> skipped = new ArrayList<>();
		for (String name : blockNames.trim().split("[,\\s]+")) {
			Identifier id = Identifier.tryParse(name);
			Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
			if (block == null || block.defaultBlockState().isAir()) {
				throw new IllegalArgumentException("unknown block '" + name + "'");
			}
			if (tier.canHarvest(block)) {
				blocks.add(block);
			} else {
				skipped.add(BuiltInRegistries.BLOCK.getKey(block).getPath());
			}
		}
		if (blocks.isEmpty()) {
			throw new IllegalArgumentException("a " + tier.id + " drone gets nothing from " + String.join(", ", skipped));
		}
		BlockPos min = BlockPos.min(a, b);
		BlockPos max = BlockPos.max(a, b);
		DroneJob.checkSize(max.subtract(min).offset(1, 1, 1), MAX_SIDE, "mining region");
		MineJob job = new MineJob(min, max, Set.copyOf(blocks), 0);
		int count = job.remaining(level);
		if (count == 0) {
			throw new IllegalArgumentException("there is no " + blockNames.trim() + " in that region");
		}
		return new MineJob(min, max, job.blocks, count);
	}

	public int remaining(ServerLevel level) {
		int n = 0;
		for (BlockPos p : BlockPos.betweenClosed(this.min, this.max)) {
			n += this.blocks.contains(level.getBlockState(p).getBlock()) ? 1 : 0;
		}
		return n;
	}

	/** Blocks of the chosen kinds still in the box, and how many there were at the start */
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
		JsonArray blocks = new JsonArray();
		this.blocks.stream().map(b -> BuiltInRegistries.BLOCK.getKey(b).toString()).sorted().forEach(blocks::add);
		json.add("blocks", blocks);
		return json;
	}
}
