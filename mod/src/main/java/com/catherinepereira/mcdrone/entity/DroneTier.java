package com.catherinepereira.mcdrone.entity;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * What a drone is built from. The tier's pickaxe sets how fast it breaks blocks and which ones drop, like a player's,
 * and its sword how hard it hits
 */
public enum DroneTier {
	COPPER("copper", Items.COPPER_PICKAXE, Items.COPPER_SWORD),
	IRON("iron", Items.IRON_PICKAXE, Items.IRON_SWORD),
	DIAMOND("diamond", Items.DIAMOND_PICKAXE, Items.DIAMOND_SWORD),
	NETHERITE("netherite", Items.NETHERITE_PICKAXE, Items.NETHERITE_SWORD);

	public final String id;
	public final Item pickaxe;
	public final Item sword;

	DroneTier(String id, Item pickaxe, Item sword) {
		this.id = id;
		this.pickaxe = pickaxe;
		this.sword = sword;
	}

	/** A hit's damage, what the tier's sword deals in a player's hand */
	public float attackDamage() {
		ItemAttributeModifiers modifiers = new ItemStack(this.sword).getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return (float) modifiers.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
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
