package com.catherinepereira.mcdrone.client;

import com.catherinepereira.mcdrone.client.bridge.Session;
import com.catherinepereira.mcdrone.client.obs.IdTables;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.net.ExportSchematicPayload;
import com.catherinepereira.mcdrone.net.RenameDronePayload;
import com.google.gson.JsonObject;
import java.util.Random;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.jspecify.annotations.Nullable;

/**
 * The bridge's JSON messages, see docs/PROTOCOL.md. Each drone has at most one controller, a client that names a drone in
 * hello controls that one and the others control the active drone. Lockstep and realtime are for the whole world
 */
final class BridgeCommands {
	private final ClientRuntime runtime;
	private final Random seeds = new Random();
	// the controller that turned recording on, it stops when that controller goes
	private @Nullable Session recordingOwner;

	BridgeCommands(ClientRuntime runtime) {
		this.runtime = runtime;
	}

	/** The player took recording over from the keyboard, so it outlives the controller */
	void playerToggledRecording() {
		this.recordingOwner = null;
	}

	void handle(Session session, JsonObject msg) {
		String type = msg.has("type") ? msg.get("type").getAsString() : "";
		Integer id = msg.has("id") && msg.get("id").isJsonPrimitive() && msg.get("id").getAsJsonPrimitive().isNumber() ? msg.get("id").getAsInt() : null;
		if (!session.greeted && !type.equals("hello") && !type.equals("ping")) {
			session.error("send hello first", id);
			return;
		}
		DroneRun run = this.runtime.runOf(session);
		switch (type) {
			case "hello" -> this.hello(session, msg);
			case "subscribe" -> {
				if (msg.has("obs")) {
					session.wantsObs = msg.get("obs").getAsBoolean();
				}
				if (msg.has("logs")) {
					session.wantsLogs = msg.get("logs").getAsBoolean();
				}
				if (msg.has("metrics")) {
					session.wantsMetrics = msg.get("metrics").getAsBoolean();
				}
			}
			case "ping" -> {
				JsonObject pong = new JsonObject();
				pong.addProperty("type", "pong");
				if (id != null) {
					pong.addProperty("replyTo", id);
				}
				pong.addProperty("tick", this.runtime.tickCount());
				session.send(pong);
			}
			case "memory" -> {
				// a controlling brain's voxel memory, passed through to the dashboards that show it
				if (run == null) {
					session.error("memory updates come from a controller", id);
					return;
				}
				JsonObject tagged = msg.deepCopy();
				tagged.addProperty("drone", run.droneId());
				this.runtime.sendToOthers(session, tagged);
			}
			case "queue" -> {
				// queues are the player's, any client may add to them
				DroneEntity drone = msg.has("drone") && !msg.get("drone").isJsonNull() ? this.runtime.ownedDrone(msg.get("drone").getAsInt()) : this.runtime.active().drone();
				String problem = !msg.has("job") || !msg.get("job").isJsonObject() ? "queue needs a job, the options a reset takes"
					: drone == null ? "no such drone of yours is loaded" : this.runtime.queueJob(drone, msg.getAsJsonObject("job"));
				if (problem != null) {
					session.error(problem, id);
					return;
				}
				JsonObject queued = new JsonObject();
				queued.addProperty("type", "queued");
				if (id != null) {
					queued.addProperty("replyTo", id);
				}
				session.send(queued);
			}
			case "run_queues" -> {
				JsonObject started = new JsonObject();
				started.addProperty("type", "queues_started");
				started.addProperty("drones", this.runtime.runAllQueues());
				if (id != null) {
					started.addProperty("replyTo", id);
				}
				session.send(started);
			}
			case "select" -> {
				// the copy selection is the player's, any client may edit it
				this.runtime.selection.merge(msg);
				this.runtime.broadcastStatus();
			}
			case "region_save" -> {
				String problem = this.runtime.saveRegion(msg.has("name") ? msg.get("name").getAsString() : "", msg.has("purpose") ? msg.get("purpose").getAsString() : "general");
				if (problem != null) {
					session.error(problem, id);
				}
			}
			case "region_delete" -> this.runtime.removeRegion(msg.get("region").getAsString());
			case "region_use" -> {
				if (!this.runtime.useRegion(msg.get("region").getAsString())) {
					session.error("no region with that id", id);
				}
			}
			case "export" -> {
				String name = msg.has("name") ? msg.get("name").getAsString() : "";
				Selection selection = this.runtime.selection;
				if (selection.size() == null || !ClientPlayNetworking.canSend(ExportSchematicPayload.TYPE)) {
					session.error("select both source corners in a world first", id);
					return;
				}
				ClientPlayNetworking.send(new ExportSchematicPayload(selection.cornerA, selection.cornerB, name.endsWith(".schem") ? name : name + ".schem"));
			}
			case "rename" -> {
				if (!ClientPlayNetworking.canSend(RenameDronePayload.TYPE)) {
					session.error("not in a world", id);
					return;
				}
				ClientPlayNetworking.send(new RenameDronePayload(msg.has("name") ? msg.get("name").getAsString() : ""));
			}
			case "configure", "act", "step", "reset", "record", "pilot", "release" -> {
				if (run == null) {
					session.error(type + " needs the controller role", id);
					return;
				}
				this.controllerMessage(session, run, type, msg, id);
			}
			default -> session.error("unknown message type '" + type + "'", id);
		}
	}

	void onDisconnect(Session session) {
		this.runtime.log().info("bridge.disconnect", DroneLog.fields("session", session.id, "client", session.client));
		DroneRun run = this.runtime.runOf(session);
		if (run != null) {
			this.release(run, "disconnected");
		}
		this.runtime.broadcastStatus();
	}

	private void hello(Session session, JsonObject msg) {
		int schema = msg.has("schema") ? msg.get("schema").getAsInt() : -1;
		if (schema != ClientRuntime.SCHEMA) {
			session.error("schema " + schema + " is not supported, the mod speaks " + ClientRuntime.SCHEMA, null);
			session.channel.close();
			return;
		}
		session.client = msg.has("client") ? msg.get("client").getAsString() : "unknown";
		session.greeted = true;
		String role = msg.has("role") ? msg.get("role").getAsString() : "observer";
		if (role.equals("controller")) {
			this.claim(session, msg.has("drone") && !msg.get("drone").isJsonNull() ? msg.get("drone").getAsInt() : -1);
		}
		DroneRun run = this.runtime.runOf(session);
		JsonObject welcome = new JsonObject();
		welcome.addProperty("type", "welcome");
		welcome.addProperty("schema", ClientRuntime.SCHEMA);
		welcome.addProperty("role", run != null ? "controller" : "observer");
		if (run != null) {
			welcome.addProperty("drone", run.droneId());
		}
		welcome.addProperty("session", this.runtime.log().session());
		welcome.add("config", this.runtime.config().toJson());
		welcome.add("status", this.runtime.statusJson());
		welcome.add("maskIds", IdTables.maskIds());
		welcome.add("itemIds", IdTables.itemIds());
		session.send(welcome);
		this.runtime.log().info("bridge.hello", DroneLog.fields("session", session.id, "client", session.client, "role", welcome.get("role").getAsString()));
		this.runtime.broadcastStatus();
	}

	// makes session the controller of the drone with entity id droneId, or of the active drone for -1
	private void claim(Session session, int droneId) {
		DroneRun run;
		if (droneId < 0) {
			run = this.runtime.active();
		} else {
			DroneEntity drone = this.runtime.ownedDrone(droneId);
			if (drone == null) {
				session.error("drone " + droneId + " isn't one of yours or isn't loaded, connected as observer", null);
				return;
			}
			run = this.runtime.runFor(drone);
		}
		Session holder = run.session;
		if (holder != null && holder != session && holder.channel.isActive()) {
			session.error("another client controls that drone, connected as observer", null);
			return;
		}
		run.session = session;
		run.setBridgeAction(DroneAction.ZERO);
	}

	private void controllerMessage(Session session, DroneRun run, String type, JsonObject msg, @Nullable Integer id) {
		switch (type) {
			case "configure" -> {
				JsonObject patch = msg.deepCopy();
				patch.remove("type");
				patch.remove("id");
				if (patch.has("mode")) {
					String mode = patch.remove("mode").getAsString();
					this.runtime.setMode(mode.equals("lockstep") ? ClientRuntime.Mode.LOCKSTEP : ClientRuntime.Mode.REALTIME);
				}
				if (!patch.isEmpty()) {
					this.runtime.config().merge(patch);
				}
				this.runtime.log().info("bridge.configure", msg);
				this.runtime.broadcastStatus();
			}
			case "act" -> {
				if (this.runtime.mode() != ClientRuntime.Mode.REALTIME) {
					session.error("act is for realtime mode, use step in lockstep", id);
					return;
				}
				run.setBridgeAction(DroneAction.fromJson(msg.getAsJsonObject("action"), this.runtime.config().maxLookPerTick, DroneEntity.INVENTORY_SIZE));
			}
			case "step" -> this.step(session, run, msg, id);
			case "reset" -> {
				long seed = msg.has("seed") && !msg.get("seed").isJsonNull() ? msg.get("seed").getAsLong() : this.seeds.nextLong() & 0xFFFFFFFFL;
				JsonObject options = msg.has("options") && msg.get("options").isJsonObject() ? msg.getAsJsonObject("options") : new JsonObject();
				String problem = run.resets.start(seed, options, session, id);
				if (problem != null) {
					session.error(problem, id);
				}
			}
			case "record" -> {
				boolean on = msg.get("on").getAsBoolean();
				this.recordingOwner = on ? session : null;
				this.runtime.recorder().setArmed(on, msg.has("test") && msg.get("test").getAsBoolean());
				this.runtime.broadcastStatus();
			}
			case "pilot" -> {
				if (!this.runtime.pilot(run, msg.get("on").getAsBoolean())) {
					session.error("no drone to pilot, send reset first", id);
				}
				this.runtime.broadcastStatus();
			}
			case "release" -> this.release(run, "released");
			default -> {
			}
		}
	}

	private void step(Session session, DroneRun run, JsonObject msg, @Nullable Integer id) {
		if (this.runtime.mode() != ClientRuntime.Mode.LOCKSTEP) {
			session.error("step is for lockstep mode, send configure with mode lockstep first", id);
			return;
		}
		if (run.resets.active()) {
			session.error("a reset is in progress", id);
			return;
		}
		if (run.drone() == null) {
			session.error("no drone, send reset first", id);
			return;
		}
		if (run.task.episodeId() != null && !run.task.active()) {
			session.error("episode is done, send reset", id);
			return;
		}
		int ticks = msg.has("ticks") ? Math.clamp(msg.get("ticks").getAsInt(), 1, 100) : 1;
		JsonObject actionJson = msg.has("action") ? msg.getAsJsonObject("action") : new JsonObject();
		this.runtime.stepAndCapture(run, DroneAction.fromJson(actionJson, this.runtime.config().maxLookPerTick, DroneEntity.INVENTORY_SIZE), ticks, session, id);
	}

	private void release(DroneRun run, String reason) {
		this.runtime.fleets().leave(run.resets);
		Session session = run.session;
		run.session = null;
		run.setBridgeAction(DroneAction.ZERO);
		// recording a controller turned on ends with it, so the world doesn't keep chaining episodes nobody flies
		if (session != null && session == this.recordingOwner) {
			this.recordingOwner = null;
			this.runtime.recorder().setArmed(false);
		}
		// never leave the world frozen without someone to step it
		if (!this.runtime.hasController()) {
			this.runtime.setMode(ClientRuntime.Mode.REALTIME);
		}
		this.runtime.log().info("bridge.controller_released", DroneLog.fields("reason", reason, "drone", run.droneId()));
		this.runtime.broadcastStatus();
	}
}
