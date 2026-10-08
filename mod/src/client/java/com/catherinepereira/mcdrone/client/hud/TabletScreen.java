package com.catherinepereira.mcdrone.client.hud;

import com.catherinepereira.mcdrone.client.ClientRuntime;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.task.TaskKind;
import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * The tablet's home screen: every loaded drone the player owns, with its tier, charge, home station, and the job it's on,
 * then the active drone's job queue. Clicking a drone makes it the active one, the buttons below set up its jobs, run its
 * queue, or fly it, and start every drone's queue at once
 */
public final class TabletScreen extends Screen {
	private static final int W = 300;
	private static final int ROW = 24;
	private static final int MAX_ROWS = 6;
	private static final int MAX_QUEUE_ROWS = 5;
	private static final int BG = 0xF2F6F8FC;
	private static final int BORDER = 0xFFDDE3EC;
	private static final int TEXT = 0xFF1F2933;
	private static final int MUTED = 0xFF6B7785;

	private final ClientRuntime runtime;
	private final List<DroneEntity> drones = new ArrayList<>();
	private JsonArray queue = new JsonArray();
	private @Nullable String message;
	private int left;
	private int top;
	private int queueTop;
	private int bottom;

	public TabletScreen(ClientRuntime runtime) {
		super(Component.literal("Tablet"));
		this.runtime = runtime;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		// the queue syncs from the server, redraw when it changes
		DroneEntity active = this.runtime.controller().drone();
		if (active != null && !active.queue().equals(this.queue)) {
			this.rebuildWidgets();
		}
	}

	@Override
	protected void init() {
		this.drones.clear();
		if (this.minecraft.level != null && this.minecraft.player != null) {
			for (Entity e : this.minecraft.level.entitiesForRendering()) {
				if (e instanceof DroneEntity drone && drone.isOwnedBy(this.minecraft.player)) {
					this.drones.add(drone);
				}
			}
			this.drones.sort(Comparator.comparingDouble(d -> d.distanceToSqr(this.minecraft.player)));
		}
		DroneEntity active = this.runtime.controller().drone();
		this.queue = active == null ? new JsonArray() : active.queue();
		int rows = Math.max(1, Math.min(MAX_ROWS, this.drones.size()));
		int queueRows = Math.max(1, Math.min(MAX_QUEUE_ROWS, this.queue.size()));
		this.left = (this.width - W) / 2;
		this.top = Math.max(10, (this.height - (rows + queueRows + 6) * ROW) / 2);
		int y = this.top + 22;
		for (DroneEntity drone : this.drones.subList(0, Math.min(MAX_ROWS, this.drones.size()))) {
			this.addRenderableWidget(Button.builder(Component.literal(this.describe(drone, drone == active)), b -> {
				this.runtime.selectDrone(drone);
				this.message = null;
				this.rebuildWidgets();
			}).bounds(this.left, y, W, 20).build());
			y += ROW;
		}
		if (this.drones.isEmpty()) {
			y += ROW;
		}

		this.queueTop = y + 4;
		y = this.queueTop + 14;
		for (int i = 0; i < Math.min(MAX_QUEUE_ROWS, this.queue.size()); i++) {
			int index = i;
			this.addRenderableWidget(Button.builder(Component.literal("x"), b -> this.runtime.removeQueued(index)).bounds(this.left + W - 20, y, 20, 20).build());
			y += ROW;
		}
		if (this.queue.isEmpty()) {
			y += ROW;
		}

		y += 6;
		int third = (W - 8) / 3;
		Button jobs = Button.builder(Component.literal("Set up jobs"), b -> this.runtime.openJobs()).bounds(this.left, y, third, 20).build();
		Button run = Button.builder(Component.literal("Run queue"), b -> this.act(this.runtime.runQueue())).bounds(this.left + third + 4, y, third, 20).build();
		Button clear = Button.builder(Component.literal("Clear queue"), b -> this.runtime.removeQueued(-1)).bounds(this.left + 2 * (third + 4), y, third, 20).build();
		y += ROW;
		Button home = Button.builder(Component.literal("Send home"), b -> this.act(this.runtime.sendHome())).bounds(this.left, y, third, 20).build();
		Button pilot = Button.builder(Component.literal(this.runtime.controller().piloting() ? "Stop piloting" : "Pilot"), b -> {
			this.runtime.togglePiloting();
			this.onClose();
		}).bounds(this.left + third + 4, y, third, 20).build();
		jobs.active = active != null;
		run.active = !this.queue.isEmpty();
		clear.active = !this.queue.isEmpty();
		home.active = active != null && active.home() != null;
		pilot.active = active != null;
		for (Button b : new Button[] {jobs, run, clear, home, pilot}) {
			this.addRenderableWidget(b);
		}
		this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose()).bounds(this.left + 2 * (third + 4), y, third, 20).build());
		y += ROW;
		Button all = Button.builder(Component.literal("Run every drone's queue"), b -> {
			int started = this.runtime.runAllQueues();
			this.message = started == 0 ? "No idle drone has a queued job" : started + (started == 1 ? " drone started" : " drones started");
			this.rebuildWidgets();
		}).bounds(this.left, y, W, 20).build();
		all.active = this.drones.stream().anyMatch(d -> !d.queue().isEmpty());
		this.addRenderableWidget(all);
		this.bottom = y + ROW;
	}

	// closes on success, a problem stays on screen
	private void act(@Nullable String problem) {
		if (problem == null) {
			this.onClose();
		} else {
			this.message = problem;
		}
	}

	// "> Harvester, iron, 82%, home 10 64 -3, mine_region, 2 queued" with the arrow on the active drone
	private String describe(DroneEntity drone, boolean active) {
		String home = drone.home() == null ? "no station" : "home " + drone.home().toShortString();
		TaskKind job = this.runtime.jobOf(drone);
		int queued = drone.queue().size();
		return (active ? "> " : "") + drone.shownName() + ", " + drone.tier().id + ", " + Math.round(drone.charge() * 100) + "%, " + home
			+ (job != null ? ", " + job.id : "") + (queued > 0 ? ", " + queued + " queued" : "");
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a);
		int h = this.bottom - this.top + 18;
		g.fill(this.left - 10, this.top - 8, this.left + W + 10, this.top + h, BG);
		g.outline(this.left - 10, this.top - 8, W + 20, h + 8, BORDER);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(this.font, "Your drones", this.left, this.top + 4, TEXT, false);
		if (this.drones.isEmpty()) {
			g.text(this.font, "No drones nearby, place one from its item", this.left, this.top + 28, MUTED, false);
		}
		g.text(this.font, "Job queue", this.left, this.queueTop, TEXT, false);
		int y = this.queueTop + 14;
		for (int i = 0; i < Math.min(MAX_QUEUE_ROWS, this.queue.size()); i++) {
			var job = this.queue.get(i).getAsJsonObject();
			String label = job.has("label") ? job.get("label").getAsString() : job.get("task").getAsString();
			g.text(this.font, (i + 1) + ". " + label, this.left, y + 6, TEXT, false);
			y += ROW;
		}
		if (this.queue.size() > MAX_QUEUE_ROWS) {
			g.text(this.font, "and " + (this.queue.size() - MAX_QUEUE_ROWS) + " more", this.left + 160, this.queueTop, MUTED, false);
		}
		if (this.queue.isEmpty()) {
			g.text(this.font, "Empty, add jobs from Set up jobs", this.left, y + 6, MUTED, false);
		}
		String hint = this.message != null ? this.message : "Right click a charging station to set home";
		g.text(this.font, hint, this.left, this.bottom + 2, this.message != null ? TEXT : MUTED, false);
	}
}
