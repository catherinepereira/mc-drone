package com.catherinepereira.mcdrone.task;

import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ground and ceiling height across the arena. Flat arenas sit on the concrete floor,
 * rough ones get rolling value-noise hills up to MAX_RELIEF blocks with grass, stone, and gravel patches.
 * Caves get a lower stone floor relief, walls, and a lumpy roof lit by glowstone,
 * with at least CAVE_HEADROOM air blocks between floor and roof in every column
 */
public final class Terrain {
	public static final int MAX_RELIEF = 4;
	public static final int CAVE_RELIEF = 3;
	public static final int CAVE_HEADROOM = 6;
	// the roof's highest underside sits this far above the base floor, its top one block higher
	public static final int CAVE_ROOF = 10;
	private static final int CEILING_DROP = 3;
	private static final int LIGHT_SPACING = 5;
	private static final int CELL = 5;
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

	public final String kind;
	private final int baseY;
	private final int ox;
	private final int oz;
	private final int radius;
	private final int[][] relief;
	// how far the cave roof's underside hangs below CAVE_ROOF, empty for open terrain
	private final int[][] drop;

	private Terrain(String kind, int baseY, int ox, int oz, int radius, int[][] relief, int[][] drop) {
		this.kind = kind;
		this.baseY = baseY;
		this.ox = ox;
		this.oz = oz;
		this.radius = radius;
		this.relief = relief;
		this.drop = drop;
	}

	public static Terrain create(String kind, Random rng, int baseY, int ox, int oz, int radius) {
		int size = radius * 2 + 1;
		return switch (kind) {
			case "flat" -> new Terrain(kind, baseY, ox, oz, radius, new int[size][size], new int[0][0]);
			case "rough" -> new Terrain(kind, baseY, ox, oz, radius, noise(rng, size, MAX_RELIEF), new int[0][0]);
			case "cave" -> new Terrain(kind, baseY, ox, oz, radius, noise(rng, size, CAVE_RELIEF), noise(rng, size, CEILING_DROP));
			default -> throw new IllegalArgumentException("unknown terrain '" + kind + "', expected flat, rough, or cave");
		};
	}

	// smoothed value noise from 0 to max over a size x size grid
	private static int[][] noise(Random rng, int size, int max) {
		int[][] out = new int[size][size];
		int grid = size / CELL + 2;
		double[][] corners = new double[grid + 1][grid + 1];
		for (int i = 0; i <= grid; i++) {
			for (int j = 0; j <= grid; j++) {
				corners[i][j] = rng.nextDouble() * (max + 0.99);
			}
		}
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				double gx = (double) x / CELL;
				double gz = (double) z / CELL;
				int ix = (int) gx;
				int iz = (int) gz;
				double tx = smooth(gx - ix);
				double tz = smooth(gz - iz);
				double top = corners[ix][iz] + (corners[ix + 1][iz] - corners[ix][iz]) * tx;
				double bottom = corners[ix][iz + 1] + (corners[ix + 1][iz + 1] - corners[ix][iz + 1]) * tx;
				out[x][z] = Math.clamp((int) Math.floor(top + (bottom - top) * tz), 0, max);
			}
		}
		return out;
	}

	public boolean isCave() {
		return this.kind.equals("cave");
	}

	private static double smooth(double t) {
		return t * t * (3 - 2 * t);
	}

	/** y of the top solid block at x, z */
	public int ground(int x, int z) {
		int ix = x - this.ox + this.radius;
		int iz = z - this.oz + this.radius;
		if (ix < 0 || iz < 0 || ix >= this.relief.length || iz >= this.relief.length) {
			return this.baseY;
		}
		return this.baseY + this.relief[ix][iz];
	}

	/** y of the lowest roof block over x, z, Integer.MAX_VALUE in the open */
	public int ceiling(int x, int z) {
		if (!this.isCave()) {
			return Integer.MAX_VALUE;
		}
		int ix = Math.clamp(x - this.ox + this.radius, 0, this.drop.length - 1);
		int iz = Math.clamp(z - this.oz + this.radius, 0, this.drop.length - 1);
		return Math.max(this.baseY + CAVE_ROOF - this.drop[ix][iz], this.ground(x, z) + 1 + CAVE_HEADROOM);
	}

	public void place(ServerLevel level, Random rng) {
		if (this.isCave()) {
			this.placeCave(level, rng);
			return;
		}
		if (!this.kind.equals("rough")) {
			return;
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -this.radius; dx <= this.radius; dx++) {
			for (int dz = -this.radius; dz <= this.radius; dz++) {
				int x = this.ox + dx;
				int z = this.oz + dz;
				int top = this.ground(x, z);
				// patches of bare stone and gravel break up the grass
				double patch = Math.sin(x * 0.7 + rng.nextDouble() * 0.3) * Math.cos(z * 0.6);
				BlockState surface = patch > 0.75 ? Blocks.STONE.defaultBlockState() : patch < -0.8 ? Blocks.GRAVEL.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
				for (int y = this.baseY + 1; y <= top; y++) {
					pos.set(x, y, z);
					BlockState fill = y == top ? surface : y >= top - 1 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
					level.setBlock(pos, fill, FLAGS);
				}
			}
		}
	}

	// stone floor with tuff and gravel patches, walls two blocks thick around the arena, and the roof over both
	private void placeCave(ServerLevel level, Random rng) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockState stone = Blocks.STONE.defaultBlockState();
		int edge = this.radius + 2;
		int roofTop = this.baseY + CAVE_ROOF + 1;
		for (int dx = -edge; dx <= edge; dx++) {
			for (int dz = -edge; dz <= edge; dz++) {
				int x = this.ox + dx;
				int z = this.oz + dz;
				boolean wall = Math.abs(dx) > this.radius || Math.abs(dz) > this.radius;
				int top = this.ground(x, z);
				double patch = Math.sin(x * 0.7 + rng.nextDouble() * 0.3) * Math.cos(z * 0.6);
				BlockState surface = patch > 0.7 ? Blocks.TUFF.defaultBlockState() : patch < -0.8 ? Blocks.GRAVEL.defaultBlockState() : stone;
				for (int y = this.baseY + 1; y <= top; y++) {
					level.setBlock(pos.set(x, y, z), y == top ? surface : stone, FLAGS);
				}
				int underside = wall ? this.baseY + 1 : this.ceiling(x, z);
				for (int y = underside; y <= roofTop; y++) {
					level.setBlock(pos.set(x, y, z), stone, FLAGS);
				}
			}
		}
		// one glowstone per LIGHT_SPACING square of roof, jittered so the grid doesn't show
		BlockState glow = Blocks.GLOWSTONE.defaultBlockState();
		for (int gx = -this.radius; gx <= this.radius; gx += LIGHT_SPACING) {
			for (int gz = -this.radius; gz <= this.radius; gz += LIGHT_SPACING) {
				int x = this.ox + Math.min(gx + rng.nextInt(LIGHT_SPACING), this.radius);
				int z = this.oz + Math.min(gz + rng.nextInt(LIGHT_SPACING), this.radius);
				level.setBlock(pos.set(x, this.ceiling(x, z), z), glow, FLAGS);
			}
		}
	}

	/** A dripstone column hanging from the roof down to bottomY */
	public void hangStalactite(ServerLevel level, int x, int z, int bottomY) {
		BlockState dripstone = Blocks.DRIPSTONE_BLOCK.defaultBlockState();
		for (int y = bottomY; y < this.ceiling(x, z); y++) {
			level.setBlock(new BlockPos(x, y, z), dripstone, FLAGS);
		}
	}

	/** An oak with a small leaf crown, returns its height */
	public int plantTree(ServerLevel level, Random rng, int x, int z) {
		int base = this.ground(x, z) + 1;
		int trunk = 4 + rng.nextInt(2);
		BlockState log = Blocks.OAK_LOG.defaultBlockState();
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		for (int dy = -1; dy <= 1; dy++) {
			int r = dy == 1 ? 1 : 2;
			for (int lx = -r; lx <= r; lx++) {
				for (int lz = -r; lz <= r; lz++) {
					if (Math.abs(lx) == 2 && Math.abs(lz) == 2) {
						continue;
					}
					level.setBlock(new BlockPos(x + lx, base + trunk - 1 + dy, z + lz), leaves, FLAGS);
				}
			}
		}
		for (int y = 0; y < trunk; y++) {
			level.setBlock(new BlockPos(x, base + y, z), log, FLAGS);
		}
		return trunk + 1;
	}
}
