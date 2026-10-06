package com.catherinepereira.mcdrone.client;

import com.catherinepereira.mcdrone.Json;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.net.DroneSyncPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Client copy of the drone's inventory, open container, and mining state from the latest DroneSyncPayload */
public final class ToolState {
	private int[] inventory = new int[DroneEntity.INVENTORY_SIZE * 2];
	private @Nullable BlockPos containerPos;
	private int[] container = new int[0];
	private @Nullable BlockPos breakingPos;
	private float breakProgress;
	private JsonArray events = new JsonArray();
	private int lastSeq = -1;

	public void reset() {
		this.inventory = new int[DroneEntity.INVENTORY_SIZE * 2];
		this.containerPos = null;
		this.container = new int[0];
		this.breakingPos = null;
		this.breakProgress = 0;
		this.events = new JsonArray();
	}

	public int lastSeq() {
		return this.lastSeq;
	}

	public void update(DroneSyncPayload sync) {
		this.lastSeq = sync.seq();
		this.inventory = sync.inventory();
		this.containerPos = sync.containerOpen() ? sync.containerPos() : null;
		this.container = sync.container();
		this.breakingPos = sync.breaking() ? sync.breakingPos() : null;
		this.breakProgress = sync.breakProgress();
		for (JsonElement e : JsonParser.parseString(sync.events()).getAsJsonArray()) {
			this.events.add(e);
		}
	}

	public boolean containerOpen() {
		return this.containerPos != null;
	}

	public int containerSize() {
		return this.container.length / 2;
	}

	public ItemStack inventoryStack(int slot) {
		return stack(this.inventory, slot);
	}

	public ItemStack containerStack(int slot) {
		return stack(this.container, slot);
	}

	public float breakProgress() {
		return this.breakingPos == null ? 0 : this.breakProgress;
	}

	/** Whether the events not yet drained include a block or item changing hands, the moments worth a pause */
	public boolean hasActionEvents() {
		for (JsonElement e : this.events) {
			String type = e.getAsJsonObject().get("type").getAsString();
			if (type.equals("break") || type.equals("place") || type.equals("open") || type.equals("close") || type.equals("transfer")) {
				return true;
			}
		}
		return false;
	}

	/** Events since the last call, so each observation carries the events that happened since the previous one */
	public JsonArray drainEvents() {
		JsonArray out = this.events;
		this.events = new JsonArray();
		return out;
	}

	public void addTo(JsonObject state, ClientLevel level, int selectedSlot) {
		state.add("inventory", slots(this.inventory));
		state.addProperty("selectedSlot", selectedSlot);
		if (this.containerPos != null) {
			JsonObject c = new JsonObject();
			c.add("pos", Json.pos(this.containerPos));
			c.addProperty("block", BuiltInRegistries.BLOCK.getKey(level.getBlockState(this.containerPos).getBlock()).toString());
			c.add("slots", slots(this.container));
			state.add("container", c);
		} else {
			state.add("container", JsonNull.INSTANCE);
		}
		if (this.breakingPos != null) {
			JsonObject b = new JsonObject();
			b.add("pos", Json.pos(this.breakingPos));
			b.addProperty("progress", this.breakProgress);
			state.add("breaking", b);
		} else {
			state.add("breaking", JsonNull.INSTANCE);
		}
	}

	private static ItemStack stack(int[] flat, int slot) {
		if (slot * 2 + 1 >= flat.length || flat[slot * 2 + 1] <= 0) {
			return ItemStack.EMPTY;
		}
		return new ItemStack(BuiltInRegistries.ITEM.byId(flat[slot * 2]), flat[slot * 2 + 1]);
	}

	private static JsonArray slots(int[] flat) {
		JsonArray out = new JsonArray();
		for (int i = 0; i + 1 < flat.length; i += 2) {
			JsonArray slot = new JsonArray();
			slot.add(flat[i + 1] > 0 ? BuiltInRegistries.ITEM.getKey(BuiltInRegistries.ITEM.byId(flat[i])).toString() : "minecraft:air");
			slot.add(flat[i + 1]);
			out.add(slot);
		}
		return out;
	}
}
