package com.catherinepereira.mcdrone;

import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.entity.DroneItem;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class ModContent {
	public static final ResourceKey<EntityType<?>> DRONE_KEY = ResourceKey.create(Registries.ENTITY_TYPE, McDrone.id("drone"));
	public static final EntityType<DroneEntity> DRONE = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		DRONE_KEY,
		EntityType.Builder.<DroneEntity>of(DroneEntity::new, MobCategory.MISC)
			.sized(0.6F, 0.4F)
			.clientTrackingRange(10)
			.updateInterval(1)
			.fireImmune()
			.build(DRONE_KEY)
	);

	public static final Item DRONE_ITEM = registerItem("drone", DroneItem::new, new Item.Properties().stacksTo(1));
	// selects copy regions, see RemoteInput
	public static final Item REMOTE = registerItem("remote", Item::new, new Item.Properties().stacksTo(1));

	// emissive so the marker reads the same in any light
	public static final Block MARKER = registerBlock(
		"marker",
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(-1.0F, 3600000.0F).lightLevel(state -> 15).sound(SoundType.GLASS).noLootTable()
	);
	public static final Item MARKER_ITEM = registerItem("marker", p -> new BlockItem(MARKER, p), new Item.Properties().useBlockDescriptionPrefix());

	private ModContent() {
	}

	public static void register() {
		RemoteInput.register();
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> {
			output.accept(DRONE_ITEM);
			output.accept(REMOTE);
			output.accept(MARKER_ITEM);
		});
	}

	private static Item registerItem(String name, java.util.function.Function<Item.Properties, Item> factory, Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, McDrone.id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(properties.setId(key)));
	}

	private static Block registerBlock(String name, BlockBehaviour.Properties properties) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, McDrone.id(name));
		return Registry.register(BuiltInRegistries.BLOCK, key, new Block(properties.setId(key)));
	}
}
