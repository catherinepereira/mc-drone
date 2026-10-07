package com.catherinepereira.mcdrone.entity;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** What a drone is built from. The tier's pickaxe sets how fast it breaks blocks and which ones drop, like a player's */
public enum DroneTier {
	COPPER("copper", Items.COPPER_PICKAXE),
	IRON("iron", Items.IRON_PICKAXE),
	DIAMOND("diamond", Items.DIAMOND_PICKAXE);

	public final String id;
	public final Item pickaxe;

	DroneTier(String id, Item pickaxe) {
		this.id = id;
		this.pickaxe = pickaxe;
	}

	public static DroneTier byOrdinal(int ordinal) {
		DroneTier[] values = values();
		return values[Math.clamp(ordinal, 0, values.length - 1)];
	}

	public static DroneTier parse(String id) {
		for (DroneTier tier : values()) {
			if (tier.id.equals(id)) {
				return tier;
			}
		}
		return COPPER;
	}
}
