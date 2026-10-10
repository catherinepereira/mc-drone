package com.catherinepereira.mcdrone.client;

import com.catherinepereira.mcdrone.Json;
import com.catherinepereira.mcdrone.ModContent;
import com.catherinepereira.mcdrone.TabletInput;
import com.catherinepereira.mcdrone.client.bridge.BridgeServer;
import com.catherinepereira.mcdrone.client.bridge.Session;
import com.catherinepereira.mcdrone.client.hud.DroneScreen;
import com.catherinepereira.mcdrone.client.hud.JobScreen;
import com.catherinepereira.mcdrone.client.hud.TabletScreen;
import com.catherinepereira.mcdrone.client.obs.CaptureService;
import com.catherinepereira.mcdrone.client.obs.Observation;
import com.catherinepereira.mcdrone.client.record.Recorder;
import com.catherinepereira.mcdrone.client.task.TaskScorer;
import com.catherinepereira.mcdrone.entity.BatteryConfig;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.net.DroneQueuePayload;
import com.catherinepereira.mcdrone.net.DroneSyncPayload;
import com.catherinepereira.mcdrone.net.DroneToolPayload;
import com.catherinepereira.mcdrone.net.RegionEditPayload;
import com.catherinepereira.mcdrone.net.SelectDronePayload;
import com.catherinepereira.mcdrone.net.SetFrozenPayload;
import com.catherinepereira.mcdrone.net.TaskEndPayload;
import com.catherinepereira.mcdrone.net.TaskReadyPayload;
import com.catherinepereira.mcdrone.task.TaskKind;
import com.catherinepereira.mcdrone.tool.DroneTool;
import com.catherinepereira.mcdrone.tool.ToolRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns every client subsystem and runs on the client thread.
 * Bridge messages arrive through onClientThread, ticks through tick(), frames through onFrameRendered().
 * Each working drone has a DroneRun, the active one is the drone the keyboard, the HUD, and the tablet use.
 * Resets are in ResetFlow, bridge messages in BridgeCommands, frame captures in CaptureService
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
	private final KeyboardPilot keyboard = new KeyboardPilot();
	private final Recorder recorder;
	private final BridgeServer bridge;
	private final BridgeCommands commands = new BridgeCommands(this);
	private final CaptureService capture;
	private final Random seeds = new Random();
	public final Selection selection = new Selection();
	private final Regions regions = new Regions();
	private final List<DroneRun> runs = new ArrayList<>();
	private final FleetResets fleets = new FleetResets();
	private DroneRun active;

	private Mode mode = Mode.REALTIME;
	// what the server was last told, see syncFrozen
	private boolean frozen;
	private boolean hudVisible = true;
	private long tick;
	private long lastMetricsMs;
	private int autoResetIn = -1;
	// the drones part of the last status, a change sends a new status
	private JsonArray lastDrones = new JsonArray();
	private int toolSeq;
	// work held back by a pause, as wall clock deadlines, run from tick()
	private final List<Map.Entry<Long, Runnable>> delayed = new ArrayList<>();
	// one-shot tool requests from the inventory screen, applied on the next keyboard tick
	private final Deque<ToolRequest> queuedTools = new ArrayDeque<>();

	public ClientRuntime(Minecraft mc) {
		this.mc = mc;
		Path gameDir = FabricLoader.getInstance().getGameDir();
		// dev runs send recordings to the training folder and logs to the mod's run folder, see build.gradle
		String data = System.getProperty("mcdrone.data");
		String logs = System.getProperty("mcdrone.logs");
		this.config = Config.load(FabricLoader.getInstance().getConfigDir().resolve("mcdrone.json"));
		String session = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		this.log = new DroneLog(logs != null ? Path.of(logs) : gameDir.resolve("mcdrone").resolve("logs"), session, this.config);
		this.recorder = new Recorder(data != null ? Path.of(data) : gameDir.resolve("mcdrone").resolve("data"), gameDir.resolve("mcdrone").resolve("test-recordings"), this.log);
		this.capture = new CaptureService(this, mc);
		this.active = this.newRun();
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

	/** The active drone's controller, the one the keyboard and the HUD use */
	public DroneController controller() {
		return this.active.controller;
	}

	public TaskScorer task() {
		return this.active.task;
	}

	public Mode mode() {
		return this.mode;
	}

	public ToolState tools() {
		return this.active.tools;
	}

	public CaptureService capture() {
		return this.capture;
	}

	public Regions regions() {
		return this.regions;
	}

	public long tickCount() {
		return this.tick;
	}

	ClientLevel level() {
		return this.mc.level;
	}

	FleetResets fleets() {
		return this.fleets;
	}

	public int selectedSlot() {
		return this.active.selectedSlot;
	}

	public void selectSlot(int slot) {
		this.active.selectedSlot = Math.clamp(slot, 0, DroneEntity.INVENTORY_SIZE - 1);
	}

	public KeyboardPilot keyboard() {
		return this.keyboard;
	}

	/** Queues a one-shot tool request from the inventory screen for the next keyboard tick */
	public void queueTool(ToolRequest request) {
		this.queuedTools.addLast(request);
	}

	void clearQueuedTools() {
		this.queuedTools.clear();
	}

	void cancelAutoReset() {
		this.autoResetIn = -1;
	}

	public Metrics metrics() {
		return this.metrics;
	}

	public boolean hudVisible() {
		return this.hudVisible;
	}

	/** Whether any bridge client controls a drone */
	public boolean hasController() {
		return this.runs.stream().anyMatch(r -> r.session != null);
	}

	public int observerCount() {
		return this.bridge.sessions().size() - (int) this.runs.stream().filter(r -> r.session != null).count();
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

	// ---- drone runs ----

	public DroneRun active() {
		return this.active;
	}

	private DroneRun newRun() {
		DroneRun run = new DroneRun(this, new DroneController(this.mc, this.config));
		this.runs.add(run);
		return run;
	}

	/** The run the session controls, or null */
	@Nullable DroneRun runOf(Session session) {
		for (DroneRun run : this.runs) {
			if (run.session == session) {
				return run;
			}
		}
		return null;
	}

	private @Nullable DroneRun runOf(int droneId) {
		for (DroneRun run : this.runs) {
			if (droneId >= 0 && run.droneId() == droneId) {
				return run;
			}
		}
		return null;
	}

	/** The drone's run, started if it has none */
	DroneRun runFor(DroneEntity drone) {
		DroneRun run = this.runOf(drone.getId());
		if (run == null) {
			run = this.active.droneId() < 0 ? this.active : this.newRun();
			run.controller.adopt(drone);
		}
		return run;
	}

	/** The job drone is working on, or null */
	public @Nullable TaskKind jobOf(DroneEntity drone) {
		DroneRun run = this.runOf(drone.getId());
		return run != null && run.task.active() && run.task.kind().isJob() ? run.task.kind() : null;
	}

	/** One of the player's loaded drones by entity id, or null */
	@Nullable DroneEntity ownedDrone(int entityId) {
		return this.mc.level != null && this.mc.player != null && this.mc.level.getEntity(entityId) instanceof DroneEntity drone && drone.isOwnedBy(this.mc.player)
			? drone
			: null;
	}

	// runs other than the active one end when their drone is gone or they have nothing to do, the server moves the drone again
	private void pruneRuns() {
		this.runs.removeIf(run -> {
			run.controller.validate();
			if (run == this.active || (run.busy() && run.drone() != null)) {
				return false;
			}
			run.controller.release();
			return true;
		});
	}

	/** Puts the camera back on the drone the player pilots, or on the player once nobody flies a drone */
	public void restoreCamera() {
		DroneEntity piloted = this.active.controller.piloting() ? this.active.drone() : null;
		Entity want = piloted != null ? piloted : this.hasController() ? null : this.mc.player;
		if (want != null && this.mc.getCameraEntity() != want) {
			this.mc.setCameraEntity(want);
		}
	}

	/** Moves the game camera into run's drone, making it the active one, or out of it */
	boolean pilot(DroneRun run, boolean on) {
		if (!on) {
			return run.controller.setPiloting(false);
		}
		DroneEntity drone = run.drone();
		if (drone == null) {
			return false;
		}
		this.selectDrone(drone);
		return this.active.controller.setPiloting(true);
	}

	// ---- keybind actions ----

	public void togglePiloting() {
		boolean on = !this.active.controller.piloting();
		if (on && this.active.drone() == null) {
			DroneEntity nearest = this.nearestFreeDrone();
			if (nearest != null) {
				this.selectDrone(nearest);
			}
		}
		if (!this.active.controller.setPiloting(on)) {
			this.toast("No drone nearby. Place one with the drone item or press N to start a task.");
			return;
		}
		this.keyboard.reset();
		this.log.info("pilot.toggle", DroneLog.fields("on", on));
	}

	// the nearest owned drone no bridge client or job already flies
	private @Nullable DroneEntity nearestFreeDrone() {
		if (this.mc.level == null || this.mc.player == null) {
			return null;
		}
		DroneEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (Entity e : this.mc.level.entitiesForRendering()) {
			if (e instanceof DroneEntity drone && drone.isOwnedBy(this.mc.player) && this.runOf(drone.getId()) == null) {
				double dist = drone.distanceToSqr(this.mc.player);
				if (dist < bestDist) {
					best = drone;
					bestDist = dist;
				}
			}
		}
		return best;
	}

	/** Opens the drone inventory screen, and the faced container with it when there is one in reach */
	public void openInventory() {
		if (!this.active.controller.piloting() || this.active.drone() == null) {
			this.toast("Pilot the drone first (V)");
			return;
		}
		this.queueTool(new ToolRequest(DroneTool.OPEN, this.active.selectedSlot, ToolRequest.NO_TRANSFER, 0, -1, 0));
		this.mc.gui.setScreen(new DroneScreen(this));
	}

	public void toggleRecording() {
		this.commands.playerToggledRecording();
		this.recorder.setArmed(!this.recorder.armed());
		this.toast(this.recorder.armed() ? "Recording armed, starts with the next episode" : "Recording stopped");
	}

	public void toggleHud() {
		this.hudVisible = !this.hudVisible;
	}

	public void newEpisodeFromKeyboard() {
		this.startFromKeyboard(new JsonObject());
	}

	public void startCopyFromKeyboard() {
		JsonObject options = new JsonObject();
		options.addProperty("task", TaskKind.COPY_REGION.id);
		this.startFromKeyboard(options);
	}

	private void startFromKeyboard(JsonObject options) {
		String problem = this.active.resets.start(this.randomSeed(), options, null, null);
		if (problem != null) {
			this.toast(problem);
		}
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

	public void openJobs() {
		this.mc.gui.setScreen(new JobScreen(this));
	}

	public void openTablet() {
		this.mc.gui.setScreen(new TabletScreen(this));
	}

	/** Makes drone the one jobs, piloting, and the bridge use, here and on the server */
	public void selectDrone(DroneEntity drone) {
		DroneRun previous = this.active;
		boolean piloting = previous.controller.piloting();
		DroneRun next = this.runOf(drone.getId());
		if (next == null) {
			next = previous.droneId() < 0 || !previous.busy() ? previous : this.newRun();
			if (next == previous && previous.droneId() >= 0) {
				previous.controller.release();
			}
			next.controller.adopt(drone);
		}
		if (next != previous && piloting) {
			previous.controller.setPiloting(false);
		}
		this.active = next;
		if (piloting) {
			next.controller.setPiloting(true);
		}
		if (ClientPlayNetworking.canSend(SelectDronePayload.TYPE)) {
			ClientPlayNetworking.send(new SelectDronePayload(drone.getId()));
		}
		this.broadcastStatus();
	}

	private void toast(String text) {
		if (this.mc.player != null) {
			this.mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(text));
		}
	}

	private long randomSeed() {
		return this.seeds.nextLong() & 0xFFFFFFFFL;
	}

	// ---- regions ----

	public void onRegions(String json) {
		this.regions.set(JsonParser.parseString(json).getAsJsonArray());
		this.broadcastStatus();
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
		edit.add("box", Json.box(this.selection.cornerA, this.selection.cornerB));
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
		JsonObject region = this.regions.byId(id);
		if (region == null) {
			return false;
		}
		BlockPos[] box = Json.readBox(region.getAsJsonArray("box"));
		this.selection.cornerA = box[0];
		this.selection.cornerB = box[1];
		this.broadcastStatus();
		return true;
	}

	private void sendRegionEdit(JsonObject edit) {
		if (ClientPlayNetworking.canSend(RegionEditPayload.TYPE)) {
			ClientPlayNetworking.send(new RegionEditPayload(edit.toString()));
		}
	}

	// ---- jobs and queues ----

	/** Starts a job for the active drone from the job screen, returning why it can't start or null */
	public @Nullable String startJob(JsonObject options) {
		return this.active.resets.start(this.randomSeed(), options, null, null);
	}

	/**
	 * Adds a job to the active drone's queue. The tablet selection is copied into the job now, so changing it later
	 * doesn't move queued work. Returns why the job can't be queued, or null
	 */
	public @Nullable String queueJob(JsonObject options) {
		return this.queueJob(this.active.drone(), options);
	}

	/** Adds a job to drone's queue, see queueJob(JsonObject) */
	@Nullable String queueJob(@Nullable DroneEntity drone, JsonObject options) {
		if (drone == null || !ClientPlayNetworking.canSend(DroneQueuePayload.TYPE)) {
			return "pick a drone on the tablet first";
		}
		TaskKind kind = TaskKind.parse(options.get("task").getAsString());
		String subject = JobOptions.subject(options);
		try {
			JobOptions.region(kind, options, subject, this.selection, this.regions);
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
		JsonObject job = options.deepCopy();
		job.addProperty("label", JobOptions.label(kind, options, subject));
		if (!job.has("region") && (kind == TaskKind.COPY_REGION || kind == TaskKind.MINE_REGION || kind == TaskKind.HARVEST_REGION
			|| kind == TaskKind.PATROL_REGION || kind == TaskKind.GUARD_REGION || kind == TaskKind.SEEK_BLOCK)) {
			job.add("source", Json.box(this.selection.cornerA, this.selection.cornerB));
		}
		if (!job.has("dest") && (kind == TaskKind.COPY_REGION || kind == TaskKind.BUILD_SCHEMATIC || kind == TaskKind.FLY_TO)) {
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
		DroneEntity drone = this.active.drone();
		if (drone != null) {
			removeQueued(drone, index);
		}
	}

	private static void removeQueued(DroneEntity drone, int index) {
		if (!ClientPlayNetworking.canSend(DroneQueuePayload.TYPE)) {
			return;
		}
		JsonObject edit = new JsonObject();
		edit.addProperty("op", index < 0 ? "clear" : "remove");
		edit.addProperty("index", index);
		ClientPlayNetworking.send(new DroneQueuePayload(drone.getId(), edit.toString()));
	}

	/** Starts the active drone's first queued job, returning why it can't or null */
	public @Nullable String runQueue() {
		return this.runQueue(this.active);
	}

	/**
	 * Starts the first queued job of every one of the player's loaded drones that isn't working, so they fly at once.
	 * Returns how many started
	 */
	public int runAllQueues() {
		if (this.mc.level == null || this.mc.player == null) {
			return 0;
		}
		int started = 0;
		for (Entity e : this.mc.level.entitiesForRendering()) {
			if (e instanceof DroneEntity drone && drone.isOwnedBy(this.mc.player) && !drone.queue().isEmpty()) {
				DroneRun run = this.runOf(drone.getId());
				if ((run == null || !run.busy()) && this.runQueue(this.runFor(drone)) == null) {
					started++;
				}
			}
		}
		return started;
	}

	private @Nullable String runQueue(DroneRun run) {
		DroneEntity drone = run.drone();
		JsonArray queue = drone == null ? new JsonArray() : drone.queue();
		if (queue.isEmpty()) {
			return "the queue is empty";
		}
		if (run.resets.active() || (run.task.active() && run.task.kind().isJob())) {
			return "a job is running, the queue continues after it";
		}
		String problem = run.resets.start(this.randomSeed(), queue.get(0).getAsJsonObject(), null, null);
		if (problem == null) {
			removeQueued(drone, 0);
		}
		return problem;
	}

	public @Nullable String sendHome() {
		return this.sendHome(this.active);
	}

	private @Nullable String sendHome(DroneRun run) {
		JsonObject options = new JsonObject();
		options.addProperty("task", TaskKind.RETURN_HOME.id);
		return run.resets.start(this.randomSeed(), options, null, null);
	}

	// after a job the drone's next queued one starts, and with nothing left, or after a failure, it flies home to charge.
	// Only while a bridge client flies the drone, the brain flies jobs
	private void advanceQueue(DroneRun run) {
		TaskKind finished = run.finishedJob;
		if (finished == null || run.resets.active()) {
			return;
		}
		run.finishedJob = null;
		DroneEntity drone = run.drone();
		if (run.session == null || drone == null || finished == TaskKind.RETURN_HOME) {
			return;
		}
		JsonArray queue = drone.queue();
		if (run.finishedJobSucceeded && !queue.isEmpty()) {
			String problem = this.runQueue(run);
			if (problem == null) {
				return;
			}
			this.toast(drone.shownName() + " skipped a queued job: " + problem);
		} else if (!queue.isEmpty()) {
			this.toast(drone.shownName() + "'s job stopped, " + queue.size() + " queued jobs wait, run them from the tablet");
		}
		if (drone.home() != null && !drone.docked()) {
			this.sendHome(run);
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
		if (this.tick % 10 == 0 && this.mc.level != null && this.mc.player != null) {
			boolean holdingTablet = this.mc.player.getMainHandItem().is(ModContent.TABLET);
			if (holdingTablet || this.runs.stream().anyMatch(r -> r.task.active() && r.task.kind().isJob())) {
				this.selection.outline(this.mc.level);
			}
			if (holdingTablet) {
				this.regions.outline(this.mc.level);
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
		this.pruneRuns();
		for (DroneRun run : List.copyOf(this.runs)) {
			run.resets.tick();
			this.advanceQueue(run);
		}

		if (this.autoResetIn > 0 && --this.autoResetIn == 0) {
			this.autoResetIn = -1;
			this.newEpisodeFromKeyboard();
		}

		if (this.mode != Mode.REALTIME || this.mc.isPaused()) {
			return;
		}
		boolean streamTick = this.tick % Math.max(1, 20 / this.config.streamHz) == 0;
		for (DroneRun run : List.copyOf(this.runs)) {
			if (run.resets.active() || run.drone() == null) {
				continue;
			}
			DroneAction action;
			if (run.session != null) {
				action = run.nextBridgeAction();
			} else if (run == this.active && run.controller.piloting()) {
				action = this.keyboardAction();
			} else {
				continue;
			}
			boolean wasDone = run.task.done();
			this.applyAction(run, action, 1);
			this.checkFinished(run);
			boolean finished = !wasDone && run.task.done();
			if (this.recorder.recording(run.droneId()) || finished || (run == this.active && streamTick && this.anyObserverWantsObs())) {
				this.capture.request(run, obs -> this.broadcastObs(run, obs, null, null));
			}
		}
	}

	private DroneAction keyboardAction() {
		DroneAction keys = this.keyboard.read(this.mc, this.config.maxLookPerTick, this.active.selectedSlot);
		this.active.selectedSlot = keys.tools().slot();
		ToolRequest queued = this.queuedTools.pollFirst();
		if (queued == null) {
			return keys;
		}
		return new DroneAction(keys.forward(), keys.right(), keys.up(), keys.yaw(), keys.pitch(), queued);
	}

	/** A lockstep step: the action for ticks ticks, then the frame once the server has applied its tool use */
	void stepAndCapture(DroneRun run, DroneAction action, int ticks, Session session, @Nullable Integer replyId) {
		int seq = this.applyAction(run, action, ticks);
		this.checkFinished(run);
		this.afterSync(run, seq, () -> {
			long pause = run.tools.hasActionEvents() ? this.config.actionPauseMs : 0;
			this.runAfter(pause, () -> this.capture.request(run, obs -> {
				run.terminalSent = !run.task.active();
				this.broadcastObs(run, obs, session, replyId);
			}));
		});
	}

	/** The end frame of an episode that finished after the last step's frame went out, as the answer to the next step */
	void sendTerminal(DroneRun run, Session session, @Nullable Integer replyId) {
		run.terminalSent = true;
		this.capture.request(run, obs -> this.broadcastObs(run, obs, session, replyId));
	}

	/**
	 * Simulates the action for ticks ticks, sending the pose and tool intent after each one.
	 * Returns the seq of the last tool packet, or -1 when nothing tool-related was sent
	 */
	private int applyAction(DroneRun run, DroneAction action, int ticks) {
		this.recorder.onAction(run.droneId(), action);
		run.task.beginAction();
		run.selectedSlot = Math.clamp(action.tools().slot(), 0, DroneEntity.INVENTORY_SIZE - 1);
		int lastSeq = -1;
		for (int i = 0; i < ticks; i++) {
			boolean collided = run.controller.simulate(action);
			run.task.turn(run.controller.yawRate(), run.controller.pitchRate());
			this.metrics.simStep();
			run.controller.sendPose();
			ToolRequest request = i == 0 ? action.tools() : action.tools().continued();
			// an idle tick still goes out while mining or beaming, so the server can cancel the crack or the beam
			DroneEntity beaming = run.drone();
			if (!request.idle() || run.tools.breakProgress() > 0 || (beaming != null && beaming.beamTarget() >= 0)) {
				lastSeq = this.sendTool(run, request);
			}
			DroneEntity drone = run.drone();
			if (drone != null) {
				run.task.update(drone.getBoundingBox().getCenter(), collided, run.controller.lastHitDrone(), this::isTargetBlock);
				run.task.health(drone.getHealth(), drone.wrecked());
				if (run.task.followTarget() >= 0 && this.mc.level != null) {
					Entity target = this.mc.level.getEntity(run.task.followTarget());
					run.task.follow(drone.getBoundingBox().getCenter(), target == null ? null : target.getBoundingBox().getCenter());
				}
				// docking is checked every step, flight steps don't wait on the server's metrics
				boolean docked = run.task.kind() == TaskKind.RETURN_HOME ? drone.docked() : run.task.kind() == TaskKind.DOCK_STATION && drone.dockedOn(run.task.dockStation());
				if (run.task.active() && docked) {
					run.task.complete();
				}
			}
			if (run.task.done()) {
				break;
			}
		}
		this.log.debug("action", DroneLog.fields("drone", run.droneId(), "action", action.toJson(), "ticks", ticks));
		return lastSeq;
	}

	int sendTool(DroneRun run, ToolRequest request) {
		DroneEntity drone = run.drone();
		if (drone == null || !ClientPlayNetworking.canSend(DroneToolPayload.TYPE)) {
			return -1;
		}
		// in realtime a held place or transfer would fire every tick, space them out
		boolean oneShot = !request.continued().equals(request) && request.tool() != DroneTool.BREAK;
		if (this.mode == Mode.REALTIME && oneShot) {
			long now = System.currentTimeMillis();
			if (now < run.oneShotReadyMs) {
				request = request.continued();
			} else {
				run.oneShotReadyMs = now + this.config.actionPauseMs;
			}
		}
		int seq = ++this.toolSeq;
		ClientPlayNetworking.send(new DroneToolPayload(drone.getId(), seq, request.withMaterials("unlimited".equals(this.config.materials))));
		return seq;
	}

	/** Runs then once the server has answered run's tool packet seq, or right away for -1 */
	void afterSync(DroneRun run, int seq, Runnable then) {
		if (seq < 0 || run.tools.lastSeq() >= seq) {
			then.run();
			return;
		}
		run.syncWaiters.computeIfAbsent(seq, k -> new ArrayList<>()).add(then);
	}

	public void onSync(DroneSyncPayload sync) {
		DroneRun run = this.runOf(sync.entityId());
		if (run == null || this.mc.level == null) {
			return;
		}
		for (int i = 0; i < sync.changedPositions().length; i++) {
			this.mc.level.setBlock(BlockPos.of(sync.changedPositions()[i]), Block.stateById(sync.changedStates()[i]), Block.UPDATE_ALL_IMMEDIATE);
		}
		if (sync.changedPositions().length > 0) {
			this.capture.blocksChanged();
		}
		run.tools.update(sync);
		for (var e : JsonParser.parseString(sync.events()).getAsJsonArray()) {
			String type = e.getAsJsonObject().get("type").getAsString();
			// a beam held with nothing to lock on fails every tick, keep those out of the info log
			if (type.equals("attack_failed")) {
				this.log.debug("tool." + type, e.getAsJsonObject());
			} else {
				this.log.info("tool." + type, e.getAsJsonObject());
			}
		}
		boolean wasDone = run.task.done();
		run.task.applyMetrics(sync.metrics());
		this.checkFinished(run);
		// realtime episodes finished by world progress still need their terminal frame
		if (!wasDone && run.task.done() && this.mode == Mode.REALTIME) {
			this.capture.request(run, obs -> this.broadcastObs(run, obs, null, null));
		}
		Map<Integer, List<Runnable>> ready = run.syncWaiters.headMap(sync.seq(), true);
		List<Runnable> runs = new ArrayList<>();
		ready.values().forEach(runs::addAll);
		ready.clear();
		runs.forEach(Runnable::run);
	}

	private boolean isTargetBlock(BlockPos pos) {
		return this.mc.level != null && this.mc.level.getBlockState(pos).is(Blocks.COAL_ORE);
	}

	private void checkFinished(DroneRun run) {
		if (run.task.active() && run.task.done()) {
			this.onEpisodeFinished(run);
		}
	}

	private void onEpisodeFinished(DroneRun run) {
		JsonObject snap = run.task.snapshot();
		this.log.info("task.done", snap);
		run.task.end();
		this.syncFrozen();
		if (run.task.kind().isJob()) {
			run.finishedJob = run.task.kind();
			run.finishedJobSucceeded = snap.has("success") && snap.get("success").getAsBoolean();
			jobEnded(run);
		}
		// keyboard demos chain episodes so a pilot can record many in a row, until one passes with no input
		if (run == this.active && run.session == null && this.recorder.armed()) {
			if (this.recorder.sawInput(run.droneId())) {
				this.autoResetIn = this.config.autoResetTicks;
			} else {
				this.recorder.setArmed(false);
				this.toast("Recording stopped, the last episode had no input");
			}
		}
	}

	/** Tells the server run's job is over, so its boxes are free for other drones */
	static void jobEnded(DroneRun run) {
		if (run.task.kind().isJob() && run.droneId() >= 0 && ClientPlayNetworking.canSend(TaskEndPayload.TYPE)) {
			ClientPlayNetworking.send(new TaskEndPayload(run.droneId()));
		}
	}

	// ---- frames, resets, and bridge messages ----

	/** Called from the level render END_MAIN event, before the GUI draws */
	public void onFrameRendered(Camera camera) {
		this.capture.onFrameRendered(camera);
	}

	public void onTaskReady(TaskReadyPayload payload) {
		for (DroneRun run : this.runs) {
			if (run.resets.awaits(payload)) {
				run.resets.onTaskReady(payload);
				return;
			}
		}
	}

	public void handleMessage(Session session, JsonObject msg) {
		this.commands.handle(session, msg);
	}

	public void onDisconnect(Session session) {
		this.commands.onDisconnect(session);
	}

	private boolean anyObserverWantsObs() {
		for (Session s : this.bridge.sessions()) {
			if (s.greeted && s.wantsObs) {
				return true;
			}
		}
		return false;
	}

	/** Answers replyTo with the frame, and streams it to observers when it's the active drone's */
	void broadcastObs(DroneRun run, Observation obs, @Nullable Session replyTo, @Nullable Integer replyId) {
		long t0 = System.nanoTime();
		byte[] observerBytes = null;
		if (run == this.active) {
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
		}
		if (replyTo != null) {
			replyTo.sendBinary(obs.encode(replyId), true);
		}
		this.metrics.encode((System.nanoTime() - t0) / 1e6);
	}

	/** Sends msg to every greeted session but from */
	void sendToOthers(Session from, JsonObject msg) {
		for (Session s : this.bridge.sessions()) {
			if (s.greeted && s != from) {
				s.send(msg);
			}
		}
	}

	/**
	 * Lockstep freezes the server so the world waits for the drones, except while a drone hunts or follows. Its target has
	 * to move, and the client only sees it move in a running world
	 */
	void syncFrozen() {
		boolean hunting = this.runs.stream().anyMatch(r -> r.task.active() && r.task.kind().needsLiveWorld());
		boolean frozen = this.mode == Mode.LOCKSTEP && !hunting;
		if (frozen != this.frozen && ClientPlayNetworking.canSend(SetFrozenPayload.TYPE)) {
			this.frozen = frozen;
			ClientPlayNetworking.send(new SetFrozenPayload(frozen));
		}
	}

	public void setMode(Mode mode) {
		if (this.mode == mode) {
			return;
		}
		this.mode = mode;
		this.runs.forEach(run -> run.setBridgeAction(DroneAction.ZERO));
		this.syncFrozen();
		this.log.info("mode.change", DroneLog.fields("mode", mode.name().toLowerCase()));
		if (mode == Mode.REALTIME) {
			this.restoreCamera();
		}
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
		Session controller = this.active.session;
		json.addProperty("type", "status");
		json.addProperty("mode", this.mode.name().toLowerCase());
		json.addProperty("controller", controller == null ? null : controller.client);
		json.addProperty("observers", this.observerCount());
		json.addProperty("recording", this.recorder.recording());
		json.addProperty("recordArmed", this.recorder.armed());
		json.addProperty("piloting", this.active.controller.piloting());
		json.addProperty("droneId", this.active.droneId());
		json.addProperty("inWorld", this.mc.level != null);
		TaskScorer task = this.active.task;
		json.addProperty("task", task.episodeId() == null ? this.config.task : task.kind().id);
		json.add("episode", task.episodeId() == null ? JsonNull.INSTANCE : task.snapshot());
		json.add("selection", this.selection.toJson());
		json.add("regions", this.regions.all());
		this.lastDrones = this.dronesJson();
		json.add("drones", this.lastDrones);
		return json;
	}

	/** The player's loaded drones with their battery, job queue, and the episode and controller of the ones working */
	private JsonArray dronesJson() {
		JsonArray out = new JsonArray();
		if (this.mc.level == null || this.mc.player == null) {
			return out;
		}
		for (Entity e : this.mc.level.entitiesForRendering()) {
			if (e instanceof DroneEntity drone && drone.isOwnedBy(this.mc.player)) {
				DroneRun run = this.runOf(drone.getId());
				JsonObject json = new JsonObject();
				json.addProperty("id", drone.getId());
				json.addProperty("name", drone.shownName());
				json.addProperty("tier", drone.tier().id);
				json.addProperty("active", run == this.active);
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
				json.addProperty("controller", run == null || run.session == null ? null : run.session.client);
				json.add("episode", run == null || run.task.episodeId() == null ? JsonNull.INSTANCE : episodeSummary(run.task));
				out.add(json);
			}
		}
		return out;
	}

	// what the drone list needs of an episode, the full snapshot changes every step
	private static JsonObject episodeSummary(TaskScorer task) {
		JsonObject snap = task.snapshot();
		JsonObject out = new JsonObject();
		for (String key : new String[] {"id", "task", "done", "success"}) {
			if (snap.has(key)) {
				out.add(key, snap.get(key));
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

	public JsonObject stateJson(DroneRun run) {
		return StateJson.of(this.mc, run.controller, run.task, run.tools, run.selectedSlot, this.config.sensorRange);
	}

	public DroneAction lastAction(DroneRun run) {
		return run.controller.lastAction();
	}

	/** Makes the next frame show run's drone where it is now instead of interpolating from the last tick */
	public void pinRenderPose(DroneRun run) {
		run.controller.pinRenderPose();
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
}
