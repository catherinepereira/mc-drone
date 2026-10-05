package com.catherinepereira.mcdrone.client.hud;

import com.catherinepereira.mcdrone.client.ClientRuntime;
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
 * Pick a job for the drone: an action, the region it works in, and what it needs (a block, a schematic, the paste point).
 * Regions are the current remote selection or any saved region. The lower row saves the selection as a named region
 */
public final class JobScreen extends Screen {
	private static final int W = 300;
	private static final int ROW = 24;
	private static final int BG = 0xF2F6F8FC;
	private static final int BORDER = 0xFFDDE3EC;
	private static final int TEXT = 0xFF1F2933;
	private static final int MUTED = 0xFF6B7785;
	private static final String SELECTION = "";

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
	private String subject = "";
	private String regionName = "";
	private String purpose = "general";
	private @Nullable String message;
	private int left;
	private int top;

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
		this.top = Math.max(10, (this.height - ROW * 9) / 2);
		int y = this.top + 22;

		this.addRenderableWidget(
			CycleButton.<Action>builder(a -> Component.literal(a.label), this.action)
				.withValues(List.of(Action.values()))
				.create(this.left, y, W, 20, Component.literal("Action"), (button, value) -> {
					this.action = value;
					this.rebuildWidgets();
				})
		);
		y += ROW;

		if (this.action != Action.BUILD) {
			List<String> regions = new ArrayList<>();
			regions.add(SELECTION);
			for (JsonElement e : this.runtime.regions()) {
				regions.add(e.getAsJsonObject().get("name").getAsString());
			}
			if (!regions.contains(this.region)) {
				this.region = SELECTION;
			}
			String label = switch (this.action) {
				case COPY -> "Copy from";
				case MINE -> "Mine in";
				default -> "Farm";
			};
			this.addRenderableWidget(
				CycleButton.<String>builder(this::regionLabel, this.region)
					.withValues(regions)
					.create(this.left, y, W, 20, Component.literal(label), (button, value) -> this.region = value)
			);
			y += ROW;
		}

		if (this.action != Action.COPY) {
			EditBox box = new EditBox(this.font, this.left, y, W, 20, Component.literal("subject"));
			box.setMaxLength(128);
			box.setHint(Component.literal(switch (this.action) {
				case BUILD -> "schematic file, such as house.schem";
				case HARVEST -> "crop, such as wheat";
				default -> "block, such as minecraft:coal_ore";
			}));
			box.setValue(this.subject);
			box.setResponder(v -> this.subject = v);
			this.addRenderableWidget(box);
			y += ROW;
		}

		this.addRenderableWidget(Button.builder(Component.literal("Start job"), b -> this.start()).bounds(this.left, y, W / 2 - 2, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose()).bounds(this.left + W / 2 + 2, y, W / 2 - 2, 20).build());
		y += ROW + 22;

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

	private Component regionLabel(String name) {
		if (!name.equals(SELECTION)) {
			return Component.literal(name);
		}
		BlockPos size = this.runtime.selection.size();
		return Component.literal(size == null ? "remote selection (none yet)" : "remote selection, " + size.getX() + "x" + size.getY() + "x" + size.getZ());
	}

	private void start() {
		JsonObject options = new JsonObject();
		options.addProperty("task", this.action.task);
		if (this.action != Action.BUILD && !this.region.equals(SELECTION)) {
			options.addProperty("region", this.region);
		}
		if (this.action == Action.BUILD) {
			options.addProperty("schematic", this.subject.endsWith(".schem") ? this.subject.trim() : this.subject.trim() + ".schem");
		} else if (this.action != Action.COPY) {
			String name = this.subject.trim();
			options.addProperty(this.action == Action.MINE ? "block" : "crop", name.contains(":") ? name : "minecraft:" + name);
		}
		String problem = this.runtime.startJob(options);
		if (problem == null) {
			this.onClose();
		} else {
			this.message = problem;
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
		int h = 22 + this.rows() * ROW + 6 + ROW + 18 + 18;
		g.fill(this.left - 10, this.top - 8, this.left + W + 10, this.top + h, BG);
		g.outline(this.left - 10, this.top - 8, W + 20, h + 8, BORDER);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(this.font, "Drone jobs", this.left, this.top + 4, TEXT, false);
		int y = this.top + 22 + this.rows() * ROW + 6;
		g.text(this.font, "Save the remote selection as a region", this.left, y, MUTED, false);
		String hint = this.message != null ? this.message : this.hint();
		g.text(this.font, hint, this.left, y + ROW + 18, this.message != null ? TEXT : MUTED, false);
	}

	// widget rows above the region row: action, start, and the region and subject rows the action uses
	private int rows() {
		return 2 + (this.action != Action.BUILD ? 1 : 0) + (this.action != Action.COPY ? 1 : 0);
	}

	private String hint() {
		BlockPos dest = this.runtime.selection.dest;
		return switch (this.action) {
			case COPY, BUILD -> dest == null ? "Set the paste point with sneak and right click" : "Pastes at " + dest.toShortString();
			case MINE -> "The drone only breaks blocks inside the region";
			case HARVEST -> "Ripe crops come out, and every empty farmland cell gets replanted";
		};
	}
}
