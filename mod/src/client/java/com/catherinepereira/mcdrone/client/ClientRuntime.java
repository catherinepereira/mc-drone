package com.catherinepereira.mcdrone.client;

import com.catherinepereira.mcdrone.Json;
import com.catherinepereira.mcdrone.TabletInput;
import com.catherinepereira.mcdrone.ModContent;
import com.catherinepereira.mcdrone.entity.BatteryConfig;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.client.bridge.BridgeServer;
import com.catherinepereira.mcdrone.client.bridge.Session;
import com.catherinepereira.mcdrone.client.obs.FrameGrabber;
import com.catherinepereira.mcdrone.client.obs.Observation;
import com.catherinepereira.mcdrone.client.obs.Raycaster;
import com.catherinepereira.mcdrone.client.record.Recorder;
import com.catherinepereira.mcdrone.client.task.TaskScorer;
import com.catherinepereira.mcdrone.net.DroneQueuePayload;
import com.catherinepereira.mcdrone.net.DroneSyncPayload;
import com.catherinepereira.mcdrone.net.DroneToolPayload;
import com.catherinepereira.mcdrone.net.ExportSchematicPayload;
import com.catherinepereira.mcdrone.net.ResetTaskPayload;
import com.catherinepereira.mcdrone.net.RegionEditPayload;
import com.catherinepereira.mcdrone.net.RenameDronePayload;
import com.catherinepereira.mcdrone.net.SelectDronePayload;
import com.catherinepereira.mcdrone.net.SetFrozenPayload;
import com.catherinepereira.mcdrone.net.TaskReadyPayload;
import com.catherinepereira.mcdrone.task.TaskKind;
import com.catherinepereira.mcdrone.tool.DroneTool;
import com.catherinepereira.mcdrone.tool.ToolRequest;
import com.catherinepereira.mcdrone.client.hud.DroneScreen;
import com.catherinepereira.mcdrone.client.hud.JobScreen;
import com.catherinepereira.mcdrone.client.hud.TabletScreen;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderTarget;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns every client subsystem and runs on the client thread.
 * Bridge messages arrive through onClientThread, ticks through tick(), frames through onFrameRendered()
 */
public final class ClientRuntime {
	public static final Logger LOGGER = LoggerFactory.getLogger("mcdrone");
	public static final int SCHEMA = 2;

	public enum Mode {
		REALTIME,
		LOCKSTEP
	}

	private final Minecraft mc;
	private final Config config;
	private final DroneLog log;
	private final Metrics metrics = new Metrics();
	private final DroneController controller;
	private final KeyboardPilot keyboard = new KeyboardPilot();
	private final TaskScorer task = new TaskScorer();
	private final ToolState tools = new ToolState();
	private final Recorder recorder;
	private final BridgeServer bridge;
	private final Random seeds = new Random();
	public final Selection selection = new Selection();
	// the world's named regions as the server last sent them
	private JsonArray regions = new JsonArray();
	private static final Map<String, Integer> REGION_COLORS = Map.of("safe", 0x2E9E63, "mine", 0xC98A1B, "farm", 0x7DBA3A, "general", 0x8A94A6);

	private Mode mode = Mode.REALTIME;
	private @Nullable Session controllerSession;
	private DroneAction bridgeAction = DroneAction.ZERO;
	private boolean hudVisible = true;
	private long tick;
	private long lastMetricsMs;
	private int episodeCounter;
	private int autoResetIn = -1;

	private @Nullable PendingReset pendingReset;
	private int resetRequestSeq;
	// the job that just ended, the next tick starts the drone's next queued job or sends it home
	private @Nullable TaskKind finishedJob;
	private boolean finishedJobSucceeded;
	// the drones part of the last status, a change sends a new status
	private JsonArray lastDrones = new JsonArray();

	private final List<Consumer<Observation>> captureListeners = new ArrayList<>();
	// set while the next frame renders from the third-person camera for the chase stream
	private @Nullable Consumer<byte[]> chaseListener;
	private int chaseWaitFrames;
	private @Nullable JsonObject captureState;
	private @Nullable JsonObject captureEpisode;
	private @Nullable JsonObject captureAction;
	private long captureTick;
	private int captureWaitFrames;
	private long obsSeq;
	private @Nullable JsonObject maskIds;
	private @Nullable JsonArray itemIds;

	private int toolSeq;
	private long oneShotReadyMs;
	// work held back by a pause, as wall clock deadlines, run from tick()
	private final List<Map.Entry<Long, Runnable>> delayed = new ArrayList<>();
	private int selectedSlot;
	// one-shot tool requests from the inventory screen, applied on the next keyboard tick
	private final Deque<ToolRequest> queuedTools = new ArrayDeque<>();
	private final TreeMap<Integer, List<Runnable>> syncWaiters = new TreeMap<>();
	// frames to wait before a capture, so block edits from a tool sync get re-meshed first
	private int captureMinFrames;
	private int pendingCaptureFrames;

	public ClientRuntime(Minecraft mc) {
		this.mc = mc;
		Path gameDir = FabricLoader.getInstance().getGameDir();
		// dev runs send recordings to the training folder and logs to the mod's run folder, see build.gradle
		String data = System.getProperty("mcdrone.data");
		String logs = System.getProperty("mcdrone.logs");
		this.config = Config.load(FabricLoader.getInstance().getConfigDir().resolve("mcdrone.json"));
		String session = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		this.log = new DroneLog(logs != null ? Path.of(logs) : gameDir.resolve("mcdrone").resolve("logs"), session, this.config);
		this.controller = new DroneController(mc, this.config);
		this.recorder = new Recorder(data != null ? Path.of(data) : gameDir.resolve("mcdrone").resolve("data"), this.log);
		this.log.addListener(this.recorder::appendLog);
		this.bridge = new BridgeServer(this);
		this.log.addListener(this::forwardLog);
		this.log.info("runtime.start", DroneLog.fields("data", this.recorder.dataDir().toAbsolutePath().toString()));
	}

	public void start() {
		this.bridge.start(this.config.port);
	}

	public void stop() {
		this.bridge.stop();
	}

	public Config config() {
		return this.config;
	}

	public DroneLog log() {
		return this.log;
	}

	public Recorder recorder() {
		return this.recorder;
	}

	public DroneController controller() {
		return this.controller;
	}

	public TaskScorer task() {
		return this.task;
	}

	public Mode mode() {
		return this.mode;
	}

	public ToolState tools() {
		return this.tools;
	}

	public int selectedSlot() {
		return this.selectedSlot;
	}

	public void selectSlot(int slot) {
		this.selectedSlot = Math.clamp(slot, 0, DroneEntity.INVENTORY_SIZE - 1);
	}

	public KeyboardPilot keyboard() {
		return this.keyboard;
	}

	/** Queues a one-shot tool request from the inventory screen for the next keyboard tick */
	public void queueTool(ToolRequest request) {
		this.queuedTools.addLast(request);
	}

	public Metrics metrics() {
		return this.metrics;
	}

	public boolean hudVisible() {
		return this.hudVisible;
	}

	public boolean hasController() {
		return this.controllerSession != null;
	}

	public int observerCount() {
		return this.bridge.sessions().size() - (this.controllerSession == null ? 0 : 1);
	}

	public void onClientThread(Runnable task) {
		this.mc.execute(() -> {
			try {
				task.run();
			} catch (RuntimeException e) {
				this.log.error("runtime.task_failed", e, null);
			}
		});
	}

	public <T> T callOnClient(Supplier<T> supplier) {
		try {
			return this.mc.submit(supplier).get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException("client thread did not answer: " + e, e);
		}
	}

	// ---- keybind actions ----

	public void togglePiloting() {
		boolean on = !this.controller.piloting();
		if (!this.controller.setPiloting(on)) {
			this.toast("No drone nearby. Place one with the drone item or press N to start a task.");
			return;
		}
		this.keyboard.reset();
		this.log.info("pilot.toggle", DroneLog.fields("on", on));
	}

	/** Opens the drone inventory screen, and the faced container with it when there is one in reach */
	public void openInventory() {
		if (!this.controller.piloting() || this.controller.drone() == null) {
			this.toast("Pilot the drone first (V)");
			return;
		}
		this.queueTool(new ToolRequest(DroneTool.OPEN, this.selectedSlot, ToolRequest.NO_TRANSFER, 0, -1, 0));
		this.mc.gui.setScreen(new DroneScreen(this));
	}

	public void toggleRecording() {
		this.recorder.setArmed(!this.recorder.armed());
		this.toast(this.recorder.armed() ? "Recording armed, starts with the next episode" : "Recording stopped");
	}

	public void toggleHud() {
		this.hudVisible = !this.hudVisible;
	}

	public void newEpisodeFromKeyboard() {
		this.startReset(this.seeds.nextLong() & 0xFFFFFFFFL, new JsonObject(), null, null);
	}

	/** A click with the tablet */
	public void select(TabletInput.Target target, BlockPos pos) {
		switch (target) {
			case CORNER_A -> this.selection.cornerA = pos;
			case CORNER_B -> this.selection.cornerB = pos;
			case DEST -> this.selection.dest = pos;
		}
		BlockPos size = this.selection.size();
		String name = switch (target) {
			case CORNER_A -> "Source corner 1";
			case CORNER_B -> "Source corner 2";
			case DEST -> "Paste point";
		};
		String problem = this.selection.problem();
		this.toast(name + " set at " + pos.toShortString() + (size != null ? ", source " + size.toShortString() : "") + (problem != null ? ". Next: " + problem : ". Ready to copy"));
		this.broadcastStatus();
	}

	public void onRegions(String json) {
		this.regions = JsonParser.parseString(json).getAsJsonArray();
		this.broadcastStatus();
	}

	public JsonArray regions() {
		return this.regions;
	}

	private void sendRegionEdit(JsonObject edit) {
		if (ClientPlayNetworking.canSend(RegionEditPayload.TYPE)) {
			ClientPlayNetworking.send(new RegionEditPayload(edit.toString()));
		}
	}

	/** Saves the selection's source box as a named region */
	public @Nullable String saveRegion(String name, String purpose) {
		if (this.selection.size() == null) {
			return "select both corners with the tablet first";
		}
		JsonObject edit = new JsonObject();
		edit.addProperty("op", "put");
		edit.addProperty("name", name);
		edit.addProperty("purpose", purpose);
		JsonArray box = new JsonArray();
		for (BlockPos p : new BlockPos[] {this.selection.cornerA, this.selection.cornerB}) {
			box.add(p.getX());
			box.add(p.getY());
			box.add(p.getZ());
		}
		edit.add("box", box);
		this.sendRegionEdit(edit);
		return null;
	}

	public void removeRegion(String id) {
		JsonObject edit = new JsonObject();
		edit.addProperty("op", "remove");
		edit.addProperty("id", id);
		this.sendRegionEdit(edit);
	}

	/** Loads a region's box into the selection's source corners */
	public boolean useRegion(String id) {
		for (JsonElement e : this.regions) {
			JsonObject r = e.getAsJsonObject();
			if (r.get("id").getAsString().equals(id)) {
				JsonArray box = r.getAsJsonArray("box");
				this.selection.cornerA = new BlockPos(box.get(0).getAsInt(), box.get(1).getAsInt(), box.get(2).getAsInt());
				this.selection.cornerB = new BlockPos(box.get(3).getAsInt(), box.get(4).getAsInt(), box.get(5).getAsInt());
				this.broadcastStatus();
				return true;
			}
		}
		return false;
	}

	private void outlineRegions() {
		for (JsonElement e : this.regions) {
			JsonObject r = e.getAsJsonObject();
			JsonArray box = r.getAsJsonArray("box");
			BlockPos min = new BlockPos(box.get(0).getAsInt(), box.get(1).getAsInt(), box.get(2).getAsInt());
			BlockPos size = new BlockPos(box.get(3).getAsInt(), box.get(4).getAsInt(), box.get(5).getAsInt()).subtract(min).offset(1, 1, 1);
			Selection.edges(this.mc.level, min, size, REGION_COLORS.getOrDefault(r.get("purpose").getAsString(), 0x8A94A6));
		}
	}

	public void openJobs() {
		this.mc.gui.setScreen(new JobScreen(this));
	}

	public void openTablet() {
		this.mc.gui.setScreen(new TabletScreen(this));
	}

	/** Makes drone the one jobs, piloting, and the bridge use, here and on the server */
	public void selectDrone(DroneEntity drone) {
		this.controller.adopt(drone);
		if (this.controller.piloting()) {
			this.mc.setCameraEntity(drone);
		}
		if (ClientPlayNetworking.canSend(SelectDronePayload.TYPE)) {
			ClientPlayNetworking.send(new SelectDronePayload(drone.getId()));
		}
		this.broadcastStatus();
	}

	/** Starts a job from the job screen, returning why it can't start or null */
	public @Nullable String startJob(JsonObject options) {
		TaskKind kind = TaskKind.parse(options.get("task").getAsString());
		String subject = jobSubject(options);
		String problem = this.jobRegion(kind, options.deepCopy(), subject);
		if (problem != null) {
			return problem;
		}
		if (this.pendingReset != null) {
			return "a job is already starting";
		}
		this.startReset(this.seeds.nextLong() & 0xFFFFFFFFL, options, null, null);
		return null;
	}

	/**
	 * Adds a job to the active drone's queue. The tablet selection is copied into the job now, so changing it later
	 * doesn't move queued work. Returns why the job can't be queued, or null
	 */
	public @Nullable String queueJob(JsonObject options) {
		DroneEntity drone = this.controller.drone();
		if (drone == null || !ClientPlayNetworking.canSend(DroneQueuePayload.TYPE)) {
			return "pick a drone on the tablet first";
		}
		TaskKind kind = TaskKind.parse(options.get("task").getAsString());
		String subject = jobSubject(options);
		String problem = this.jobRegion(kind, options.deepCopy(), subject);
		if (problem != null) {
			return problem;
		}
		JsonObject job = options.deepCopy();
		job.addProperty("label", jobLabel(kind, options, subject));
		if (!job.has("region") && (kind == TaskKind.COPY_REGION || kind == TaskKind.MINE_REGION || kind == TaskKind.HARVEST_REGION)) {
			job.add("source", Json.box(this.selection.cornerA, this.selection.cornerB));
		}
		if (!job.has("dest") && (kind == TaskKind.COPY_REGION || kind == TaskKind.BUILD_SCHEMATIC)) {
			job.add("dest", Json.pos(this.selection.dest));
		}
		JsonObject edit = new JsonObject();
		edit.addProperty("op", "add");
		edit.add("job", job);
		ClientPlayNetworking.send(new DroneQueuePayload(drone.getId(), edit.toString()));
		return null;
	}

	/** Drops one queued job of the active drone, or all of them for a negative index */
	public void removeQueued(int index) {
		DroneEntity drone = this.controller.drone();
		if (drone == null || !ClientPlayNetworking.canSend(DroneQueuePayload.TYPE)) {
			return;
		}
		JsonObject edit = new JsonObject();
		edit.addProperty("op", index < 0 ? "clear" : "remove");
		edit.addProperty("index", index);
		ClientPlayNetworking.send(new DroneQueuePayload(drone.getId(), edit.toString()));
	}

	/** Starts the active drone's first queued job, returning why it can't or null */
	public @Nullable String runQueue() {
		DroneEntity drone = this.controller.drone();
		JsonArray queue = drone == null ? new JsonArray() : drone.queue();
		if (queue.isEmpty()) {
			return "the queue is empty";
		}
		if (this.pendingReset != null || (this.task.active() && this.task.kind().isJob())) {
			return "a job is running, the queue continues after it";
		}
		String problem = this.startJob(queue.get(0).getAsJsonObject());
		if (problem == null) {
			this.removeQueued(0);
		}
		return problem;
	}

	public @Nullable String sendHome() {
		JsonObject options = new JsonObject();
		options.addProperty("task", TaskKind.RETURN_HOME.id);
		return this.startJob(options);
	}

	// "mine coal_ore, iron_ore in quarry", what the tablet and the dashboard show for a queued job
	private static String jobLabel(TaskKind kind, JsonObject options, String subject) {
		String region = savedName(options, "region", "the selection");
		String dest = savedName(options, "dest", "the paste point");
		String what = subject.replace("minecraft:", "").replace(",", ", ");
		return switch (kind) {
			case COPY_REGION -> "copy " + region + " to " + dest;
			case BUILD_SCHEMATIC -> "build " + what + " at " + dest;
			case MINE_REGION -> "mine " + what + " in " + region;
			case HARVEST_REGION -> "harvest " + what + " in " + region;
			case RETURN_HOME -> "return home";
			default -> kind.id;
		};
	}

	private static String savedName(JsonObject options, String key, String otherwise) {
		return options.has(key) && options.get(key).isJsonPrimitive() ? options.get(key).getAsString() : otherwise;
	}

	// after a job the next queued one starts, and with nothing left, or after a failure, the drone flies home to charge.
	// Only with a controller connected, the brain flies jobs
	private void advanceQueue() {
		TaskKind finished = this.finishedJob;
		if (finished == null || this.pendingReset != null) {
			return;
		}
		this.finishedJob = null;
		DroneEntity drone = this.controller.drone();
		if (this.controllerSession == null || drone == null || finished == TaskKind.RETURN_HOME) {
			return;
		}
		JsonArray queue = drone.queue();
		if (this.finishedJobSucceeded && !queue.isEmpty()) {
			String problem = this.runQueue();
			if (problem == null) {
				return;
			}
			this.toast("Queued job skipped: " + problem);
		} else if (!queue.isEmpty()) {
			this.toast("The job stopped, " + queue.size() + " queued jobs wait, run them from the tablet");
		}
		if (drone.home() != null && !drone.docked()) {
			this.sendHome();
		}
	}

	public void startCopyFromKeyboard() {
		JsonObject options = new JsonObject();
		options.addProperty("task", TaskKind.COPY_REGION.id);
		this.startReset(this.seeds.nextLong() & 0xFFFFFFFFL, options, null, null);
	}

	private void toast(String text) {
		if (this.mc.player != null) {
			this.mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(text));
		}
	}

	// ---- tick loop ----

	private void runAfter(long ms, Runnable then) {
		if (ms <= 0) {
			then.run();
		} else {
			this.delayed.add(Map.entry(System.currentTimeMillis() + ms, then));
		}
	}

	public void tick() {
		this.tick++;
		if (!this.delayed.isEmpty()) {
			long now = System.currentTimeMillis();
			List<Map.Entry<Long, Runnable>> due = this.delayed.stream().filter(e -> e.getKey() <= now).toList();
			this.delayed.removeAll(due);
			due.forEach(e -> e.getValue().run());
		}
		if (this.tick % 10 == 0 && this.mc.level != null && this.mc.player != null
			&& (this.mc.player.getMainHandItem().is(ModContent.TABLET) || (this.task.active() && this.task.kind().isJob()))) {
			this.selection.outline(this.mc.level);
			if (this.mc.player.getMainHandItem().is(ModContent.TABLET)) {
				this.outlineRegions();
			}
		}
		this.log.setTick(this.tick);
		long now = System.currentTimeMillis();
		if (now - this.lastMetricsMs >= 1000) {
			this.lastMetricsMs = now;
			JsonObject metrics = this.metrics.roll(this.mc.getFps(), this.observerCount(), this.recorder.queueDepth());
			for (Session s : this.bridge.sessions()) {
				if (s.wantsMetrics) {
					s.send(metrics);
				}
			}
			if (!this.dronesJson().equals(this.lastDrones)) {
				this.broadcastStatus();
			}
		}

		if (this.mc.level == null || this.mc.player == null) {
			return;
		}
		this.controller.validate();
		this.advanceReset();
		this.advanceQueue();

		if (this.autoResetIn > 0 && --this.autoResetIn == 0) {
			this.autoResetIn = -1;
			this.newEpisodeFromKeyboard();
		}

		if (this.mode != Mode.REALTIME || this.mc.isPaused() || this.pendingReset != null) {
			return;
		}
		DroneEntity drone = this.controller.drone();
		if (drone == null || (!this.controller.piloting() && this.controllerSession == null)) {
			return;
		}
		DroneAction action;
		if (this.controllerSession != null) {
			action = this.bridgeAction;
			this.bridgeAction = this.bridgeAction.continued();
		} else {
			action = this.keyboardAction();
		}
		boolean wasDone = this.task.done();
		this.applyAction(action, 1);
		this.checkFinished();

		boolean streamTick = this.tick % Math.max(1, 20 / this.config.streamHz) == 0;
		boolean finished = !wasDone && this.task.done();
		if (this.recorder.recording() || finished || (streamTick && this.anyObserverWantsObs())) {
			this.requestCapture(obs -> this.broadcastObs(obs, null, null));
		}
	}

	private DroneAction keyboardAction() {
		DroneAction keys = this.keyboard.read(this.mc, this.config.maxLookPerTick, this.selectedSlot);
		this.selectedSlot = keys.tools().slot();
		ToolRequest queued = this.queuedTools.pollFirst();
		if (queued == null) {
			return keys;
		}
		return new DroneAction(keys.forward(), keys.right(), keys.up(), keys.yaw(), keys.pitch(), queued);
	}

	/**
	 * Simulates the action for ticks ticks, sending the pose and tool intent after each one.
	 * Returns the seq of the last tool packet, or -1 when nothing tool-related was sent
	 */
	private int applyAction(DroneAction action, int ticks) {
		this.recorder.onAction(action);
		this.task.beginAction();
		if (action.tools().slot() != this.selectedSlot) {
			this.selectSlot(action.tools().slot());
		}
		int lastSeq = -1;
		for (int i = 0; i < ticks; i++) {
			boolean collided = this.controller.simulate(action);
			this.metrics.simStep();
			this.controller.sendPose();
			ToolRequest request = i == 0 ? action.tools() : action.tools().continued();
			// an idle tick still goes out while mining, so the server can cancel the crack
			if (!request.idle() || this.tools.breakProgress() > 0) {
				lastSeq = this.sendTool(request);
			}
			DroneEntity drone = this.controller.drone();
			if (drone != null) {
				this.task.update(drone.getBoundingBox().getCenter(), collided, this::isTargetBlock);
				if (this.task.active() && this.task.kind() == TaskKind.RETURN_HOME && drone.docked()) {
					this.task.complete();
				}
			}
			if (this.task.done()) {
				break;
			}
		}
		this.log.debug("action", DroneLog.fields("action", action.toJson(), "ticks", ticks));
		return lastSeq;
	}

	private int sendTool(ToolRequest request) {
		DroneEntity drone = this.controller.drone();
		if (drone == null || !ClientPlayNetworking.canSend(DroneToolPayload.TYPE)) {
			return -1;
		}
		// in realtime a held place or transfer would fire every tick, space them out
		boolean oneShot = !request.continued().equals(request) && request.tool() != DroneTool.BREAK;
		if (this.mode == Mode.REALTIME && oneShot) {
			long now = System.currentTimeMillis();
			if (now < this.oneShotReadyMs) {
				request = request.continued();
			} else {
				this.oneShotReadyMs = now + this.config.actionPauseMs;
			}
		}
		int seq = ++this.toolSeq;
		ClientPlayNetworking.send(new DroneToolPayload(drone.getId(), seq, request.withMaterials("unlimited".equals(this.config.materials))));
		return seq;
	}

	/** Runs then once the server has answered tool packet seq, or right away for -1 */
	private void afterSync(int seq, Runnable then) {
		if (seq < 0 || this.tools.lastSeq() >= seq) {
			then.run();
			return;
		}
		this.syncWaiters.computeIfAbsent(seq, k -> new ArrayList<>()).add(then);
	}

	public void onSync(DroneSyncPayload sync) {
		if (sync.entityId() != this.controller.droneId() || this.mc.level == null) {
			return;
		}
		for (int i = 0; i < sync.changedPositions().length; i++) {
			this.mc.level.setBlock(BlockPos.of(sync.changedPositions()[i]), Block.stateById(sync.changedStates()[i]), Block.UPDATE_ALL_IMMEDIATE);
		}
		if (sync.changedPositions().length > 0) {
			this.pendingCaptureFrames = Math.max(this.pendingCaptureFrames, 3);
		}
		this.tools.update(sync);
		for (var e : JsonParser.parseString(sync.events()).getAsJsonArray()) {
			this.log.info("tool." + e.getAsJsonObject().get("type").getAsString(), e.getAsJsonObject());
		}
		boolean wasDone = this.task.done();
		this.task.applyMetrics(sync.metrics());
		this.checkFinished();
		// realtime episodes finished by world progress still need their terminal frame
		if (!wasDone && this.task.done() && this.mode == Mode.REALTIME) {
			this.requestCapture(obs -> this.broadcastObs(obs, null, null));
		}
		Map<Integer, List<Runnable>> ready = this.syncWaiters.headMap(sync.seq(), true);
		List<Runnable> runs = new ArrayList<>();
		ready.values().forEach(runs::addAll);
		ready.clear();
		runs.forEach(Runnable::run);
	}

	private boolean isTargetBlock(BlockPos pos) {
		return this.mc.level != null && this.mc.level.getBlockState(pos).is(Blocks.COAL_ORE);
	}

	private void checkFinished() {
		if (this.task.active() && this.task.done()) {
			this.onEpisodeFinished();
		}
	}

	private void onEpisodeFinished() {
		JsonObject snap = this.task.snapshot();
		this.log.info("task.done", snap);
		this.task.end();
		if (this.task.kind().isJob()) {
			this.finishedJob = this.task.kind();
			this.finishedJobSucceeded = snap.has("success") && snap.get("success").getAsBoolean();
		}
		// keyboard demos chain episodes so a pilot can record many in a row
		if (this.recorder.armed() && this.controllerSession == null) {
			this.autoResetIn = this.config.autoResetTicks;
		}
	}

	// ---- capture ----

	private boolean anyObserverWantsObs() {
		for (Session s : this.bridge.sessions()) {
			if (s.greeted && s.wantsObs) {
				return true;
			}
		}
		return false;
	}

	/** Snapshots state now and fills in pixels on the next rendered frame */
	public void requestCapture(Consumer<Observation> listener) {
		if (this.captureListeners.isEmpty()) {
			this.captureState = this.stateJson();
			this.captureEpisode = this.task.episodeId() == null ? null : this.task.snapshot();
			this.captureAction = this.controller.lastAction().toJson();
			this.captureTick = this.tick;
			this.captureWaitFrames = 0;
			this.captureMinFrames = this.pendingCaptureFrames;
			this.pendingCaptureFrames = 0;
			this.controller.pinRenderPose();
			if (!this.controller.piloting()) {
				this.controller.setPiloting(true);
			}
		}
		this.captureListeners.add(listener);
	}

	/** Called from the level render END_MAIN event, before the GUI draws */
	public void onFrameRendered(Camera camera) {
		if (this.chaseListener != null) {
			// the readback callback that asked for the chase view can land after this frame's camera was set up, so wait
			// for a frame rendered from the third-person camera
			if (!camera.isDetached() && this.chaseWaitFrames++ < 5) {
				return;
			}
			Consumer<byte[]> listener = this.chaseListener;
			this.chaseListener = null;
			this.chaseWaitFrames = 0;
			this.mc.options.setCameraType(CameraType.FIRST_PERSON);
			FrameGrabber.grab(this.mc.gameRenderer.mainRenderTarget(), this.config.chaseWidth, this.config.chaseHeight, rgb -> this.onClientThread(() -> listener.accept(rgb)), err -> {
				this.log.warn("capture.chase_failed", DroneLog.fields("error", err));
				this.onClientThread(() -> listener.accept(null));
			});
			return;
		}
		if (this.captureListeners.isEmpty()) {
			return;
		}
		DroneEntity drone = this.controller.drone();
		if (drone == null) {
			this.log.warn("capture.no_drone", null);
			this.captureListeners.clear();
			return;
		}
		if (this.captureMinFrames > 0) {
			this.captureMinFrames--;
			return;
		}
		if ((this.mc.getCameraEntity() != drone || camera.isDetached()) && this.captureWaitFrames++ < 10) {
			return;
		}
		List<Consumer<Observation>> listeners = new ArrayList<>(this.captureListeners);
		this.captureListeners.clear();
		int w = this.config.width;
		int h = this.config.height;
		RenderTarget target = this.mc.gameRenderer.mainRenderTarget();
		long seq = ++this.obsSeq;
		long frameTick = this.captureTick;
		JsonObject state = this.captureState;
		JsonObject episode = this.captureEpisode;
		JsonObject action = this.captureAction;

		long t0 = System.nanoTime();
		float[] depth = null;
		short[] mask = null;
		short[] blockStates = null;
		if (this.config.wants("depth") || this.config.wants("mask") || this.config.wants("state")) {
			depth = new float[w * h];
			mask = new short[w * h];
			blockStates = new short[w * h];
			Raycaster.Camera cam = new Raycaster.Camera(
				camera.position(), camera.forwardVector(), camera.upVector(), camera.leftVector(), camera.getFov(), (float) target.width / target.height
			);
			Raycaster.cast(this.mc.level, cam, drone, w, h, this.config.depthMax, depth, mask, blockStates);
		}
		double raycastMs = (System.nanoTime() - t0) / 1e6;
		float[] outDepth = this.config.wants("depth") ? depth : null;
		short[] outMask = this.config.wants("mask") ? mask : null;
		short[] outStates = this.config.wants("state") ? blockStates : null;

		Consumer<Observation> deliver = obs -> {
			for (Consumer<Observation> listener : listeners) {
				listener.accept(obs);
			}
			this.recorder.onObservation(obs);
		};
		Consumer<byte[]> finish = rgb -> {
			double totalMs = (System.nanoTime() - t0) / 1e6;
			this.metrics.capture(totalMs, raycastMs);
			Observation obs = new Observation(seq, frameTick, w, h, state, episode, action, rgb, outDepth, outMask, outStates);
			if (!this.config.wants("chase")) {
				deliver.accept(obs);
				return;
			}
			this.mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			this.chaseListener = chase -> {
				obs.chase = chase;
				obs.chaseWidth = this.config.chaseWidth;
				obs.chaseHeight = this.config.chaseHeight;
				deliver.accept(obs);
			};
		};
		if (this.config.wants("rgb")) {
			FrameGrabber.grab(target, w, h, rgb -> this.onClientThread(() -> finish.accept(rgb)), err -> {
				this.log.warn("capture.readback_failed", DroneLog.fields("error", err));
				this.onClientThread(() -> finish.accept(null));
			});
		} else {
			finish.accept(null);
		}
	}

	private void broadcastObs(Observation obs, @Nullable Session replyTo, @Nullable Integer replyId) {
		long t0 = System.nanoTime();
		byte[] observerBytes = null;
		for (Session s : this.bridge.sessions()) {
			if (s == replyTo || !s.greeted || !s.wantsObs) {
				continue;
			}
			if (observerBytes == null) {
				observerBytes = obs.encode(null);
			}
			// the realtime controller gets frames like an observer, dropping them under backpressure is fine
			if (!s.sendBinary(observerBytes, false)) {
				this.metrics.dropped();
			}
		}
		if (replyTo != null) {
			replyTo.sendBinary(obs.encode(replyId), true);
		}
		this.metrics.encode((System.nanoTime() - t0) / 1e6);
	}

	// ---- reset ----

	private record PendingReset(
		int requestId, TaskKind kind, long seed, int radius, int obstacles, int targets, int maxSteps, float successDist, @Nullable Session session,
		@Nullable Integer replyId
	) {
	}

	private @Nullable TaskReadyPayload readyPayload;
	private int readyWaitTicks;
	private int settleTicks;

	public void startReset(long seed, JsonObject options, @Nullable Session session, @Nullable Integer replyId) {
		if (this.pendingReset != null) {
			if (session != null) {
				session.error("a reset is already in progress", replyId);
			}
			return;
		}
		if (!ClientPlayNetworking.canSend(ResetTaskPayload.TYPE)) {
			if (session != null) {
				session.error("not in a world", replyId);
			}
			return;
		}
		TaskKind kind;
		try {
			kind = TaskKind.parse(options.has("task") ? options.get("task").getAsString() : this.config.task);
		} catch (IllegalArgumentException e) {
			if (session != null) {
				session.error(e.getMessage(), replyId);
			}
			return;
		}
		int[] region = new int[0];
		String subject = jobSubject(options);
		if (kind.isJob()) {
			String problem = this.jobRegion(kind, options, subject);
			if (problem != null) {
				if (session != null) {
					session.error(problem, replyId);
				} else {
					this.toast(problem);
				}
				return;
			}
			region = this.jobRegionInts;
		}
		int radius = options.has("radius") ? options.get("radius").getAsInt() : this.config.radius;
		int targets = options.has("targets") ? options.get("targets").getAsInt() : (kind == TaskKind.MINE_AND_DELIVER ? 3 : 1);
		String terrain = options.has("terrain") ? options.get("terrain").getAsString() : this.config.terrain;
		int obstacles = options.has("obstacles") ? options.get("obstacles").getAsInt() : this.config.obstacles;
		int size = options.has("size") ? options.get("size").getAsInt() : 5;
		boolean scan = "scan".equals(options.has("perception") ? options.get("perception").getAsString() : this.config.perception);
		int maxSteps = options.has("maxSteps") ? options.get("maxSteps").getAsInt() : defaultMaxSteps(kind, this.config.maxSteps);
		float successDist = options.has("successDist") ? options.get("successDist").getAsFloat() : this.config.successDist;
		this.pendingReset = new PendingReset(++this.resetRequestSeq, kind, seed, radius, obstacles, targets, maxSteps, successDist, session, replyId);
		this.readyPayload = null;
		this.autoResetIn = -1;
		if (this.task.active()) {
			this.task.end();
		}
		boolean hasOrigin = this.config.arenaX != null && this.config.arenaZ != null;
		ClientPlayNetworking.send(new ResetTaskPayload(
			this.pendingReset.requestId(), kind.id, terrain, seed, radius, obstacles, targets, hasOrigin, hasOrigin ? this.config.arenaX : 0,
			hasOrigin ? this.config.arenaZ : 0, region, subject, size, scan
		));
		this.log.info(
			"task.reset_requested",
			DroneLog.fields("task", kind.id, "terrain", terrain, "seed", seed, "radius", radius, "obstacles", obstacles, "targets", targets, "maxSteps", maxSteps)
		);
	}

	private int[] jobRegionInts = new int[0];

	// the schematic for a build job, the blocks for a mine job (a list, or names separated by commas), the crop for a harvest job
	private static String jobSubject(JsonObject options) {
		if (options.has("blocks") && options.get("blocks").isJsonArray()) {
			List<String> names = new ArrayList<>();
			options.getAsJsonArray("blocks").forEach(e -> names.add(e.getAsString()));
			return String.join(",", names);
		}
		for (String key : new String[] {"schematic", "blocks", "block", "crop"}) {
			if (options.has(key)) {
				return options.get(key).getAsString();
			}
		}
		return "";
	}

	/**
	 * Fills jobRegionInts from the options ("source" as 6 ints and "dest" as 3), falling back to the tablet selection.
	 * Returns why the job can't start, or null
	 */
	private @Nullable String jobRegion(TaskKind kind, JsonObject options, String subject) {
		if (kind == TaskKind.RETURN_HOME) {
			// the server knows the drone's station
			this.jobRegionInts = new int[9];
			return null;
		}
		Selection sel = new Selection();
		sel.cornerA = this.selection.cornerA;
		sel.cornerB = this.selection.cornerB;
		sel.dest = this.selection.dest;
		// a mine job's box: "region" as 6 numbers or a saved region's name, else the selection's corners
		if (options.has("region") && options.get("region").isJsonPrimitive()) {
			String name = options.get("region").getAsString();
			BlockPos[] box = this.namedRegion(name);
			if (box == null) {
				return "no saved region named " + name;
			}
			sel.cornerA = box[0];
			sel.cornerB = box[1];
		}
		if (options.has("region") && options.get("region").isJsonArray()) {
			options.add("source", options.get("region"));
		}
		if (options.has("source") && options.get("source").isJsonArray()) {
			JsonArray src = options.getAsJsonArray("source");
			if (src.size() != 6) {
				return "source needs 6 numbers, x0 y0 z0 x1 y1 z1";
			}
			sel.cornerA = new BlockPos(src.get(0).getAsInt(), src.get(1).getAsInt(), src.get(2).getAsInt());
			sel.cornerB = new BlockPos(src.get(3).getAsInt(), src.get(4).getAsInt(), src.get(5).getAsInt());
		}
		if (options.has("dest") && options.get("dest").isJsonArray()) {
			JsonArray d = options.getAsJsonArray("dest");
			if (d.size() != 3) {
				return "dest needs 3 numbers, x y z";
			}
			sel.dest = new BlockPos(d.get(0).getAsInt(), d.get(1).getAsInt(), d.get(2).getAsInt());
		}
		// a saved region as the destination, the build's min corner lands on the region's
		if (options.has("dest") && options.get("dest").isJsonPrimitive()) {
			BlockPos[] box = this.namedRegion(options.get("dest").getAsString());
			if (box == null) {
				return "no saved region named " + options.get("dest").getAsString();
			}
			sel.dest = BlockPos.min(box[0], box[1]);
		}
		// the box a copy or build mines its materials from: a saved region's name or 6 numbers
		int[] gather = new int[0];
		if (options.has("gather")) {
			if (kind != TaskKind.COPY_REGION && kind != TaskKind.BUILD_SCHEMATIC) {
				return "only copy and build jobs gather their materials";
			}
			JsonElement g = options.get("gather");
			BlockPos[] box = g.isJsonPrimitive() ? this.namedRegion(g.getAsString()) : null;
			if (g.isJsonArray() && g.getAsJsonArray().size() == 6) {
				JsonArray a = g.getAsJsonArray();
				box = new BlockPos[] {new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()), new BlockPos(a.get(3).getAsInt(), a.get(4).getAsInt(), a.get(5).getAsInt())};
			}
			if (box == null) {
				return "gather needs a saved region's name or 6 numbers, x0 y0 z0 x1 y1 z1";
			}
			gather = new int[] {box[0].getX(), box[0].getY(), box[0].getZ(), box[1].getX(), box[1].getY(), box[1].getZ()};
		}
		if (kind == TaskKind.MINE_REGION || kind == TaskKind.HARVEST_REGION) {
			if (subject.isEmpty()) {
				return kind == TaskKind.MINE_REGION ? "mine_region needs blocks, such as coal_ore, iron_ore" : "harvest_region needs a crop, such as minecraft:wheat";
			}
			if (sel.size() == null) {
				return "select the region to mine, or name a saved one";
			}
			this.jobRegionInts = new int[] {
				sel.cornerA.getX(), sel.cornerA.getY(), sel.cornerA.getZ(), sel.cornerB.getX(), sel.cornerB.getY(), sel.cornerB.getZ(), 0, 0, 0
			};
			return null;
		}
		if (kind == TaskKind.BUILD_SCHEMATIC) {
			if (subject.isEmpty()) {
				return "build_schematic needs a schematic file name";
			}
			if (sel.dest == null) {
				return "set the paste point with sneak and right click";
			}
			this.jobRegionInts = withGather(new int[] {0, 0, 0, 0, 0, 0, sel.dest.getX(), sel.dest.getY(), sel.dest.getZ()}, gather);
			return null;
		}
		String problem = sel.problem();
		if (problem == null) {
			this.jobRegionInts = withGather(sel.region(), gather);
		}
		return problem;
	}

	private static int[] withGather(int[] region, int[] gather) {
		int[] out = java.util.Arrays.copyOf(region, region.length + gather.length);
		System.arraycopy(gather, 0, out, region.length, gather.length);
		return out;
	}

	/** A saved region's corners by name, ignoring case, or null */
	private BlockPos @Nullable [] namedRegion(String name) {
		for (JsonElement e : this.regions) {
			JsonObject r = e.getAsJsonObject();
			if (r.get("name").getAsString().equalsIgnoreCase(name)) {
				JsonArray box = r.getAsJsonArray("box");
				return new BlockPos[] {new BlockPos(box.get(0).getAsInt(), box.get(1).getAsInt(), box.get(2).getAsInt()), new BlockPos(box.get(3).getAsInt(), box.get(4).getAsInt(), box.get(5).getAsInt())};
			}
		}
		return null;
	}

	// tool tasks take more steps: flying, mining, and container work
	private static int defaultMaxSteps(TaskKind kind, int navigateSteps) {
		return switch (kind) {
			case NAVIGATE_TO -> navigateSteps;
			case DIG_BLOCK, PLACE_BLOCK -> 400;
			case CHEST_TRANSFER -> 600;
			case MINE_AND_DELIVER, REPLICATE_BUILD -> 900;
			case HARVEST_CROPS -> 1500;
			case COPY_BUILD, SCHEMATIC_BUILD, MINE_DEPOSIT, GATHER_BUILD -> 20000;
			// a job runs until it's done or stopped, sized for the largest 16x16x16 builds
			case COPY_REGION, BUILD_SCHEMATIC, MINE_REGION, HARVEST_REGION, RETURN_HOME -> 100000;
		};
	}

	public void onTaskReady(TaskReadyPayload payload) {
		PendingReset pending = this.pendingReset;
		if (pending == null || pending.requestId() != payload.requestId()) {
			return;
		}
		if (!payload.error().isEmpty()) {
			this.failReset(payload.error());
			return;
		}
		if (this.config.arenaX == null || this.config.arenaZ == null) {
			this.config.arenaX = payload.origin().getX();
			this.config.arenaZ = payload.origin().getZ();
			this.config.save();
		}
		this.readyPayload = payload;
		this.readyWaitTicks = 0;
		this.settleTicks = -1;
	}

	private void failReset(String error) {
		PendingReset pending = this.pendingReset;
		this.pendingReset = null;
		this.readyPayload = null;
		this.log.warn("task.reset_failed", DroneLog.fields("error", error));
		if (pending != null && pending.session() != null) {
			pending.session().error("reset failed: " + error, pending.replyId());
		}
	}

	private void advanceReset() {
		PendingReset pending = this.pendingReset;
		TaskReadyPayload ready = this.readyPayload;
		if (pending == null || ready == null) {
			return;
		}
		if (this.settleTicks < 0) {
			if (!(this.mc.level.getEntity(ready.droneId()) instanceof DroneEntity drone)) {
				if (++this.readyWaitTicks > 100) {
					this.failReset("drone entity never reached the client");
				}
				return;
			}
			this.controller.adopt(drone);
			this.controller.setPose(ready.x(), ready.y(), ready.z(), ready.yaw(), 0.0F);
			this.controller.setPiloting(true);
			this.keyboard.reset();
			this.controller.sendPose();
			this.settleTicks = this.config.resetSettleTicks;
			return;
		}
		// block updates from the rebuild need a few ticks to reach the client and re-mesh
		if (this.settleTicks-- > 0) {
			return;
		}
		this.pendingReset = null;
		this.readyPayload = null;

		TaskKind kind = pending.kind();
		String id = kind.id + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + String.format("%04d", ++this.episodeCounter);
		JsonObject arena = JsonParser.parseString(ready.arena()).getAsJsonObject();
		this.task.begin(
			id, kind, pending.seed(), ready.marker(), arena, pending.maxSteps(), pending.successDist(), this.config.collisionPenalty,
			this.config.boundsPadding, this.config.outOfBoundsPenalty
		);
		this.tools.reset();
		this.selectedSlot = 0;
		this.queuedTools.clear();
		this.log.setEpisode(id);

		JsonObject meta = new JsonObject();
		meta.addProperty("id", id);
		meta.addProperty("task", kind.id);
		meta.addProperty("seed", pending.seed());
		JsonObject options = new JsonObject();
		options.addProperty("radius", pending.radius());
		options.addProperty("obstacles", pending.obstacles());
		options.addProperty("targets", pending.targets());
		options.addProperty("terrain", arena.has("terrain") ? arena.get("terrain").getAsString() : "flat");
		options.addProperty("maxSteps", pending.maxSteps());
		options.addProperty("successDist", pending.successDist());
		meta.add("options", options);
		meta.addProperty("width", this.config.width);
		meta.addProperty("height", this.config.height);
		meta.add("streams", Config.tree(this.config.streams));
		meta.addProperty("depthMax", this.config.depthMax);
		meta.addProperty("schema", SCHEMA);
		meta.addProperty("modVersion", FabricLoader.getInstance().getModContainer("mcdrone").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev"));
		meta.addProperty("pilot", pending.session() != null ? "bridge" : "keyboard");
		meta.addProperty("startedAt", java.time.Instant.now().toString());
		meta.add("marker", Json.pos(ready.marker()));
		meta.add("arena", arena);
		this.recorder.beginEpisode(kind.id, id, meta);
		this.log.info("task.reset", DroneLog.fields("seed", pending.seed(), "marker", Json.pos(ready.marker()), "recording", this.recorder.recording()));
		this.broadcastStatus();

		Session session = pending.session();
		Integer replyId = pending.replyId();
		// one idle tool tick fetches the fresh inventory and metrics before the first frame
		this.afterSync(this.sendTool(ToolRequest.IDLE), () -> this.requestCapture(obs -> this.broadcastObs(obs, session, replyId)));
	}

	// ---- bridge messages ----

	public void handleMessage(Session session, JsonObject msg) {
		String type = msg.has("type") ? msg.get("type").getAsString() : "";
		Integer id = msg.has("id") && msg.get("id").isJsonPrimitive() && msg.get("id").getAsJsonPrimitive().isNumber() ? msg.get("id").getAsInt() : null;
		if (!session.greeted && !type.equals("hello") && !type.equals("ping")) {
			session.error("send hello first", id);
			return;
		}
		boolean isController = session == this.controllerSession;
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
				pong.addProperty("tick", this.tick);
				session.send(pong);
			}
			case "memory" -> {
				// the controlling brain's voxel memory, passed through to the dashboards that show it
				if (!isController) {
					session.error("memory updates come from the controller", id);
					return;
				}
				for (Session s : this.bridge.sessions()) {
					if (s.greeted && s != session) {
						s.send(msg);
					}
				}
			}
			case "select" -> {
				// the copy selection is the player's, any client may edit it
				this.selection.merge(msg);
				this.broadcastStatus();
			}
			case "region_save" -> {
				String problem = this.saveRegion(msg.has("name") ? msg.get("name").getAsString() : "", msg.has("purpose") ? msg.get("purpose").getAsString() : "general");
				if (problem != null) {
					session.error(problem, id);
				}
			}
			case "region_delete" -> this.removeRegion(msg.get("region").getAsString());
			case "region_use" -> {
				if (!this.useRegion(msg.get("region").getAsString())) {
					session.error("no region with that id", id);
				}
			}
			case "export" -> {
				String name = msg.has("name") ? msg.get("name").getAsString() : "";
				if (this.selection.size() == null || !ClientPlayNetworking.canSend(ExportSchematicPayload.TYPE)) {
					session.error("select both source corners in a world first", id);
					return;
				}
				ClientPlayNetworking.send(new ExportSchematicPayload(this.selection.cornerA, this.selection.cornerB, name.endsWith(".schem") ? name : name + ".schem"));
			}
			case "rename" -> {
				if (!ClientPlayNetworking.canSend(RenameDronePayload.TYPE)) {
					session.error("not in a world", id);
					return;
				}
				ClientPlayNetworking.send(new RenameDronePayload(msg.has("name") ? msg.get("name").getAsString() : ""));
			}
			case "configure", "act", "step", "reset", "record", "pilot", "release" -> {
				if (!isController) {
					session.error(type + " needs the controller role", id);
					return;
				}
				this.controllerMessage(session, type, msg, id);
			}
			default -> session.error("unknown message type '" + type + "'", id);
		}
	}

	private void hello(Session session, JsonObject msg) {
		int schema = msg.has("schema") ? msg.get("schema").getAsInt() : -1;
		if (schema != SCHEMA) {
			session.error("schema " + schema + " is not supported, the mod speaks " + SCHEMA, null);
			session.channel.close();
			return;
		}
		session.client = msg.has("client") ? msg.get("client").getAsString() : "unknown";
		session.greeted = true;
		String role = msg.has("role") ? msg.get("role").getAsString() : "observer";
		if (role.equals("controller")) {
			if (this.controllerSession == null || this.controllerSession == session || !this.controllerSession.channel.isActive()) {
				this.controllerSession = session;
				this.bridgeAction = DroneAction.ZERO;
			} else {
				session.error("another client is the controller, connected as observer", null);
			}
		}
		JsonObject welcome = new JsonObject();
		welcome.addProperty("type", "welcome");
		welcome.addProperty("schema", SCHEMA);
		welcome.addProperty("role", session == this.controllerSession ? "controller" : "observer");
		welcome.addProperty("session", this.log.session());
		welcome.add("config", this.config.toJson());
		welcome.add("status", this.statusJson());
		welcome.add("maskIds", this.maskIds());
		welcome.add("itemIds", this.itemIds());
		session.send(welcome);
		this.log.info("bridge.hello", DroneLog.fields("session", session.id, "client", session.client, "role", welcome.get("role").getAsString()));
		this.broadcastStatus();
	}

	private void controllerMessage(Session session, String type, JsonObject msg, @Nullable Integer id) {
		switch (type) {
			case "configure" -> {
				JsonObject patch = msg.deepCopy();
				patch.remove("type");
				patch.remove("id");
				if (patch.has("mode")) {
					String mode = patch.remove("mode").getAsString();
					this.setMode(mode.equals("lockstep") ? Mode.LOCKSTEP : Mode.REALTIME);
				}
				if (!patch.isEmpty()) {
					this.config.merge(patch);
				}
				this.log.info("bridge.configure", msg);
				this.broadcastStatus();
			}
			case "act" -> {
				if (this.mode != Mode.REALTIME) {
					session.error("act is for realtime mode, use step in lockstep", id);
					return;
				}
				this.bridgeAction = DroneAction.fromJson(msg.getAsJsonObject("action"), this.config.maxLookPerTick, DroneEntity.INVENTORY_SIZE);
			}
			case "step" -> this.step(session, msg, id);
			case "reset" -> {
				long seed = msg.has("seed") && !msg.get("seed").isJsonNull() ? msg.get("seed").getAsLong() : this.seeds.nextLong() & 0xFFFFFFFFL;
				JsonObject options = msg.has("options") && msg.get("options").isJsonObject() ? msg.getAsJsonObject("options") : new JsonObject();
				this.startReset(seed, options, session, id);
			}
			case "record" -> {
				this.recorder.setArmed(msg.get("on").getAsBoolean());
				this.broadcastStatus();
			}
			case "pilot" -> {
				boolean on = msg.get("on").getAsBoolean();
				if (!this.controller.setPiloting(on)) {
					session.error("no drone to pilot, send reset first", id);
				}
				this.broadcastStatus();
			}
			case "release" -> this.releaseController("released");
			default -> {
			}
		}
	}

	private void step(Session session, JsonObject msg, @Nullable Integer id) {
		if (this.mode != Mode.LOCKSTEP) {
			session.error("step is for lockstep mode, send configure with mode lockstep first", id);
			return;
		}
		if (this.pendingReset != null) {
			session.error("a reset is in progress", id);
			return;
		}
		if (this.controller.drone() == null) {
			session.error("no drone, send reset first", id);
			return;
		}
		if (this.task.episodeId() != null && !this.task.active()) {
			session.error("episode is done, send reset", id);
			return;
		}
		int ticks = msg.has("ticks") ? Math.clamp(msg.get("ticks").getAsInt(), 1, 100) : 1;
		JsonObject actionJson = msg.has("action") ? msg.getAsJsonObject("action") : new JsonObject();
		DroneAction action = DroneAction.fromJson(actionJson, this.config.maxLookPerTick, DroneEntity.INVENTORY_SIZE);
		int seq = this.applyAction(action, ticks);
		this.checkFinished();
		this.afterSync(seq, () -> {
			long pause = this.tools.hasActionEvents() ? this.config.actionPauseMs : 0;
			this.runAfter(pause, () -> this.requestCapture(obs -> this.broadcastObs(obs, session, id)));
		});
	}

	public void setMode(Mode mode) {
		if (this.mode == mode) {
			return;
		}
		this.mode = mode;
		this.bridgeAction = DroneAction.ZERO;
		if (ClientPlayNetworking.canSend(SetFrozenPayload.TYPE)) {
			ClientPlayNetworking.send(new SetFrozenPayload(mode == Mode.LOCKSTEP));
		}
		this.log.info("mode.change", DroneLog.fields("mode", mode.name().toLowerCase()));
	}

	public void onDisconnect(Session session) {
		this.log.info("bridge.disconnect", DroneLog.fields("session", session.id, "client", session.client));
		if (session == this.controllerSession) {
			this.releaseController("disconnected");
		}
		this.broadcastStatus();
	}

	private void releaseController(String reason) {
		this.controllerSession = null;
		this.bridgeAction = DroneAction.ZERO;
		// never leave the world frozen without someone to step it
		this.setMode(Mode.REALTIME);
		this.log.info("bridge.controller_released", DroneLog.fields("reason", reason));
		this.broadcastStatus();
	}

	public JsonObject updateConfig(JsonObject patch) {
		this.config.merge(patch);
		this.log.info("config.update", patch);
		this.broadcastStatus();
		return this.config.toJson();
	}

	// ---- status and state ----

	public JsonObject statusJson() {
		JsonObject json = new JsonObject();
		json.addProperty("type", "status");
		json.addProperty("mode", this.mode.name().toLowerCase());
		json.addProperty("controller", this.controllerSession == null ? null : this.controllerSession.client);
		json.addProperty("observers", this.observerCount());
		json.addProperty("recording", this.recorder.recording());
		json.addProperty("recordArmed", this.recorder.armed());
		json.addProperty("piloting", this.controller.piloting());
		json.addProperty("droneId", this.controller.droneId());
		json.addProperty("inWorld", this.mc.level != null);
		json.addProperty("task", this.task.episodeId() == null ? this.config.task : this.task.kind().id);
		json.add("episode", this.task.episodeId() == null ? JsonNull.INSTANCE : this.task.snapshot());
		json.add("selection", this.selection.toJson());
		json.add("regions", this.regions);
		this.lastDrones = this.dronesJson();
		json.add("drones", this.lastDrones);
		return json;
	}

	/** The player's loaded drones with their battery and job queue, for the dashboard */
	private JsonArray dronesJson() {
		JsonArray out = new JsonArray();
		if (this.mc.level == null || this.mc.player == null) {
			return out;
		}
		for (Entity e : this.mc.level.entitiesForRendering()) {
			if (e instanceof DroneEntity drone && drone.isOwnedBy(this.mc.player)) {
				JsonObject json = new JsonObject();
				json.addProperty("id", drone.getId());
				json.addProperty("name", drone.shownName());
				json.addProperty("tier", drone.tier().id);
				json.addProperty("active", drone.getId() == this.controller.droneId());
				JsonArray pos = new JsonArray();
				pos.add(Math.round(drone.getX() * 10) / 10.0);
				pos.add(Math.round(drone.getY() * 10) / 10.0);
				pos.add(Math.round(drone.getZ() * 10) / 10.0);
				json.add("pos", pos);
				json.addProperty("charge", Math.round(drone.charge() * 1000) / 1000.0);
				json.addProperty("battery", BatteryConfig.get().enabled);
				json.add("home", Json.pos(drone.home()));
				json.addProperty("docked", drone.docked());
				json.add("queue", drone.queue());
				out.add(json);
			}
		}
		return out;
	}

	public void broadcastStatus() {
		JsonObject status = this.statusJson();
		for (Session s : this.bridge.sessions()) {
			if (s.greeted) {
				s.send(status);
			}
		}
	}

	public JsonObject stateJson() {
		JsonObject json = new JsonObject();
		DroneEntity drone = this.controller.drone();
		if (drone == null) {
			return json;
		}
		json.add("pos", vec(drone.position()));
		json.add("vel", vec(this.controller.velocity()));
		json.addProperty("tier", drone.tier().id);
		json.add("battery", drone.batteryJson());
		json.addProperty("yaw", drone.getYRot());
		json.addProperty("pitch", drone.getXRot());
		Vec3 eye = drone.getEyePosition();
		Vec3 end = eye.add(drone.getLookAngle().scale(32.0));
		BlockHitResult hit = this.mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, drone));
		if (hit.getType() == HitResult.Type.BLOCK) {
			JsonObject looking = new JsonObject();
			looking.addProperty("block", BuiltInRegistries.BLOCK.getKey(this.mc.level.getBlockState(hit.getBlockPos()).getBlock()).toString());
			looking.add("pos", Json.pos(hit.getBlockPos()));
			looking.addProperty("face", hit.getDirection().getName());
			looking.addProperty("dist", hit.getLocation().distanceTo(eye));
			json.add("lookingAt", looking);
		} else {
			json.add("lookingAt", JsonNull.INSTANCE);
		}
		json.addProperty("collided", this.controller.lastCollided());
		// lets scripts project world points into the frame, same frustum math as Raycaster
		RenderTarget frame = this.mc.gameRenderer.mainRenderTarget();
		JsonObject camera = new JsonObject();
		camera.addProperty("fov", this.mc.gameRenderer.mainCamera().getFov());
		camera.addProperty("windowAspect", (float) frame.width / frame.height);
		camera.addProperty("eyeHeight", drone.getEyeHeight());
		json.add("camera", camera);
		json.add("marker", this.task.episodeId() == null ? JsonNull.INSTANCE : Json.pos(this.task.marker()));
		// privileged layout for scripted experts and the dashboard minimap, DroneEnv keeps it out of the policy's observation
		json.add("arena", this.task.episodeId() == null ? JsonNull.INSTANCE : this.task.arena());
		// the job (boxes and schematic name) is the drone's instruction, so policies may read it too
		JsonObject arena = this.task.episodeId() == null ? null : this.task.arena();
		json.add("job", arena != null && arena.has("job") ? arena.get("job") : JsonNull.INSTANCE);
		// the geofence is part of the task, so policies may read it, unlike the arena layout
		if (this.task.episodeId() != null) {
			JsonArray box = new JsonArray();
			for (double v : this.task.bounds()) {
				box.add(v);
			}
			json.add("bounds", box);
		} else {
			json.add("bounds", JsonNull.INSTANCE);
		}
		this.tools.addTo(json, this.mc.level, this.selectedSlot);
		json.add("events", this.tools.drainEvents());
		return json;
	}

	private JsonObject maskIds() {
		if (this.maskIds == null) {
			JsonObject json = new JsonObject();
			JsonArray blocks = new JsonArray();
			for (int i = 0; i < BuiltInRegistries.BLOCK.size(); i++) {
				blocks.add(BuiltInRegistries.BLOCK.getKey(BuiltInRegistries.BLOCK.byId(i)).toString());
			}
			JsonArray entities = new JsonArray();
			for (int i = 0; i < BuiltInRegistries.ENTITY_TYPE.size(); i++) {
				entities.add(BuiltInRegistries.ENTITY_TYPE.getKey(BuiltInRegistries.ENTITY_TYPE.byId(i)).toString());
			}
			json.add("blocks", blocks);
			json.addProperty("entityBase", Raycaster.ENTITY_BASE);
			json.add("entities", entities);
			this.maskIds = json;
		}
		return this.maskIds;
	}

	private JsonArray itemIds() {
		if (this.itemIds == null) {
			JsonArray items = new JsonArray();
			for (int i = 0; i < BuiltInRegistries.ITEM.size(); i++) {
				items.add(BuiltInRegistries.ITEM.getKey(BuiltInRegistries.ITEM.byId(i)).toString());
			}
			this.itemIds = items;
		}
		return this.itemIds;
	}

	private void forwardLog(JsonObject line) {
		JsonObject message = new JsonObject();
		message.addProperty("type", "log");
		message.add("entry", line);
		for (Session s : this.bridge.sessions()) {
			if (s.greeted && s.wantsLogs) {
				s.send(message);
			}
		}
	}

	private static JsonArray vec(Vec3 v) {
		JsonArray a = new JsonArray();
		a.add(v.x);
		a.add(v.y);
		a.add(v.z);
		return a;
	}
}
