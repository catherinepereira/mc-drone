package com.catherinepereira.mcdrone.client;

import com.catherinepereira.mcdrone.Json;
import com.catherinepereira.mcdrone.task.Region;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** The world's named regions as the server last sent them, each { "id", "name", "purpose", "box" } */
public final class Regions {
	private JsonArray all = new JsonArray();

	public void set(JsonArray regions) {
		this.all = regions;
	}

	public JsonArray all() {
		return this.all;
	}

	public List<String> names() {
		List<String> names = new ArrayList<>();
		for (JsonElement e : this.all) {
			names.add(e.getAsJsonObject().get("name").getAsString());
		}
		return names;
	}

	/** Ignores case, the server keeps names unique that way */
	public @Nullable JsonObject byName(String name) {
		return this.find("name", name);
	}

	public @Nullable JsonObject byId(String id) {
		return this.find("id", id);
	}

	/** A region's two corners by name, or null */
	public BlockPos @Nullable [] box(String name) {
		JsonObject region = this.byName(name);
		return region == null ? null : Json.readBox(region.getAsJsonArray("box"));
	}

	/** The name with its purpose and size, such as "quarry (mine, 8x4x8)" */
	public String label(String name) {
		JsonObject region = this.byName(name);
		BlockPos[] box = this.box(name);
		if (region == null || box == null) {
			return name;
		}
		BlockPos size = BlockPos.max(box[0], box[1]).subtract(BlockPos.min(box[0], box[1])).offset(1, 1, 1);
		return name + " (" + region.get("purpose").getAsString() + ", " + size.getX() + "x" + size.getY() + "x" + size.getZ() + ")";
	}

	/** Dust along every region's edges in its purpose's color */
	public void outline(ClientLevel level) {
		for (JsonElement e : this.all) {
			JsonObject region = e.getAsJsonObject();
			BlockPos[] box = Json.readBox(region.getAsJsonArray("box"));
			BlockPos min = BlockPos.min(box[0], box[1]);
			BlockPos size = BlockPos.max(box[0], box[1]).subtract(min).offset(1, 1, 1);
			Selection.edges(level, min, size, Region.Purpose.parse(region.get("purpose").getAsString()).color);
		}
	}

	private @Nullable JsonObject find(String key, String value) {
		for (JsonElement e : this.all) {
			JsonObject region = e.getAsJsonObject();
			if (region.get(key).getAsString().equalsIgnoreCase(value)) {
				return region;
			}
		}
		return null;
	}
}
