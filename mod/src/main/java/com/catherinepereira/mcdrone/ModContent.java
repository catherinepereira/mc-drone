package com.catherinepereira.mcdrone;

import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.entity.BatteryConfig;
import com.catherinepereira.mcdrone.entity.DroneItem;
import com.catherinepereira.mcdrone.entity.DroneTier;
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

	public static final Item COPPER_DRONE = registerItem("copper_drone", p -> new DroneItem(p, DroneTier.COPPER), new Item.Properties().stacksTo(1));
	public static final Item IRON_DRONE = registerItem("iron_drone", p -> new DroneItem(p, DroneTier.IRON), new Item.Properties().stacksTo(1));
	public static final Item DIAMOND_DRONE = registerItem("diamond_drone", p -> new DroneItem(p, DroneTier.DIAMOND), new Item.Properties().stacksTo(1));
	// lists the player's drones and selects regions for their jobs, see TabletInput
	public static final Item TABLET = registerItem("tablet", Item::new, new Item.Properties().stacksTo(1));

	// a drone assigned to a station charges sitting on top of it
	public static final Block CHARGING_STATION = registerBlock(
		"charging_station",
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(3.0F, 6.0F).requiresCorrectToolForDrops().sound(SoundType.COPPER).lightLevel(state -> 7)
	);
	public static final Item CHARGING_STATION_ITEM = registerItem("charging_station", p -> new BlockItem(CHARGING_STATION, p), new Item.Properties().useBlockDescriptionPrefix());

	// emissive so the marker reads the same in any light
	public static final Block MARKER = registerBlock(
		"marker",
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(-1.0F, 3600000.0F).lightLevel(state -> 15).sound(SoundType.GLASS).noLootTable()
	);
	public static final Item MARKER_ITEM = registerItem("marker", p -> new BlockItem(MARKER, p), new Item.Properties().useBlockDescriptionPrefix());

	private ModContent() {
	}

	public static Item droneItem(DroneTier tier) {
		return switch (tier) {
			case COPPER -> COPPER_DRONE;
			case IRON -> IRON_DRONE;
			case DIAMOND -> DIAMOND_DRONE;
		};
	}

	public static void register() {
		TabletInput.register();
		BatteryConfig.load();
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> {
			output.accept(COPPER_DRONE);
			output.accept(IRON_DRONE);
			output.accept(DIAMOND_DRONE);
			output.accept(TABLET);
			output.accept(CHARGING_STATION_ITEM);
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
