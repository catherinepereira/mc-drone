package com.catherinepereira.mcdrone.client.task;

import com.catherinepereira.mcdrone.Json;
import com.catherinepereira.mcdrone.task.TaskKind;
import com.catherinepereira.mcdrone.task.TrainingArenas;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
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
	// the collisions that were with another drone
	private int droneCollisions;
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
	// health lost to mobs, at damagePenalty per point, and whether the drone was wrecked, which ends the episode
	private double damagePenalty;
	private float lastHealth = -1;
	private float damage;
	private boolean wrecked;
	// a patrol's cells as x, z, the ones visited this round, and the rounds flown, see PatrolJob
	private List<double[]> patrolCells = List.of();
	private boolean[] visited = new boolean[0];
	private double visitRadius;
	private int rounds;
	private int roundsDone;
	// a goto's point, a follow's target entity and band, and ticks spent in the band
	private @Nullable Vec3 point;
	private double arrive;
	private int followTarget = -1;
	private double near;
	private double far;
	private int followTicks;
	private int inBand;
	private double prevBandGap = -1;

	public void begin(
		String episodeId, TaskKind kind, long seed, BlockPos marker, JsonObject arena, int maxSteps, double successDist, double collisionPenalty,
		int boundsPadding, double outOfBoundsPenalty, double damagePenalty
	) {
		JsonArray origin = arena.getAsJsonArray("origin");
		int radius = arena.get("radius").getAsInt() + boundsPadding;
		int ox = origin.get(0).getAsInt();
		int floor = origin.get(1).getAsInt();
		int oz = origin.get(2).getAsInt();
		this.bounds = new double[] {ox - radius, floor, oz - radius, ox + radius + 1, floor + TrainingArenas.HEIGHT + 1, oz + radius + 1};
		if (arena.has("fence")) {
			// jobs bring their own geofence around the source and destination boxes
			JsonArray fence = arena.getAsJsonArray("fence");
			for (int i = 0; i < 6; i++) {
				this.bounds[i] = fence.get(i).getAsDouble() + (i < 3 ? -boundsPadding : boundsPadding);
			}
		}
		this.outOfBoundsPenalty = outOfBoundsPenalty;
		this.outOfBounds = false;
		this.damagePenalty = damagePenalty;
		this.lastHealth = -1;
		this.damage = 0;
		this.wrecked = false;
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
		this.droneCollisions = 0;
		this.goal = null;
		this.totalReward = 0;
		this.stepReward = 0;
		this.success = false;
		this.truncated = false;
		this.metrics = new int[0];
		this.patrolCells = new ArrayList<>();
		this.rounds = 0;
		this.roundsDone = 0;
		JsonObject job = arena.has("job") ? arena.getAsJsonObject("job") : null;
		if (job != null && job.has("cells")) {
			for (JsonElement cell : job.getAsJsonArray("cells")) {
				this.patrolCells.add(new double[] {cell.getAsJsonArray().get(0).getAsDouble(), cell.getAsJsonArray().get(1).getAsDouble()});
			}
			this.rounds = job.get("rounds").getAsInt();
			this.visitRadius = job.get("visitRadius").getAsDouble();
		}
		this.visited = new boolean[this.patrolCells.size()];
		this.point = job != null && job.has("point") ? Vec3.atCenterOf(Json.readPos(job.getAsJsonArray("point"))) : null;
		this.arrive = job != null && job.has("arrive") ? job.get("arrive").getAsDouble() : 0;
		this.followTarget = job != null && job.has("target") ? job.get("target").getAsInt() : -1;
		this.near = job != null && job.has("near") ? job.get("near").getAsDouble() : 0;
		this.far = job != null && job.has("far") ? job.get("far").getAsDouble() : 0;
		this.followTicks = job != null && job.has("ticks") ? job.get("ticks").getAsInt() : 0;
		this.inBand = 0;
		this.prevBandGap = -1;
	}

	/** The entity a follow keeps up with, -1 for other tasks */
	public int followTarget() {
		return this.followTarget;
	}

	/**
	 * One tick of a follow: closing the gap to the band between near and far earns its distance, each tick in the band
	 * earns a share of 10, and a training arena's follow succeeds after followTicks of them. A player's never ends on its own
	 */
	public void follow(Vec3 drone, @Nullable Vec3 target) {
		if (!this.active || this.done() || target == null) {
			return;
		}
		double d = drone.distanceTo(target);
		double gap = Math.max(0, d - this.far) + Math.max(0, this.near - d);
		double reward = this.prevBandGap >= 0 ? this.prevBandGap - gap : 0;
		this.prevBandGap = gap;
		if (gap == 0) {
			this.inBand++;
			reward += this.followTicks > 0 ? 10.0 / this.followTicks : 0;
		}
		this.goal = target;
		this.prevDist = gap;
		if (this.followTicks > 0 && this.inBand >= this.followTicks) {
			this.succeed();
			reward += SUCCESS_BONUS;
		}
		this.stepReward += reward;
		this.totalReward += reward;
	}

	public void end() {
		this.active = false;
	}

	public boolean active() {
		return this.active;
	}

	public boolean done() {
		return this.success || this.truncated || this.outOfBounds || this.wrecked;
	}

	/** The drone's health once a tick: lost health costs damagePenalty a point, and a wreck ends the episode like leaving the geofence */
	public void health(float health, boolean wrecked) {
		if (this.active && !this.done() && this.lastHealth > health) {
			double reward = -this.damagePenalty * (this.lastHealth - health);
			this.damage += this.lastHealth - health;
			if (wrecked) {
				this.wrecked = true;
				reward -= this.outOfBoundsPenalty;
			}
			this.stepReward += reward;
			this.totalReward += reward;
		}
		this.lastHealth = health;
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
	public void update(Vec3 droneCenter, boolean collided, boolean hitDrone, Predicate<BlockPos> isTarget) {
		if (!this.active || this.done()) {
			return;
		}
		this.step++;
		double reward = 0;
		if (collided) {
			this.collisions++;
			reward -= this.collisionPenalty;
			if (hitDrone) {
				this.droneCollisions++;
			}
		}
		if (!this.inBounds(droneCenter)) {
			this.outOfBounds = true;
			reward -= this.outOfBoundsPenalty;
			this.stepReward += reward;
			this.totalReward += reward;
			return;
		}
		Vec3 target = this.currentGoal(droneCenter, isTarget);
		boolean reaching = this.kind == TaskKind.NAVIGATE_TO || this.point != null || this.kind == TaskKind.FIND_BLOCK || this.kind == TaskKind.SEEK_BLOCK;
		if (target != null) {
			double standoff = reaching ? 0 : TOOL_STANDOFF;
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
		if (reaching && this.kind != TaskKind.NAVIGATE_TO && target != null && droneCenter.distanceTo(target) <= this.arrive) {
			this.succeed();
			reward += SUCCESS_BONUS;
		}
		reward += this.patrol(droneCenter);
		if (!this.success && this.patrolled()) {
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
			case HUNT_MOBS, GUARD_REGION -> {
				reward += 10.0 * (next[1] - prev[1]) / Math.max(1, next[2]);
				// a guard also flies its rounds, see patrolled
				won = this.kind == TaskKind.HUNT_MOBS && next[0] == 0;
			}
			default -> {
			}
		}
		this.metrics = next;
		won |= this.patrolled();
		if (won) {
			this.succeed();
			reward += SUCCESS_BONUS;
		}
		this.stepReward += reward;
		this.totalReward += reward;
	}

	// marks the patrol cells the drone is over, and starts the next round once every one is, returns the reward
	private double patrol(Vec3 p) {
		if (this.patrolCells.isEmpty() || this.roundsDone >= this.rounds) {
			return 0;
		}
		double reward = 0;
		boolean all = true;
		for (int i = 0; i < this.visited.length; i++) {
			double[] c = this.patrolCells.get(i);
			if (!this.visited[i] && Math.hypot(p.x - c[0], p.z - c[1]) <= this.visitRadius) {
				this.visited[i] = true;
				reward += 10.0 / (this.visited.length * this.rounds);
			}
			all &= this.visited[i];
		}
		if (all) {
			this.roundsDone++;
			java.util.Arrays.fill(this.visited, false);
		}
		return reward;
	}

	// a patrol flew every round, and a guard's region has no hostile mob left
	private boolean patrolled() {
		boolean flown = this.rounds > 0 && this.roundsDone >= this.rounds;
		return switch (this.kind) {
			case PATROL_AREA, PATROL_REGION -> flown;
			case GUARD_REGION -> flown && this.metrics.length >= 1 && this.metrics[0] == 0;
			default -> false;
		};
	}

	private int visitedCount() {
		int n = 0;
		for (boolean v : this.visited) {
			n += v ? 1 : 0;
		}
		return n;
	}

	private boolean inBounds(Vec3 p) {
		double[] b = this.bounds;
		return p.x >= b[0] && p.y >= b[1] && p.z >= b[2] && p.x <= b[3] && p.y <= b[4] && p.z <= b[5];
	}

	/** Ends the episode as a success for a goal the client checks itself, such as docking */
	public void complete() {
		if (this.active && !this.done()) {
			this.succeed();
			this.stepReward += SUCCESS_BONUS;
			this.totalReward += SUCCESS_BONUS;
		}
	}

	private void succeed() {
		this.success = true;
		this.truncated = false;
	}

	private @Nullable Vec3 currentGoal(Vec3 drone, Predicate<BlockPos> isTarget) {
		return switch (this.kind) {
			case NAVIGATE_TO -> Vec3.atCenterOf(this.marker);
			// the marker is the station, the drone docks on its top
			case RETURN_HOME -> Vec3.atCenterOf(this.marker.above());
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
			// no distance shaping, the drone has to study the reference before heading to the build site, or find what it hunts
			case REPLICATE_BUILD, COPY_REGION, BUILD_SCHEMATIC, MINE_REGION, HARVEST_CROPS, HARVEST_REGION, COPY_BUILD, SCHEMATIC_BUILD, MINE_DEPOSIT, GATHER_BUILD,
				PATROL_AREA, HUNT_MOBS, PATROL_REGION, GUARD_REGION -> null;
			case GOTO_POINT, FLY_TO -> this.point;
			// the nearest block of the kind, the drone has to find them, the reward knows where they are
			case FIND_BLOCK, SEEK_BLOCK -> this.nearestTarget(drone, p -> true);
			// see follow
			case FOLLOW_MOB, FOLLOW_PLAYER -> null;
		};
	}

	private @Nullable Vec3 nearestTarget(Vec3 drone, Predicate<BlockPos> isTarget) {
		Vec3 best = null;
		if (this.arena.has("targets")) {
			for (JsonElement el : this.arena.getAsJsonArray("targets")) {
				BlockPos pos = Json.readPos(el.getAsJsonArray());
				Vec3 center = Vec3.atCenterOf(pos);
				if (isTarget.test(pos) && (best == null || drone.distanceTo(center) < drone.distanceTo(best))) {
					best = center;
				}
			}
		}
		return best;
	}

	private @Nullable Vec3 point(String key) {
		return this.arena.has(key) ? Vec3.atCenterOf(Json.readPos(this.arena.getAsJsonArray(key))) : null;
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
		json.addProperty("droneCollisions", this.droneCollisions);
		json.addProperty("outOfBounds", this.outOfBounds);
		json.addProperty("damage", this.damage);
		json.addProperty("wrecked", this.wrecked);
		JsonArray m = new JsonArray();
		for (int v : this.metrics) {
			m.add(v);
		}
		json.add("metrics", m);
		if (this.followTarget >= 0) {
			json.addProperty("inBand", this.inBand);
		}
		if (!this.patrolCells.isEmpty()) {
			JsonObject patrol = new JsonObject();
			patrol.addProperty("visited", this.visitedCount());
			patrol.addProperty("cells", this.patrolCells.size());
			patrol.addProperty("round", this.roundsDone);
			patrol.addProperty("rounds", this.rounds);
			json.add("patrol", patrol);
		}
		return json;
	}
}
