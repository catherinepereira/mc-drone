package com.catherinepereira.mcdrone.task;

import com.catherinepereira.mcdrone.Json;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Get somewhere: to a point given as coordinates, to a block of a named kind the drone finds with its camera, or after
 * a moving player or mob, staying close without crowding it. The client scores all three from the drone's pose
 */
public final class GotoJob implements DroneJob {
	public enum Mode {
		POINT, BLOCK, FOLLOW
	}

	public static final int MAX_SIDE = 64;
	// a point or block counts as reached this close to its center
	public static final double ARRIVE = 1.5;
	// a follower stays this far from its target, and a training arena's follow ends after this many ticks in that band
	public static final double NEAR = 2.0;
	public static final double FAR = 5.0;
	public static final int FOLLOW_TICKS = 200;
	// a player's follow job keeps the drone within this many blocks of where the job started
	public static final int FOLLOW_REACH = 48;

	public final Mode mode;
	private final BlockPos min;
	private final BlockPos max;
	private final @Nullable Block block;
	private final int target;
	private final int ticks;

	private GotoJob(Mode mode, BlockPos min, BlockPos max, @Nullable Block block, int target, int ticks) {
		this.mode = mode;
		this.min = min;
		this.max = max;
		this.block = block;
		this.target = target;
		this.ticks = ticks;
	}

	public static GotoJob point(BlockPos point) {
		return new GotoJob(Mode.POINT, point, point, null, -1, 0);
	}

	/** A block of the named kind somewhere in the box between a and b, with where they are for the client's scoring */
	public static GotoJob block(ServerLevel level, BlockPos a, BlockPos b, String name, List<BlockPos> found) {
		Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
		Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
		if (block == null) {
			throw new IllegalArgumentException("unknown block " + name);
		}
		BlockPos min = BlockPos.min(a, b);
		BlockPos max = BlockPos.max(a, b);
		DroneJob.checkSize(max.subtract(min).offset(1, 1, 1), MAX_SIDE, "search region");
		for (BlockPos p : BlockPos.betweenClosed(min, max)) {
			if (level.getBlockState(p).is(block)) {
				found.add(p.immutable());
			}
		}
		if (found.isEmpty()) {
			throw new IllegalArgumentException("there is no " + name + " in that region");
		}
		return new GotoJob(Mode.BLOCK, min, max, block, -1, 0);
	}

	/** Follow target, for ticks ticks in the band or, with 0, until stopped */
	public static GotoJob follow(Entity target, int ticks) {
		BlockPos at = target.blockPosition();
		return new GotoJob(Mode.FOLLOW, at.offset(-FOLLOW_REACH, -8, -FOLLOW_REACH), at.offset(FOLLOW_REACH, 24, FOLLOW_REACH), null, target.getId(), ticks);
	}

	/** Static targets in the arena record, see ArenaRecord.targets, the client scores the rest */
	@Override
	public int[] score(ServerLevel level) {
		return new int[0];
	}

	@Override
	public boolean allows(BlockPos pos) {
		return false;
	}

	@Override
	public boolean changesBlocks() {
		return false;
	}

	@Override
	public BlockPos[] corners() {
		return new BlockPos[] {this.min, this.max};
	}

	@Override
	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		switch (this.mode) {
			case POINT -> {
				json.addProperty("kind", "goto");
				json.add("point", Json.pos(this.min));
			}
			case BLOCK -> {
				json.addProperty("kind", "find");
				json.addProperty("block", BuiltInRegistries.BLOCK.getKey(this.block).toString());
				json.add("region", Json.box(this.min, this.max));
			}
			case FOLLOW -> {
				json.addProperty("kind", "follow");
				json.addProperty("target", this.target);
				json.addProperty("near", NEAR);
				json.addProperty("far", FAR);
				json.addProperty("ticks", this.ticks);
			}
		}
		json.addProperty("arrive", ARRIVE);
		return json;
	}
}
