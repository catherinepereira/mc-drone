package com.catherinepereira.mcdrone.client.obs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;

/** Names by raw id for the mask stream and the inventory, sent once in the bridge's welcome */
public final class IdTables {
	private static @Nullable JsonObject maskIds;
	private static @Nullable JsonArray itemIds;

	private IdTables() {
	}

	/** blocks[i] is mask id i + 1, entities[j] is id entityBase + j, categories[j] is that entity's mob category */
	public static JsonObject maskIds() {
		if (maskIds == null) {
			JsonObject json = new JsonObject();
			JsonArray blocks = new JsonArray();
			for (int i = 0; i < BuiltInRegistries.BLOCK.size(); i++) {
				blocks.add(BuiltInRegistries.BLOCK.getKey(BuiltInRegistries.BLOCK.byId(i)).toString());
			}
			JsonArray entities = new JsonArray();
			JsonArray categories = new JsonArray();
			for (int i = 0; i < BuiltInRegistries.ENTITY_TYPE.size(); i++) {
				EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.byId(i);
				entities.add(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
				categories.add(type.getCategory().getSerializedName());
			}
			json.add("blocks", blocks);
			json.addProperty("entityBase", Raycaster.ENTITY_BASE);
			json.add("entities", entities);
			json.add("categories", categories);
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
