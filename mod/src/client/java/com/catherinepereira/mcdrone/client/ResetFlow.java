package com.catherinepereira.mcdrone.client;

import com.catherinepereira.mcdrone.Json;
import com.catherinepereira.mcdrone.client.bridge.Session;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.net.ResetTaskPayload;
import com.catherinepereira.mcdrone.net.TaskReadyPayload;
import com.catherinepereira.mcdrone.task.TaskKind;
import com.catherinepereira.mcdrone.tool.ToolRequest;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Starts one drone's episodes and jobs. Asks the server to build the arena or start the job, waits for the drone to reach
 * the client and the rebuilt blocks to settle, then starts scoring and recording and answers with the first frame
 */
final class ResetFlow {
	private record Pending(
		int requestId, TaskKind kind, long seed, int radius, int obstacles, int targets, int maxSteps, float successDist, @Nullable Session session,
		@Nullable Integer replyId, @Nullable JsonObject fleet
	) {
	}

	private final ClientRuntime runtime;
	private final DroneRun run;
	private @Nullable Pending pending;
	private @Nullable TaskReadyPayload ready;
	private int readyWaitTicks;
	private int settleTicks;
	// shared by every drone's flow, so the server's answer finds the flow that asked
	private static int requestSeq;
	private static int episodeCounter;

	ResetFlow(ClientRuntime runtime, DroneRun run) {
		this.runtime = runtime;
		this.run = run;
	}

	boolean active() {
		return this.pending != null;
	}

	/** Returns why the reset can't start, or null once the server has been asked */
	@Nullable String start(long seed, JsonObject options, @Nullable Session session, @Nullable Integer replyId) {
		if (this.pending != null) {
			return "a reset is already in progress";
		}
		if (!ClientPlayNetworking.canSend(ResetTaskPayload.TYPE)) {
			return "not in a world";
		}
		Config config = this.runtime.config();
		TaskKind kind;
		int[] region = new int[0];
		String subject = JobOptions.subject(options);
		try {
			kind = TaskKind.parse(options.has("task") ? options.get("task").getAsString() : config.task);
			if (kind.isJob()) {
				region = JobOptions.region(kind, options, subject, this.runtime.selection, this.runtime.regions());
			}
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
		int radius = options.has("radius") ? options.get("radius").getAsInt() : config.radius;
		int targets = options.has("targets") ? options.get("targets").getAsInt() : kind == TaskKind.MINE_AND_DELIVER ? 3 : kind == TaskKind.HUNT_MOBS ? 4 : 1;
		String terrain = options.has("terrain") ? options.get("terrain").getAsString() : config.terrain;
		int obstacles = options.has("obstacles") ? options.get("obstacles").getAsInt() : config.obstacles;
		int size = options.has("size") ? options.get("size").getAsInt() : 5;
		boolean scan = "scan".equals(options.has("perception") ? options.get("perception").getAsString() : config.perception);
		int maxSteps = options.has("maxSteps") ? options.get("maxSteps").getAsInt() : JobOptions.defaultMaxSteps(kind, config.maxSteps);
		float successDist = options.has("successDist") ? options.get("successDist").getAsFloat() : config.successDist;
		String tier = options.has("tier") ? options.get("tier").getAsString() : "";
		JsonObject fleet = options.has("fleet") && options.get("fleet").isJsonObject() ? options.getAsJsonObject("fleet") : null;
		this.pending = new Pending(++requestSeq, kind, seed, radius, obstacles, targets, maxSteps, successDist, session, replyId, fleet);
		this.ready = null;
		if (this.run == this.runtime.active()) {
			this.runtime.cancelAutoReset();
		}
		if (this.run.task.active()) {
			this.run.task.end();
			ClientRuntime.jobEnded(this.run);
		}
		boolean hasOrigin = config.arenaX != null && config.arenaZ != null;
		ResetTaskPayload request = new ResetTaskPayload(
			this.pending.requestId(), kind.id, terrain, seed, radius, obstacles, targets, hasOrigin, hasOrigin ? config.arenaX : 0, hasOrigin ? config.arenaZ : 0,
			region, subject, size, scan, tier, this.run.droneId()
		);
		if (fleet != null) {
			String problem = kind.isJob() ? "a fleet shares a training arena, " + kind.id + " is a player job"
				: !fleet.has("group") || !fleet.has("size") || !fleet.has("member") ? "fleet takes group, size, and member"
				: this.runtime.fleets().join(fleet.get("group").getAsString(), fleet.get("size").getAsInt(), fleet.get("member").getAsInt(), this, request);
			if (problem != null) {
				this.pending = null;
				return problem;
			}
		} else {
			ClientPlayNetworking.send(request);
		}
		this.runtime.log().info(
			"task.reset_requested",
			DroneLog.fields("task", kind.id, "terrain", terrain, "seed", seed, "radius", radius, "obstacles", obstacles, "targets", targets, "maxSteps", maxSteps)
		);
		return null;
	}

	boolean awaits(TaskReadyPayload payload) {
		return this.pending != null && this.pending.requestId() == payload.requestId() && this.ready == null;
	}

	void onTaskReady(TaskReadyPayload payload) {
		if (!this.awaits(payload)) {
			return;
		}
		if (!payload.error().isEmpty()) {
			this.fail(payload.error());
			return;
		}
		Config config = this.runtime.config();
		if (config.arenaX == null || config.arenaZ == null) {
			config.arenaX = payload.origin().getX();
			config.arenaZ = payload.origin().getZ();
			config.save();
		}
		this.ready = payload;
		this.readyWaitTicks = 0;
		this.settleTicks = -1;
	}

	void tick() {
		Pending pending = this.pending;
		TaskReadyPayload ready = this.ready;
		if (pending == null || ready == null) {
			return;
		}
		DroneController controller = this.run.controller;
		if (this.settleTicks < 0) {
			if (!(this.runtime.level().getEntity(ready.droneId()) instanceof DroneEntity drone)) {
				if (++this.readyWaitTicks > 100) {
					this.fail("drone entity never reached the client");
				}
				return;
			}
			controller.adopt(drone);
			controller.setPose(ready.x(), ready.y(), ready.z(), ready.yaw(), 0.0F);
			if (this.run == this.runtime.active()) {
				controller.setPiloting(true);
				this.runtime.keyboard().reset();
			}
			controller.sendPose();
			this.settleTicks = this.runtime.config().resetSettleTicks;
			return;
		}
		// block updates from the rebuild need a few ticks to reach the client and re-mesh
		if (this.settleTicks-- > 0) {
			return;
		}
		this.pending = null;
		this.ready = null;

		Config config = this.runtime.config();
		TaskKind kind = pending.kind();
		String id = kind.id + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + String.format("%04d", ++episodeCounter);
		JsonObject arena = JsonParser.parseString(ready.arena()).getAsJsonObject();
		this.run.task.begin(
			id, kind, pending.seed(), ready.marker(), arena, pending.maxSteps(), pending.successDist(), config.collisionPenalty, config.boundsPadding,
			config.outOfBoundsPenalty, config.damagePenalty
		);
		this.run.tools.reset();
		this.run.selectedSlot = 0;
		this.runtime.syncFrozen();
		if (this.run == this.runtime.active()) {
			this.runtime.clearQueuedTools();
		}
		this.runtime.log().setEpisode(id);
		this.runtime.recorder().beginEpisode(this.run.droneId(), kind.id, id, this.meta(id, pending, ready, arena));
		this.runtime.log().info("task.reset", DroneLog.fields("seed", pending.seed(), "marker", Json.pos(ready.marker()), "recording", this.runtime.recorder().recording(this.run.droneId())));
		this.runtime.broadcastStatus();

		// one idle tool tick fetches the fresh inventory and metrics before the first frame
		this.runtime.afterSync(
			this.run, this.runtime.sendTool(this.run, ToolRequest.IDLE),
			() -> this.runtime.capture().request(this.run, obs -> this.runtime.broadcastObs(this.run, obs, pending.session(), pending.replyId()))
		);
	}

	/** The reset can't finish, its requester gets the error */
	void fail(String error) {
		Pending pending = this.pending;
		this.pending = null;
		this.ready = null;
		this.runtime.log().warn("task.reset_failed", DroneLog.fields("error", error));
		if (pending != null && pending.session() != null) {
			pending.session().error("reset failed: " + error, pending.replyId());
		}
	}

	// meta.json for the recording, see Recorded episodes in docs/PROTOCOL.md
	private JsonObject meta(String id, Pending pending, TaskReadyPayload ready, JsonObject arena) {
		Config config = this.runtime.config();
		JsonObject meta = new JsonObject();
		meta.addProperty("id", id);
		meta.addProperty("task", pending.kind().id);
		meta.addProperty("seed", pending.seed());
		JsonObject options = new JsonObject();
		options.addProperty("radius", pending.radius());
		options.addProperty("obstacles", pending.obstacles());
		options.addProperty("targets", pending.targets());
		options.addProperty("terrain", arena.has("terrain") ? arena.get("terrain").getAsString() : "flat");
		options.addProperty("maxSteps", pending.maxSteps());
		options.addProperty("successDist", pending.successDist());
		meta.add("options", options);
		meta.addProperty("width", config.width);
		meta.addProperty("height", config.height);
		meta.add("streams", Config.tree(config.streams));
		meta.addProperty("depthMax", config.depthMax);
		meta.addProperty("schema", ClientRuntime.SCHEMA);
		meta.addProperty("modVersion", FabricLoader.getInstance().getModContainer("mcdrone").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev"));
		meta.addProperty("pilot", pending.session() != null ? "bridge" : "keyboard");
		if (pending.session() != null) {
			meta.addProperty("client", pending.session().client);
		}
		meta.addProperty("startedAt", java.time.Instant.now().toString());
		meta.add("marker", Json.pos(ready.marker()));
		meta.add("arena", arena);
		if (pending.fleet() != null) {
			meta.add("fleet", pending.fleet());
		}
		return meta;
	}
}
