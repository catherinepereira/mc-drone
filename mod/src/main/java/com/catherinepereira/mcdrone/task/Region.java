package com.catherinepereira.mcdrone.task;

import com.catherinepereira.mcdrone.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

/**
 * A named box the player designated. safe boxes are never broken or built in by a drone, mine and farm boxes are
 * where those jobs work, general is any other named box, such as a copy source or a build site
 */
public record Region(String id, String name, Purpose purpose, BlockPos min, BlockPos max) {
	public enum Purpose {
		GENERAL,
		SAFE,
		MINE,
		FARM;

		public String id() {
			return this.name().toLowerCase();
		}

		public static Purpose parse(String id) {
			for (Purpose p : values()) {
				if (p.id().equals(id)) {
					return p;
				}
			}
			throw new IllegalArgumentException("unknown region purpose '" + id + "', expected general, safe, mine, or farm");
		}
	}

	public static Region of(String id, String name, Purpose purpose, BlockPos a, BlockPos b) {
		BlockPos min = BlockPos.min(a, b);
		BlockPos max = BlockPos.max(a, b);
		return new Region(id, name, purpose, min, max);
	}

	public boolean contains(BlockPos p) {
		return DroneJob.inside(p, this.min, this.max);
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("id", this.id);
		json.addProperty("name", this.name);
		json.addProperty("purpose", this.purpose.id());
		json.add("box", Json.box(this.min, this.max));
		return json;
	}

	public static Region fromJson(JsonObject json) {
		JsonArray box = json.getAsJsonArray("box");
		return of(
			json.get("id").getAsString(), json.get("name").getAsString(), Purpose.parse(json.get("purpose").getAsString()),
			new BlockPos(box.get(0).getAsInt(), box.get(1).getAsInt(), box.get(2).getAsInt()), new BlockPos(box.get(3).getAsInt(), box.get(4).getAsInt(), box.get(5).getAsInt())
		);
	}
}
