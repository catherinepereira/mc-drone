package com.catherinepereira.mcdrone.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Random structures for the copy and build arenas, and the mining deposits that go with them.
 * A structure is column heights over a footprint, built from a few palette blocks, and every block has a face in the open,
 * so a drone looking from outside and above can read all of it
 */
public final class Structures {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

	private Structures() {
	}

	/** A width x height x length schematic, air above each column's top */
	public static Schematic random(Random rng, int width, int height, int length) {
		int[][] heights = new int[width][length];
		// stacked random rectangles give terraces and towers more often than noise does
		int rects = 2 + width * length / 6;
		for (int i = 0; i < rects; i++) {
			int x0 = rng.nextInt(width);
			int z0 = rng.nextInt(length);
			int x1 = Math.min(width - 1, x0 + rng.nextInt(Math.max(1, width / 2) + 1));
			int z1 = Math.min(length - 1, z0 + rng.nextInt(Math.max(1, length / 2) + 1));
			for (int x = x0; x <= x1; x++) {
				for (int z = z0; z <= z1; z++) {
					heights[x][z] = Math.min(height, heights[x][z] + 1);
				}
			}
		}
		heights[rng.nextInt(width)][rng.nextInt(length)] = Math.max(1, height - rng.nextInt(2));
		uncover(rng, heights);

		List<Block> shuffled = new ArrayList<>(Blueprint.PALETTE);
		Collections.shuffle(shuffled, rng);
		List<Block> used = shuffled.subList(0, 2 + rng.nextInt(3));
		// each column mostly keeps one material, the way builds use them, with a different block now and then
		Block[][] material = new Block[width][length];
		for (int x = 0; x < width; x++) {
			for (int z = 0; z < length; z++) {
				material[x][z] = used.get(rng.nextInt(used.size()));
			}
		}
		BlockState[] blocks = new BlockState[width * height * length];
		for (int y = 0; y < height; y++) {
			for (int z = 0; z < length; z++) {
				for (int x = 0; x < width; x++) {
					Block block = y >= heights[x][z] ? Blocks.AIR : rng.nextInt(4) == 0 ? used.get(rng.nextInt(used.size())) : material[x][z];
					blocks[x + z * width + y * width * length] = block.defaultBlockState();
				}
			}
		}
		return new Schematic(width, height, length, blocks);
	}

	/**
	 * Makes every block visible.
	 * A block on layer y shows a side when a neighbor column (or the footprint edge) is at most y tall, so a column two or
	 * more tall needs an empty neighbor for its bottom block.
	 * Such a column either clears its shortest neighbor or drops to one block, both only take blocks away, so the loop ends
	 */
	private static void uncover(Random rng, int[][] heights) {
		int w = heights.length;
		int l = heights[0].length;
		boolean changed = true;
		while (changed) {
			changed = false;
			for (int x = 0; x < w; x++) {
				for (int z = 0; z < l; z++) {
					if (heights[x][z] < 2) {
						continue;
					}
					int[] lowest = null;
					for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
						int nx = x + d[0];
						int nz = z + d[1];
						if (nx < 0 || nz < 0 || nx >= w || nz >= l || heights[nx][nz] == 0) {
							lowest = null;
							break;
						}
						if (lowest == null || heights[nx][nz] < heights[lowest[0]][lowest[1]]) {
							lowest = new int[] {nx, nz};
						}
					}
					if (lowest != null) {
						if (rng.nextBoolean()) {
							heights[lowest[0]][lowest[1]] = 0;
						} else {
							heights[x][z] = 1;
						}
						changed = true;
					}
				}
			}
		}
	}

	public static void paste(ServerLevel level, Schematic s, BlockPos min) {
		for (int y = 0; y < s.height; y++) {
			for (int z = 0; z < s.length; z++) {
				for (int x = 0; x < s.width; x++) {
					level.setBlock(min.offset(x, y, z), s.get(x, y, z), FLAGS);
				}
			}
		}
	}

	/** How many of each block the schematic has, air left out */
	public static Map<Block, Integer> counts(Schematic s) {
		Map<Block, Integer> out = new LinkedHashMap<>();
		for (int y = 0; y < s.height; y++) {
			for (int z = 0; z < s.length; z++) {
				for (int x = 0; x < s.width; x++) {
					BlockState state = s.get(x, y, z);
					if (!state.isAir()) {
						out.merge(state.getBlock(), 1, Integer::sum);
					}
				}
			}
		}
		return out;
	}

	/** Enough of each block plus two spares, in stacks of up to 64 in shuffled slots */
	public static List<ItemStack> stock(Random rng, Schematic s, int slots) {
		List<ItemStack> stacks = new ArrayList<>();
		counts(s).forEach((block, n) -> {
			for (int left = n + 2; left > 0; left -= 64) {
				stacks.add(new ItemStack(block.asItem(), Math.min(64, left)));
			}
		});
		return scatter(rng, stacks, slots);
	}

	/** The stacks in random slots of a container with this many, any past the last slot are dropped */
	public static List<ItemStack> scatter(Random rng, List<ItemStack> stacks, int slots) {
		List<ItemStack> layout = new ArrayList<>(Collections.nCopies(slots, ItemStack.EMPTY));
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < slots; i++) {
			order.add(i);
		}
		Collections.shuffle(order, rng);
		for (int i = 0; i < stacks.size() && i < slots; i++) {
			layout.set(order.get(i), stacks.get(i));
		}
		return layout;
	}

	/**
	 * A solid box of stone with the given blocks set into it at random cells, about a third of them on the surface.
	 * Returns the cells that hold them
	 */
	public static List<BlockPos> deposit(ServerLevel level, Random rng, BlockPos min, BlockPos max, List<Block> embedded) {
		List<BlockPos> surface = new ArrayList<>();
		List<BlockPos> inside = new ArrayList<>();
		for (BlockPos p : BlockPos.betweenClosed(min, max)) {
			level.setBlock(p, Blocks.STONE.defaultBlockState(), FLAGS);
			boolean outer = p.getX() == min.getX() || p.getX() == max.getX() || p.getZ() == min.getZ() || p.getZ() == max.getZ() || p.getY() == max.getY();
			(outer ? surface : inside).add(p.immutable());
		}
		Collections.shuffle(surface, rng);
		Collections.shuffle(inside, rng);
		List<BlockPos> cells = new ArrayList<>();
		for (int i = 0; i < embedded.size(); i++) {
			boolean onSurface = (i % 3 == 0 || inside.isEmpty()) && !surface.isEmpty();
			BlockPos p = onSurface ? surface.removeLast() : inside.isEmpty() ? null : inside.removeLast();
			if (p == null) {
				break;
			}
			level.setBlock(p, embedded.get(i).defaultBlockState(), FLAGS);
			cells.add(p);
		}
		return cells;
	}
}
