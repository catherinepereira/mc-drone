package com.catherinepereira.mcdrone.task;

import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/** One player's current arena, the server's source of truth for task progress */
public final class ArenaRecord {
	public final TaskKind kind;
	public final BlockPos origin;
	public final int radius;
	public List<int[]> obstacles = List.of();
	public List<int[]> trees = List.of();
	public List<int[]> stalactites = List.of();
	public String terrain = "flat";
	public @Nullable BlockPos marker;
	public final List<BlockPos> targets = new ArrayList<>();
	public final List<BlockPos> distractors = new ArrayList<>();
	public @Nullable BlockPos goal;
	public @Nullable Block goalBlock;
	public @Nullable BlockPos sourceChest;
	public @Nullable BlockPos targetChest;
	public final Map<Item, Integer> required = new LinkedHashMap<>();
	public int requiredCount;
	public int wrongPlacements;
	public @Nullable Blueprint blueprint;
	public @Nullable BlockPos referenceBase;
	public @Nullable BlockPos buildBase;
	public @Nullable DroneJob job;
	// overrides the geofence the client derives from origin and radius, as x0, y0, z0, x1, y1, z1
	public int @Nullable [] fence;

	ArenaRecord(TaskKind kind, BlockPos origin, int radius) {
		this.kind = kind;
		this.origin = origin;
		this.radius = radius;
	}

	/**
	 * Task progress counters sent with every tool sync.
	 * dig_block: remaining targets. place_block: placed (0 or 1), wrong placements.
	 * chest_transfer: items in the target chest, items required, required items the drone carries.
	 * mine_and_deliver: remaining targets, coal delivered, coal required, coal the drone carries.
	 * replicate_build, copy_region, build_schematic: destination cells that match, target blocks, destination blocks that don't belong.
	 * mine_region: blocks of the kind left in the region, how many there were at the start.
	 * harvest_crops, harvest_region: crops ripe at the start still standing, ripe ones harvested, ripe at the start,
	 * empty farmland cells, farmland cells
	 */
	public int[] metrics(ServerLevel level, DroneEntity drone) {
		return switch (this.kind) {
			case NAVIGATE_TO -> new int[0];
			case DIG_BLOCK -> new int[] {this.remainingTargets(level)};
			case PLACE_BLOCK -> new int[] {
				this.goal != null && level.getBlockState(this.goal).is(this.goalBlock) ? 1 : 0, this.wrongPlacements
			};
			case CHEST_TRANSFER -> {
				Container target = DroneContainers.resolve(level, this.targetChest);
				int inTarget = 0;
				int carried = 0;
				int total = 0;
				for (var entry : this.required.entrySet()) {
					inTarget += Math.min(entry.getValue(), target == null ? 0 : target.countItem(entry.getKey()));
					carried += drone.inventory.countItem(entry.getKey());
					total += entry.getValue();
				}
				yield new int[] {inTarget, total, carried};
			}
			case MINE_AND_DELIVER -> {
				Container chest = DroneContainers.resolve(level, this.targetChest);
				yield new int[] {
					this.remainingTargets(level),
					chest == null ? 0 : chest.countItem(Items.COAL),
					this.requiredCount,
					drone.inventory.countItem(Items.COAL)
				};
			}
			case REPLICATE_BUILD, COPY_REGION, BUILD_SCHEMATIC, MINE_REGION, HARVEST_CROPS, HARVEST_REGION -> this.job.score(level);
		};
	}

	private int remainingTargets(ServerLevel level) {
		return (int) this.targets.stream().filter(p -> level.getBlockState(p).is(Blocks.COAL_ORE)).count();
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("task", this.kind.id);
		json.add("origin", pos(this.origin));
		json.addProperty("radius", this.radius);
		json.add("obstacles", rows(this.obstacles));
		json.addProperty("terrain", this.terrain);
		json.add("trees", rows(this.trees));
		json.add("stalactites", rows(this.stalactites));
		if (this.marker != null) {
			json.add("marker", pos(this.marker));
		}
		if (!this.targets.isEmpty()) {
			json.add("targets", positions(this.targets));
			json.add("distractors", positions(this.distractors));
			json.addProperty("targetBlock", "minecraft:coal_ore");
		}
		if (this.goal != null) {
			json.add("goal", pos(this.goal));
			json.addProperty("goalBlock", BuiltInRegistries.BLOCK.getKey(this.goalBlock).toString());
		}
		if (this.sourceChest != null) {
			json.add("sourceChest", pos(this.sourceChest));
		}
		if (this.targetChest != null) {
			json.add("targetChest", pos(this.targetChest));
		}
		if (!this.required.isEmpty()) {
			JsonObject req = new JsonObject();
			this.required.forEach((item, count) -> req.addProperty(BuiltInRegistries.ITEM.getKey(item).toString(), count));
			json.add("required", req);
		}
		if (this.job != null) {
			json.add("job", this.job.toJson());
		}
		if (this.fence != null) {
			JsonArray f = new JsonArray();
			for (int v : this.fence) {
				f.add(v);
			}
			json.add("fence", f);
		}
		if (this.blueprint != null) {
			json.add("referenceBase", pos(this.referenceBase));
			json.add("buildBase", pos(this.buildBase));
			json.add("blueprint", this.blueprint.toJson());
		}
		if (this.requiredCount > 0) {
			json.addProperty("requiredCount", this.requiredCount);
			json.addProperty("deliverItem", "minecraft:coal");
		}
		return json;
	}

	private static JsonArray rows(List<int[]> list) {
		JsonArray rows = new JsonArray();
		for (int[] row : list) {
			JsonArray a = new JsonArray();
			for (int v : row) {
				a.add(v);
			}
			rows.add(a);
		}
		return rows;
	}

	private static JsonArray pos(BlockPos p) {
		JsonArray a = new JsonArray();
		a.add(p.getX());
		a.add(p.getY());
		a.add(p.getZ());
		return a;
	}

	private static JsonArray positions(List<BlockPos> list) {
		JsonArray a = new JsonArray();
		list.forEach(p -> a.add(pos(p)));
		return a;
	}
}
