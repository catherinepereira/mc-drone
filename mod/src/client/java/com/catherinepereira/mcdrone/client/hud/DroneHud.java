package com.catherinepereira.mcdrone.client.hud;

import com.catherinepereira.mcdrone.client.ClientRuntime;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** F8 status panel, same palette as the dashboard */
public final class DroneHud implements HudElement {
	private static final int BG = 0xEBF6F8FC;
	private static final int BORDER = 0xFFDDE3EC;
	private static final int TEXT = 0xFF1F2933;
	private static final int MUTED = 0xFF6B7785;
	private static final int ACCENT = 0xFF3D7BD9;
	private static final int REC = 0xFFD94F4F;
	private static final int OK = 0xFF2E9E63;
	private static final int PAD = 6;
	private static final int LINE = 10;

	private final ClientRuntime runtime;

	public DroneHud(ClientRuntime runtime) {
		this.runtime = runtime;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!this.runtime.hudVisible() || mc.level == null) {
			return;
		}
		Font font = mc.font;
		List<Row> rows = new ArrayList<>();
		DroneEntity drone = this.runtime.controller().drone();
		rows.add(new Row("mode", this.runtime.mode().name().toLowerCase() + (this.runtime.controller().piloting() ? ", piloting" : ""), TEXT));
		if (drone != null) {
			rows.add(new Row("pos", String.format("%.1f %.1f %.1f", drone.getX(), drone.getY(), drone.getZ()), TEXT));
			var v = this.runtime.controller().velocity();
			rows.add(new Row("vel", String.format("%.2f %.2f %.2f", v.x, v.y, v.z), TEXT));
			rows.add(new Row("look", String.format("yaw %.0f  pitch %.0f", drone.getYRot(), drone.getXRot()), TEXT));
			var held = this.runtime.tools().inventoryStack(this.runtime.selectedSlot());
			String slotText = "slot " + this.runtime.selectedSlot() + ": " + (held.isEmpty() ? "empty" : held.getCount() + " " + held.getHoverName().getString());
			rows.add(new Row("tool", slotText, TEXT));
			float progress = this.runtime.tools().breakProgress();
			if (progress > 0) {
				rows.add(new Row("mining", String.format("%.0f%%", progress * 100), ACCENT));
			}
			if (this.runtime.tools().containerOpen()) {
				rows.add(new Row("container", "open, R shows it", ACCENT));
			}
		} else {
			rows.add(new Row("drone", "none, press N or place one", MUTED));
		}
		rows.add(new Row("bridge", (this.runtime.hasController() ? "controller connected" : "no controller") + ", " + this.runtime.observerCount() + " observers", this.runtime.hasController() ? OK : MUTED));
		JsonObject m = this.runtime.metrics().last();
		if (m.has("sps")) {
			rows.add(new Row("perf", String.format("%d sps  %d fps  %.1f ms capture", m.get("sps").getAsInt(), m.get("fps").getAsInt(), m.get("captureMs").getAsDouble()), TEXT));
		}
		if (this.runtime.task().episodeId() != null) {
			JsonObject ep = this.runtime.task().snapshot();
			String outcome = ep.get("success").getAsBoolean() ? "success" : ep.get("truncated").getAsBoolean() ? "timed out" : "running";
			rows.add(new Row(ep.get("task").getAsString(), String.format("step %d  dist %.1f  %s", ep.get("step").getAsInt(), ep.get("distance").getAsDouble(), outcome), ep.get("success").getAsBoolean() ? OK : TEXT));
			rows.add(new Row("reward", String.format("%.2f", ep.get("totalReward").getAsDouble()), TEXT));
		}
		if (this.runtime.recorder().recording()) {
			rows.add(new Row("rec", "recording episode", REC));
		} else if (this.runtime.recorder().armed()) {
			rows.add(new Row("rec", "armed, starts next episode", REC));
		}

		int labelW = 0;
		int valueW = 0;
		for (Row row : rows) {
			labelW = Math.max(labelW, font.width(row.label));
			valueW = Math.max(valueW, font.width(row.value));
		}
		int x = 6;
		int y = 6;
		int w = PAD * 2 + labelW + 8 + valueW;
		int h = PAD * 2 + LINE + 4 + rows.size() * LINE;
		g.fill(x, y, x + w, y + h, BG);
		g.outline(x, y, w, h, BORDER);
		g.fill(x, y, x + 2, y + h, ACCENT);
		g.text(font, "MC Drone", x + PAD, y + PAD, ACCENT, false);
		int ry = y + PAD + LINE + 4;
		for (Row row : rows) {
			g.text(font, row.label, x + PAD, ry, MUTED, false);
			g.text(font, row.value, x + PAD + labelW + 8, ry, row.color, false);
			ry += LINE;
		}
	}

	private record Row(String label, String value, int color) {
	}
}
