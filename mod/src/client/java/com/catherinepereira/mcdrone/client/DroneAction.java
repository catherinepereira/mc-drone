package com.catherinepereira.mcdrone.client;

import com.catherinepereira.mcdrone.tool.DroneTool;
import com.catherinepereira.mcdrone.tool.ToolRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * forward, right, up are in [-1, 1] relative to the drone's yaw.
 * yaw and pitch are degrees per tick, positive pitch looks down. tools carries the tool, selected slot, block to place, and container transfer
 */
public record DroneAction(float forward, float right, float up, float yaw, float pitch, ToolRequest tools) {
	public static final DroneAction ZERO = new DroneAction(0, 0, 0, 0, 0, ToolRequest.IDLE);

	public DroneAction(float forward, float right, float up, float yaw, float pitch) {
		this(forward, right, up, yaw, pitch, ToolRequest.IDLE);
	}

	/** The same action with one-shot tool parts dropped, so a held realtime action doesn't place or transfer every tick */
	public DroneAction continued() {
		return new DroneAction(this.forward, this.right, this.up, this.yaw, this.pitch, this.tools.continued());
	}

	public static DroneAction fromJson(JsonObject json, float maxLook, int slots) {
		JsonArray move = json.has("move") && json.get("move").isJsonArray() ? json.getAsJsonArray("move") : new JsonArray();
		JsonArray look = json.has("look") && json.get("look").isJsonArray() ? json.getAsJsonArray("look") : new JsonArray();
		DroneTool tool = json.has("tool") && json.get("tool").isJsonPrimitive() ? DroneTool.parse(json.get("tool").getAsString()) : DroneTool.NONE;
		int slot = json.has("slot") && json.get("slot").isJsonPrimitive() ? Math.clamp(json.get("slot").getAsInt(), 0, slots - 1) : 0;
		String block = json.has("block") && json.get("block").isJsonPrimitive() ? json.get("block").getAsString() : "";
		int transfer = ToolRequest.NO_TRANSFER;
		int fromSlot = 0;
		int toSlot = -1;
		int count = 0;
		if (json.has("transfer") && json.get("transfer").isJsonObject()) {
			JsonObject t = json.getAsJsonObject("transfer");
			String from = t.has("from") ? t.get("from").getAsString() : "drone";
			transfer = from.equals("container") ? ToolRequest.CONTAINER_TO_DRONE : ToolRequest.DRONE_TO_CONTAINER;
			fromSlot = t.has("slot") ? t.get("slot").getAsInt() : 0;
			toSlot = t.has("toSlot") && !t.get("toSlot").isJsonNull() ? t.get("toSlot").getAsInt() : -1;
			count = t.has("count") && !t.get("count").isJsonNull() ? Math.max(0, t.get("count").getAsInt()) : 0;
		}
		return new DroneAction(
			clamp(component(move, 0), 1),
			clamp(component(move, 1), 1),
			clamp(component(move, 2), 1),
			clamp(component(look, 0), maxLook),
			clamp(component(look, 1), maxLook),
			new ToolRequest(tool, slot, transfer, fromSlot, toSlot, count, block, false)
		);
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		JsonArray move = new JsonArray();
		move.add(this.forward);
		move.add(this.right);
		move.add(this.up);
		JsonArray look = new JsonArray();
		look.add(this.yaw);
		look.add(this.pitch);
		json.add("move", move);
		json.add("look", look);
		json.addProperty("tool", this.tools.tool().name().toLowerCase());
		json.addProperty("slot", this.tools.slot());
		if (!this.tools.block().isEmpty()) {
			json.addProperty("block", this.tools.block());
		}
		if (this.tools.transfer() == ToolRequest.NO_TRANSFER) {
			json.add("transfer", JsonNull.INSTANCE);
		} else {
			JsonObject t = new JsonObject();
			t.addProperty("from", this.tools.transfer() == ToolRequest.CONTAINER_TO_DRONE ? "container" : "drone");
			t.addProperty("slot", this.tools.fromSlot());
			t.addProperty("toSlot", this.tools.toSlot());
			t.addProperty("count", this.tools.count());
			json.add("transfer", t);
		}
		return json;
	}

	private static float component(JsonArray array, int i) {
		if (i >= array.size() || array.get(i).isJsonNull()) {
			return 0;
		}
		float value = array.get(i).getAsFloat();
		return Float.isFinite(value) ? value : 0;
	}

	private static float clamp(float value, float limit) {
		return Math.clamp(value, -limit, limit);
	}
}
