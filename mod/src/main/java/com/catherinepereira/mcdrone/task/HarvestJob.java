package com.catherinepereira.mcdrone.task;

import com.catherinepereira.mcdrone.Json;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Harvest every crop of one kind that is ripe in a region, and leave every farmland cell in it planted.
 * Seeds come from the harvest, so the drone harvests, then replants with what it picked up.
 * The drone is told the region and the crop, it judges ripeness from the camera. Unripe crops can't be broken
 */
public final class HarvestJob implements DroneJob {
	public static final int MAX_SIDE = 32;

	public final BlockPos min;
	public final BlockPos max;
	public final CropBlock crop;
	private final List<BlockPos> ripeAtStart;
	private final List<BlockPos> plots;

	private HarvestJob(BlockPos min, BlockPos max, CropBlock crop, List<BlockPos> ripeAtStart, List<BlockPos> plots) {
		this.min = min;
		this.max = max;
		this.crop = crop;
		this.ripeAtStart = ripeAtStart;
		this.plots = plots;
	}

	public static HarvestJob start(ServerLevel level, BlockPos a, BlockPos b, String cropName) {
		Identifier id = Identifier.tryParse(cropName);
		Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
		if (!(block instanceof CropBlock crop)) {
			throw new IllegalArgumentException("'" + cropName + "' is not a crop, try minecraft:wheat, carrots, potatoes, or beetroots");
		}
		BlockPos min = BlockPos.min(a, b);
		BlockPos max = BlockPos.max(a, b);
		DroneJob.checkSize(max.subtract(min).offset(1, 1, 1), MAX_SIDE, "farm region");
		List<BlockPos> ripe = new ArrayList<>();
		List<BlockPos> plots = new ArrayList<>();
		// a box drawn on the crops or on the farmland both count, so look one block past the top
		for (BlockPos p : BlockPos.betweenClosed(min, max.above())) {
			BlockState state = level.getBlockState(p);
			if (state.is(crop) && crop.isMaxAge(state)) {
				ripe.add(p.immutable());
			}
			if (state.is(Blocks.FARMLAND) && DroneJob.inside(p.above(), min, max.above())) {
				plots.add(p.above().immutable());
			}
		}
		if (plots.isEmpty()) {
			throw new IllegalArgumentException("there is no farmland in that region");
		}
		return new HarvestJob(min, max.above(), crop, List.copyOf(ripe), List.copyOf(plots));
	}

	public boolean unripe(BlockState state) {
		return state.getBlock() instanceof CropBlock c && !c.isMaxAge(state);
	}

	/** Crops ripe at the start still standing, ripe ones harvested, ripe at the start, empty farmland cells, farmland cells */
	@Override
	public int[] score(ServerLevel level) {
		int ripeLeft = 0;
		for (BlockPos p : this.ripeAtStart) {
			BlockState state = level.getBlockState(p);
			ripeLeft += state.is(this.crop) && this.crop.isMaxAge(state) ? 1 : 0;
		}
		int empty = 0;
		for (BlockPos p : this.plots) {
			empty += level.getBlockState(p).isAir() ? 1 : 0;
		}
		return new int[] {ripeLeft, this.ripeAtStart.size() - ripeLeft, this.ripeAtStart.size(), empty, this.plots.size()};
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
		json.addProperty("kind", "harvest");
		json.add("region", Json.box(this.min, this.max));
		json.addProperty("crop", BuiltInRegistries.BLOCK.getKey(this.crop).toString());
		json.addProperty("seed", BuiltInRegistries.ITEM.getKey(this.crop.asItem()).toString());
		return json;
	}
}
