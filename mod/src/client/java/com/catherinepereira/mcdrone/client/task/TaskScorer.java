package com.catherinepereira.mcdrone.client.task;

import com.catherinepereira.mcdrone.task.TaskKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Client-side scoring for every task.
 * Movement shaping runs per simulated tick from the drone pose, which only the client knows exactly.
 * World progress (mined blocks, chest contents, placements) comes from the server's metrics on each tool sync
 */
public final class TaskScorer {
	public static final double SUCCESS_BONUS = 10.0;
	// tool tasks reward approach only down to roughly reach distance, the rest is the tool's job
	private static final double TOOL_STANDOFF = 3.0;
	// matches Arena.HEIGHT, the cleared space above the floor
	private static final int ARENA_HEIGHT = 16;

	private boolean active;
	private String episodeId;
	private TaskKind kind = TaskKind.NAVIGATE_TO;
	private long seed;
	private BlockPos marker = BlockPos.ZERO;
	private JsonObject arena = new JsonObject();
	private int maxSteps;
	private double successDist;
	private double collisionPenalty;
	private int step;
	private int collisions;
	private @Nullable Vec3 goal;
	private double prevDist;
	private double totalReward;
	private double stepReward;
	private boolean success;
	private boolean truncated;
	private int[] metrics = new int[0];
	private double[] bounds = new double[6];
	private double outOfBoundsPenalty;
	private boolean outOfBounds;

	public void begin(
		String episodeId, TaskKind kind, long seed, BlockPos marker, JsonObject arena, int maxSteps, double successDist, double collisionPenalty,
		int boundsPadding, double outOfBoundsPenalty
	) {
		JsonArray origin = arena.getAsJsonArray("origin");
		int radius = arena.get("radius").getAsInt() + boundsPadding;
		int ox = origin.get(0).getAsInt();
		int floor = origin.get(1).getAsInt();
		int oz = origin.get(2).getAsInt();
		this.bounds = new double[] {ox - radius, floor, oz - radius, ox + radius + 1, floor + ARENA_HEIGHT + 1, oz + radius + 1};
		if (arena.has("fence")) {
			// jobs bring their own geofence around the source and destination boxes
			JsonArray fence = arena.getAsJsonArray("fence");
			for (int i = 0; i < 6; i++) {
				this.bounds[i] = fence.get(i).getAsDouble() + (i < 3 ? -boundsPadding : boundsPadding);
			}
		}
		this.outOfBoundsPenalty = outOfBoundsPenalty;
		this.outOfBounds = false;
		this.active = true;
		this.episodeId = episodeId;
		this.kind = kind;
		this.seed = seed;
		this.marker = marker;
		this.arena = arena;
		this.maxSteps = maxSteps;
		this.successDist = successDist;
		this.collisionPenalty = collisionPenalty;
		this.step = 0;
		this.collisions = 0;
		this.goal = null;
		this.totalReward = 0;
		this.stepReward = 0;
		this.success = false;
		this.truncated = false;
		this.metrics = new int[0];
	}

	public void end() {
		this.active = false;
	}

	public boolean active() {
		return this.active;
	}

	public boolean done() {
		return this.success || this.truncated || this.outOfBounds;
	}

	public double[] bounds() {
		return this.bounds;
	}

	public String episodeId() {
		return this.episodeId;
	}

	public TaskKind kind() {
		return this.kind;
	}

	public BlockPos marker() {
		return this.marker;
	}

	public JsonObject arena() {
		return this.arena;
	}

	/** Called once per applied action, reward then accumulates across the ticks that action covers */
	public void beginAction() {
		this.stepReward = 0;
	}

	/**
	 * One simulated tick. isTarget tells whether a target block still stands in the client's world copy,
	 * so shaping heads for the nearest unmined one
	 */
	public void update(Vec3 droneCenter, boolean collided, Predicate<BlockPos> isTarget) {
		if (!this.active || this.done()) {
			return;
		}
		this.step++;
		double reward = 0;
		if (collided) {
			this.collisions++;
			reward -= this.collisionPenalty;
		}
		if (!this.inBounds(droneCenter)) {
			this.outOfBounds = true;
			reward -= this.outOfBoundsPenalty;
			this.stepReward += reward;
			this.totalReward += reward;
			return;
		}
		Vec3 target = this.currentGoal(droneCenter, isTarget);
		if (target != null) {
			double standoff = this.kind == TaskKind.NAVIGATE_TO ? 0 : TOOL_STANDOFF;
			double dist = Math.max(0, droneCenter.distanceTo(target) - standoff);
			if (this.goal != null && this.goal.equals(target)) {
				reward += this.prevDist - dist;
			}
			this.goal = target;
			this.prevDist = dist;
		}
		if (this.kind == TaskKind.NAVIGATE_TO && target != null && droneCenter.distanceTo(target) <= this.successDist) {
			this.succeed();
			reward += SUCCESS_BONUS;
		}
		this.stepReward += reward;
		this.totalReward += reward;
		if (!this.success && this.step >= this.maxSteps) {
			this.truncated = true;
		}
	}

	/** Reward from world progress the server reports, see ArenaRecord.metrics for the layout */
	public void applyMetrics(int[] next) {
		if (!this.active || this.done() || this.kind == TaskKind.NAVIGATE_TO) {
			this.metrics = next;
			return;
		}
		int[] prev = this.metrics.length == next.length ? this.metrics : next;
		double reward = 0;
		boolean won = false;
		switch (this.kind) {
			case DIG_BLOCK -> {
				reward += 10.0 * (prev[0] - next[0]);
				won = next[0] == 0;
			}
			case PLACE_BLOCK -> {
				reward -= 1.0 * (next[1] - prev[1]);
				won = next[0] == 1;
			}
			case CHEST_TRANSFER -> {
				double total = Math.max(1, next[1]);
				reward += 10.0 * (next[0] - prev[0]) / total;
				reward += 2.0 * Math.max(0, next[2] - prev[2]) / total;
				won = next[0] >= next[1];
			}
			case MINE_AND_DELIVER -> {
				double required = Math.max(1, next[2]);
				reward += 3.0 * (prev[0] - next[0]);
				reward += 5.0 * (next[1] - prev[1]) / required;
				won = next[1] >= next[2];
			}
			case HARVEST_CROPS, HARVEST_REGION -> {
				// a harvested cell empties until it is replanted, so harvesting and replanting together earn the most
				reward += 5.0 * (prev[0] - next[0]) / Math.max(1, next[2]);
				reward += 3.0 * (prev[3] - next[3]) / Math.max(1, next[4]);
				won = next[0] == 0 && next[3] == 0;
			}
			case MINE_REGION, MINE_DEPOSIT -> {
				double initial = Math.max(1, next[1]);
				reward += 10.0 * (prev[0] - next[0]) / initial;
				won = next[0] == 0;
			}
			case REPLICATE_BUILD, COPY_REGION, BUILD_SCHEMATIC, COPY_BUILD, SCHEMATIC_BUILD, GATHER_BUILD -> {
				double total = Math.max(1, next[1]);
				reward += 10.0 * (next[0] - prev[0]) / total;
				reward -= 1.0 * (next[2] - prev[2]);
				won = next[0] >= next[1] && next[2] == 0;
			}
			default -> {
			}
		}
		this.metrics = next;
		if (won) {
			this.succeed();
			reward += SUCCESS_BONUS;
		}
		this.stepReward += reward;
		this.totalReward += reward;
	}

	private boolean inBounds(Vec3 p) {
		double[] b = this.bounds;
		return p.x >= b[0] && p.y >= b[1] && p.z >= b[2] && p.x <= b[3] && p.y <= b[4] && p.z <= b[5];
	}

	private void succeed() {
		this.success = true;
		this.truncated = false;
	}

	private @Nullable Vec3 currentGoal(Vec3 drone, Predicate<BlockPos> isTarget) {
		return switch (this.kind) {
			case NAVIGATE_TO -> Vec3.atCenterOf(this.marker);
			case DIG_BLOCK -> this.nearestTarget(drone, isTarget);
			case PLACE_BLOCK -> point("goal");
			case CHEST_TRANSFER -> {
				boolean carrying = this.metrics.length >= 3 && this.metrics[2] > 0;
				yield carrying ? point("targetChest") : point("sourceChest");
			}
			case MINE_AND_DELIVER -> {
				boolean loaded = this.metrics.length >= 4 && (this.metrics[0] == 0 || this.metrics[3] + this.metrics[1] >= this.metrics[2]);
				Vec3 next = loaded ? null : this.nearestTarget(drone, isTarget);
				yield next != null ? next : point("targetChest");
			}
			// no distance shaping, the drone has to study the reference before heading to the build site
			case REPLICATE_BUILD, COPY_REGION, BUILD_SCHEMATIC, MINE_REGION, HARVEST_CROPS, HARVEST_REGION, COPY_BUILD, SCHEMATIC_BUILD, MINE_DEPOSIT, GATHER_BUILD -> null;
		};
	}

	private @Nullable Vec3 nearestTarget(Vec3 drone, Predicate<BlockPos> isTarget) {
		Vec3 best = null;
		if (this.arena.has("targets")) {
			for (JsonElement el : this.arena.getAsJsonArray("targets")) {
				BlockPos pos = blockPos(el.getAsJsonArray());
				Vec3 center = Vec3.atCenterOf(pos);
				if (isTarget.test(pos) && (best == null || drone.distanceTo(center) < drone.distanceTo(best))) {
					best = center;
				}
			}
		}
		return best;
	}

	private @Nullable Vec3 point(String key) {
		return this.arena.has(key) ? Vec3.atCenterOf(blockPos(this.arena.getAsJsonArray(key))) : null;
	}

	private static BlockPos blockPos(JsonArray a) {
		return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
	}

	public JsonObject snapshot() {
		JsonObject json = new JsonObject();
		json.addProperty("id", this.episodeId);
		json.addProperty("task", this.kind.id);
		json.addProperty("seed", this.seed);
		json.addProperty("step", this.step);
		json.addProperty("reward", this.stepReward);
		json.addProperty("totalReward", this.totalReward);
		json.addProperty("distance", this.goal == null ? -1 : this.prevDist);
		json.addProperty("done", this.done());
		json.addProperty("success", this.success);
		json.addProperty("truncated", this.truncated);
		json.addProperty("collisions", this.collisions);
		json.addProperty("outOfBounds", this.outOfBounds);
		JsonArray m = new JsonArray();
		for (int v : this.metrics) {
			m.add(v);
		}
		json.add("metrics", m);
		return json;
	}
}
