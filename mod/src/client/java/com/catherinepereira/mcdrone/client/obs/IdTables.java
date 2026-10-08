package com.catherinepereira.mcdrone.client.obs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jspecify.annotations.Nullable;

/** Names by raw id for the mask stream and the inventory, sent once in the bridge's welcome */
public final class IdTables {
	private static @Nullable JsonObject maskIds;
	private static @Nullable JsonArray itemIds;

	private IdTables() {
	}

	/** blocks[i] is mask id i + 1, entities[j] is id entityBase + j */
	public static JsonObject maskIds() {
		if (maskIds == null) {
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
			maskIds = json;
		}
		return maskIds;
	}

	public static JsonArray itemIds() {
		if (itemIds == null) {
			JsonArray items = new JsonArray();
			for (int i = 0; i < BuiltInRegistries.ITEM.size(); i++) {
				items.add(BuiltInRegistries.ITEM.getKey(BuiltInRegistries.ITEM.byId(i)).toString());
			}
			itemIds = items;
		}
		return itemIds;
	}
}
