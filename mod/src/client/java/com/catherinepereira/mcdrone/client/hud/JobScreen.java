package com.catherinepereira.mcdrone.client.hud;

import com.catherinepereira.mcdrone.client.ClientRuntime;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Pick a job for the drone: an action, the regions it works with, and what it needs (a block, a schematic).
 * A copy takes three: the region to copy, where to paste it, and optionally a region to mine its materials from first.
 * Each is the tablet selection or any saved region. The lower row saves the selection as a named region
 */
public final class JobScreen extends Screen {
	private static final int W = 300;
	private static final int ROW = 24;
	private static final int BG = 0xF2F6F8FC;
	private static final int BORDER = 0xFFDDE3EC;
	private static final int TEXT = 0xFF1F2933;
	private static final int MUTED = 0xFF6B7785;
	// the tablet selection in the region pickers, and no gather region
	private static final String SELECTION = "";
	private static final String NONE = "";

	public enum Action {
		COPY("Copy a region", "copy_region"),
		BUILD("Build a schematic", "build_schematic"),
		MINE("Mine blocks in a region", "mine_region"),
		HARVEST("Harvest and replant crops", "harvest_region");

		final String label;
		final String task;

		Action(String label, String task) {
			this.label = label;
			this.task = task;
		}
	}

	private final ClientRuntime runtime;
	private Action action = Action.COPY;
	private String region = SELECTION;
	private String dest = SELECTION;
	private String gather = NONE;
	private String subject = "";
	private String regionName = "";
	private String purpose = "general";
	private @Nullable String message;
	private int left;
	private int top;
	// widget rows above the save row, counted as init adds them
	private int rows;

	public JobScreen(ClientRuntime runtime) {
		super(Component.literal("Drone jobs"));
		this.runtime = runtime;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		this.left = (this.width - W) / 2;
		this.top = Math.max(10, (this.height - ROW * 11) / 2);
		int y = this.top + 22;
		this.rows = 0;

		this.addRenderableWidget(
			CycleButton.<Action>builder(a -> Component.literal(a.label), this.action)
				.withValues(List.of(Action.values()))
				.create(this.left, y, W, 20, Component.literal("Action"), (button, value) -> {
					this.action = value;
					this.rebuildWidgets();
				})
		);
		y = this.nextRow(y);

		List<String> saved = new ArrayList<>();
		for (JsonElement e : this.runtime.regions()) {
			saved.add(e.getAsJsonObject().get("name").getAsString());
		}
		List<String> withSelection = new ArrayList<>(saved);
		withSelection.addFirst(SELECTION);

		if (this.action != Action.BUILD) {
			this.region = withSelection.contains(this.region) ? this.region : SELECTION;
			String label = switch (this.action) {
				case COPY -> "Copy from";
				case MINE -> "Mine in";
				default -> "Farm";
			};
			this.addRenderableWidget(
				CycleButton.<String>builder(this::selectionLabel, this.region)
					.withValues(withSelection)
					.create(this.left, y, W, 20, Component.literal(label), (button, value) -> this.region = value)
			);
			y = this.nextRow(y);
		}

		if (this.action == Action.COPY || this.action == Action.BUILD) {
			this.dest = withSelection.contains(this.dest) ? this.dest : SELECTION;
			this.addRenderableWidget(
				CycleButton.<String>builder(this::destLabel, this.dest)
					.withValues(withSelection)
					.create(this.left, y, W, 20, Component.literal("Paste at"), (button, value) -> this.dest = value)
			);
			y = this.nextRow(y);
			List<String> gathers = new ArrayList<>(saved);
			gathers.addFirst(NONE);
			this.gather = gathers.contains(this.gather) ? this.gather : NONE;
			this.addRenderableWidget(
				CycleButton.<String>builder(this::gatherLabel, this.gather)
					.withValues(gathers)
					.create(this.left, y, W, 20, Component.literal("Materials"), (button, value) -> this.gather = value)
			);
			y = this.nextRow(y);
		}

		if (this.action != Action.COPY) {
			EditBox box = new EditBox(this.font, this.left, y, W, 20, Component.literal("subject"));
			box.setMaxLength(128);
			box.setHint(Component.literal(switch (this.action) {
				case BUILD -> "schematic file, such as house.schem";
				case HARVEST -> "crop, such as wheat";
				default -> "blocks, such as coal_ore, iron_ore";
			}));
			box.setValue(this.subject);
			box.setResponder(v -> this.subject = v);
			this.addRenderableWidget(box);
			y = this.nextRow(y);
		}

		int third = (W - 8) / 3;
		this.addRenderableWidget(Button.builder(Component.literal("Start job"), b -> this.start(false)).bounds(this.left, y, third, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Add to queue"), b -> this.start(true)).bounds(this.left + third + 4, y, third, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose()).bounds(this.left + 2 * (third + 4), y, third, 20).build());
		y = this.nextRow(y) + 22;

		EditBox name = new EditBox(this.font, this.left, y, 140, 20, Component.literal("region name"));
		name.setMaxLength(40);
		name.setHint(Component.literal("region name"));
		name.setValue(this.regionName);
		name.setResponder(v -> this.regionName = v);
		this.addRenderableWidget(name);
		this.addRenderableWidget(
			CycleButton.<String>builder(p -> Component.literal(p), this.purpose)
				.withValues(List.of("general", "safe", "mine", "farm"))
				.displayOnlyValue()
				.create(this.left + 144, y, 76, 20, Component.literal("Purpose"), (button, value) -> this.purpose = value)
		);
		this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> this.saveRegion()).bounds(this.left + 224, y, 76, 20).build());
	}

	private int nextRow(int y) {
		this.rows++;
		return y + ROW;
	}

	private Component selectionLabel(String name) {
		if (!name.equals(SELECTION)) {
			return Component.literal(this.savedLabel(name));
		}
		BlockPos size = this.runtime.selection.size();
		return Component.literal(size == null ? "tablet selection (none yet)" : "tablet selection, " + size.getX() + "x" + size.getY() + "x" + size.getZ());
	}

	private Component destLabel(String name) {
		if (!name.equals(SELECTION)) {
			return Component.literal(this.savedLabel(name) + ", its low corner");
		}
		BlockPos dest = this.runtime.selection.dest;
		return Component.literal(dest == null ? "tablet paste point (none yet)" : "tablet paste point " + dest.toShortString());
	}

	private Component gatherLabel(String name) {
		return Component.literal(name.equals(NONE) ? "carried by the drone" : "mined from " + this.savedLabel(name));
	}

	// a saved region by name with its purpose and size, such as "quarry (mine, 8x4x8)"
	private String savedLabel(String name) {
		for (JsonElement e : this.runtime.regions()) {
			JsonObject r = e.getAsJsonObject();
			if (r.get("name").getAsString().equals(name)) {
				JsonArray b = r.getAsJsonArray("box");
				int sx = Math.abs(b.get(3).getAsInt() - b.get(0).getAsInt()) + 1;
				int sy = Math.abs(b.get(4).getAsInt() - b.get(1).getAsInt()) + 1;
				int sz = Math.abs(b.get(5).getAsInt() - b.get(2).getAsInt()) + 1;
				return name + " (" + r.get("purpose").getAsString() + ", " + sx + "x" + sy + "x" + sz + ")";
			}
		}
		return name;
	}

	private void start(boolean queue) {
		JsonObject options = new JsonObject();
		options.addProperty("task", this.action.task);
		if (this.action != Action.BUILD && !this.region.equals(SELECTION)) {
			options.addProperty("region", this.region);
		}
		if (this.action == Action.COPY || this.action == Action.BUILD) {
			if (!this.dest.equals(SELECTION)) {
				options.addProperty("dest", this.dest);
			}
			if (!this.gather.equals(NONE)) {
				options.addProperty("gather", this.gather);
			}
		}
		if (this.action == Action.BUILD) {
			options.addProperty("schematic", this.subject.endsWith(".schem") ? this.subject.trim() : this.subject.trim() + ".schem");
		} else if (this.action != Action.COPY) {
			String name = this.subject.trim();
			if (this.action == Action.MINE) {
				// names without a namespace are minecraft's, the server reads the list
				options.addProperty("blocks", name);
			} else {
				options.addProperty("crop", name.contains(":") ? name : "minecraft:" + name);
			}
		}
		String problem = queue ? this.runtime.queueJob(options) : this.runtime.startJob(options);
		if (problem != null) {
			this.message = problem;
		} else if (queue) {
			this.message = "Queued, it runs after the current job or from Run queue";
		} else {
			this.onClose();
		}
	}

	private void saveRegion() {
		String problem = this.runtime.saveRegion(this.regionName.trim(), this.purpose);
		this.message = problem != null ? problem : "Saved " + this.purpose + " region " + this.regionName.trim();
		if (problem == null) {
			this.regionName = "";
			this.rebuildWidgets();
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a);
		int h = 22 + this.rows * ROW + 6 + ROW + 18 + 18;
		g.fill(this.left - 10, this.top - 8, this.left + W + 10, this.top + h, BG);
		g.outline(this.left - 10, this.top - 8, W + 20, h + 8, BORDER);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(this.font, "Drone jobs", this.left, this.top + 4, TEXT, false);
		int y = this.top + 22 + this.rows * ROW + 6;
		g.text(this.font, "Save the tablet selection as a region", this.left, y, MUTED, false);
		String hint = this.message != null ? this.message : this.hint();
		g.text(this.font, hint, this.left, y + ROW + 18, this.message != null ? TEXT : MUTED, false);
	}

	private String hint() {
		return switch (this.action) {
			case COPY -> this.gather.equals(NONE) ? "Uses the drone's own blocks, leaves the source" : "Mines its blocks first, leaves the source";
			case BUILD -> this.gather.equals(NONE) ? "Uses the drone's own blocks" : "Mines its blocks first, then builds";
			case MINE -> "Mines every block of those kinds, only inside the region";
			case HARVEST -> "Ripe crops come out, and every empty farmland cell gets replanted";
		};
	}
}
