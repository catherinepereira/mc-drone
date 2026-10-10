package com.catherinepereira.mcdrone.task;

import com.catherinepereira.mcdrone.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Fly over every patrol cell of a region, rounds times, and with hunt set kill its prey in it, hostile mobs unless told otherwise.
 * The drone is told the region, its cells, and the prey. It finds mobs with its camera.
 * The client scores the rounds from the drone's pose, the server counts the mobs
 */
public final class PatrolJob implements DroneJob {
	public static final int MAX_SIDE = 64;
	public static final int MAX_ROUNDS = 20;
	// patrol cells are about this many blocks across, the drone has to pass within VISIT_RADIUS of each one's center
	public static final int CELL = 8;
	public static final double VISIT_RADIUS = 3.0;

	public final BlockPos min;
	public final BlockPos max;
	public final int rounds;
	public final boolean hunt;
	public final Prey prey;
	// a training arena's prey, the mobs it counts. A player's job counts every mob of its prey in the region
	private final @Nullable List<UUID> quarry;
	private final int preyAtStart;
	public int kills;

	private PatrolJob(BlockPos min, BlockPos max, int rounds, boolean hunt, Prey prey, @Nullable List<UUID> quarry, int preyAtStart) {
		this.min = min;
		this.max = max;
		this.rounds = rounds;
		this.hunt = hunt;
		this.prey = prey;
		this.quarry = quarry;
		this.preyAtStart = preyAtStart;
	}

	/** A player's patrol, or with hunt their guard job. rounds is the subject, blank for one */
	public static PatrolJob start(ServerLevel level, BlockPos a, BlockPos b, String rounds, boolean hunt, Prey prey) {
		BlockPos min = BlockPos.min(a, b);
		BlockPos max = BlockPos.max(a, b);
		DroneJob.checkSize(max.subtract(min).offset(1, 1, 1), MAX_SIDE, "patrol region");
		int n;
		try {
			n = rounds.isBlank() ? 1 : Integer.parseInt(rounds.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("rounds is a whole number, such as 3");
		}
		if (n < 1 || n > MAX_ROUNDS) {
			throw new IllegalArgumentException("a patrol runs 1 to " + MAX_ROUNDS + " rounds");
		}
		return new PatrolJob(min, max, n, hunt, prey, null, hunt ? preyIn(level, min, max, prey) : 0);
	}

	/** A training arena's patrol over its floor, and with quarry a hunt for those mobs, its prey, with no rounds to fly */
	static PatrolJob arena(BlockPos min, BlockPos max, Prey prey, @Nullable List<UUID> quarry) {
		return quarry == null ? new PatrolJob(min, max, 1, false, prey, null, 0) : new PatrolJob(min, max, 0, true, prey, List.copyOf(quarry), quarry.size());
	}

	/** The patrol cells' centers as x, z, evenly spread over the region */
	public static List<double[]> cells(int x0, int z0, int x1, int z1) {
		int nx = Math.max(1, (x1 - x0 + 1 + CELL - 1) / CELL);
		int nz = Math.max(1, (z1 - z0 + 1 + CELL - 1) / CELL);
		double sx = (x1 - x0 + 1) / (double) nx;
		double sz = (z1 - z0 + 1) / (double) nz;
		List<double[]> out = new ArrayList<>();
		for (int i = 0; i < nx; i++) {
			for (int j = 0; j < nz; j++) {
				out.add(new double[] {x0 + (i + 0.5) * sx, z0 + (j + 0.5) * sz});
			}
		}
		return out;
	}

	private int preyLeft(ServerLevel level) {
		if (this.quarry != null) {
			int left = 0;
			for (UUID id : this.quarry) {
				Entity mob = level.getEntity(id);
				left += mob != null && mob.isAlive() ? 1 : 0;
			}
			return left;
		}
		return preyIn(level, this.min, this.max, this.prey);
	}

	private static int preyIn(ServerLevel level, BlockPos min, BlockPos max, Prey prey) {
		return level.getEntitiesOfClass(Mob.class, box(min, max), prey::matches).size();
	}

	private static AABB box(BlockPos min, BlockPos max) {
		return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
	}

	/** Prey left, mobs the drone killed, prey at the start, all 0 without hunt */
	@Override
	public int[] score(ServerLevel level) {
		return this.hunt ? new int[] {this.preyLeft(level), this.kills, this.preyAtStart} : new int[] {0, 0, 0};
	}

	/** A hunt's prey inside its region, a plain patrol attacks nothing */
	@Override
	public boolean mayAttack(Entity entity) {
		return this.hunt && this.prey.matches(entity) && box(this.min, this.max).contains(entity.position());
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
		json.addProperty("kind", "patrol");
		json.add("region", Json.box(this.min, this.max));
		json.addProperty("rounds", this.rounds);
		json.addProperty("hunt", this.hunt);
		if (this.hunt) {
			json.add("prey", this.prey.toJson());
		}
		JsonArray cells = new JsonArray();
		for (double[] c : cells(this.min.getX(), this.min.getZ(), this.max.getX(), this.max.getZ())) {
			JsonArray cell = new JsonArray();
			cell.add(c[0]);
			cell.add(c[1]);
			cells.add(cell);
		}
		json.add("cells", cells);
		json.addProperty("visitRadius", VISIT_RADIUS);
		return json;
	}
}
