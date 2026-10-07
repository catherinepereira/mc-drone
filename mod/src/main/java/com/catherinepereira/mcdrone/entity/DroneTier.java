package com.catherinepereira.mcdrone.entity;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

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

	/** Whether breaking state drops anything, the game's own tool tags decide */
	public boolean canHarvest(BlockState state) {
		return !state.requiresCorrectToolForDrops() || new ItemStack(this.pickaxe).isCorrectToolForDrops(state);
	}

	public boolean canHarvest(Block block) {
		return this.canHarvest(block.defaultBlockState());
	}

	/** Ids of every block this tier breaks without a drop, sorted. Read fresh each time, a datapack reload can change the tags */
	public List<String> unharvestable() {
		return BuiltInRegistries.BLOCK.stream()
			.filter(b -> !this.canHarvest(b))
			.map(b -> BuiltInRegistries.BLOCK.getKey(b).toString())
			.sorted()
			.toList();
	}

	/** The lowest tier that harvests block, null when no drone does, such as cobweb */
	public static @Nullable DroneTier lowestFor(Block block) {
		for (DroneTier tier : values()) {
			if (tier.canHarvest(block)) {
				return tier;
			}
		}
		return null;
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
