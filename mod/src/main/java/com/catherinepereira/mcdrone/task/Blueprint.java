package com.catherinepereira.mcdrone.task;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A small structure for replicate_build: column heights over a 3x3 footprint, up to SIZE tall, one palette block per cell.
 * Every block is supported from below, and the center column always has an open side so each of its blocks can be seen
 */
public final class Blueprint {
	public static final int SIZE = 3;
	public static final List<Block> PALETTE = List.of(
		Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.COBBLESTONE, Blocks.BRICKS,
		Blocks.SANDSTONE, Blocks.WOOL.pick(DyeColor.WHITE), Blocks.WOOL.pick(DyeColor.RED), Blocks.WOOL.pick(DyeColor.BLUE), Blocks.TERRACOTTA
	);
	private static final int MIN_BLOCKS = 3;
	private static final int MAX_BLOCKS = 10;
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

	/** One block, dx and dz from -1 to 1 around the base center, dy from 1 up */
	public record Cell(int dx, int dy, int dz, Block block) {
	}

	public final List<Cell> cells;
	public final List<Block> decoys;

	private Blueprint(List<Cell> cells, List<Block> decoys) {
		this.cells = cells;
		this.decoys = decoys;
	}

	public static Blueprint random(Random rng) {
		int[][] heights;
		do {
			heights = new int[SIZE][SIZE];
			int wanted = MIN_BLOCKS + rng.nextInt(MAX_BLOCKS - MIN_BLOCKS + 1);
			for (int placed = 0; placed < wanted; ) {
				int x = rng.nextInt(SIZE);
				int z = rng.nextInt(SIZE);
				if (heights[x][z] < SIZE) {
					heights[x][z]++;
					placed++;
				}
			}
			// a center column walled in on all four sides would hide its lower blocks, open one side
			if (heights[1][1] > 0 && heights[0][1] > 0 && heights[2][1] > 0 && heights[1][0] > 0 && heights[1][2] > 0) {
				int[][] sides = {{0, 1}, {2, 1}, {1, 0}, {1, 2}};
				int[] side = sides[rng.nextInt(sides.length)];
				heights[side[0]][side[1]] = 0;
			}
		} while (count(heights) < MIN_BLOCKS);

		List<Block> shuffled = new ArrayList<>(PALETTE);
		Collections.shuffle(shuffled, rng);
		int kinds = 2 + rng.nextInt(3);
		List<Block> used = shuffled.subList(0, kinds);
		List<Cell> cells = new ArrayList<>();
		for (int x = 0; x < SIZE; x++) {
			for (int z = 0; z < SIZE; z++) {
				for (int y = 1; y <= heights[x][z]; y++) {
					cells.add(new Cell(x - 1, y, z - 1, used.get(rng.nextInt(kinds))));
				}
			}
		}
		return new Blueprint(List.copyOf(cells), List.copyOf(shuffled.subList(kinds, kinds + 2)));
	}

	private static int count(int[][] heights) {
		int n = 0;
		for (int[] row : heights) {
			for (int h : row) {
				n += h;
			}
		}
		return n;
	}

	public void build(ServerLevel level, BlockPos base) {
		for (Cell c : this.cells) {
			level.setBlock(base.offset(c.dx, c.dy, c.dz), c.block.defaultBlockState(), FLAGS);
		}
	}

	/** Exactly enough of each block plus two spares, and a few stacks of two unused palette blocks, in shuffled slots */
	public List<ItemStack> stock(Random rng, int slots) {
		List<ItemStack> stacks = new ArrayList<>();
		for (Block block : this.cells.stream().map(Cell::block).distinct().toList()) {
			long n = this.cells.stream().filter(c -> c.block == block).count();
			stacks.add(new ItemStack(block.asItem(), (int) n + 2));
		}
		for (Block decoy : this.decoys) {
			stacks.add(new ItemStack(decoy.asItem(), 4 + rng.nextInt(5)));
		}
		return Structures.scatter(rng, stacks, slots);
	}

	/** Cells of the build site that match, how many the blueprint has, and blocks there that don't belong */
	public int[] score(ServerLevel level, BlockPos site) {
		int correct = 0;
		int wrong = 0;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = 1; dy <= SIZE; dy++) {
					BlockState actual = level.getBlockState(site.offset(dx, dy, dz));
					Block expected = this.at(dx, dy, dz);
					if (expected != null && actual.is(expected)) {
						correct++;
					} else if (!actual.isAir()) {
						wrong++;
					}
				}
			}
		}
		return new int[] {correct, this.cells.size(), wrong};
	}

	private @Nullable Block at(int dx, int dy, int dz) {
		for (Cell c : this.cells) {
			if (c.dx == dx && c.dy == dy && c.dz == dz) {
				return c.block;
			}
		}
		return null;
	}

	public JsonArray toJson() {
		JsonArray out = new JsonArray();
		for (Cell c : this.cells) {
			JsonObject cell = new JsonObject();
			JsonArray offset = new JsonArray();
			offset.add(c.dx);
			offset.add(c.dy);
			offset.add(c.dz);
			cell.add("offset", offset);
			cell.addProperty("block", BuiltInRegistries.BLOCK.getKey(c.block).toString());
			out.add(cell);
		}
		return out;
	}
}
