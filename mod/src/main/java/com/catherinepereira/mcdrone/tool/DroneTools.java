package com.catherinepereira.mcdrone.tool;

import com.catherinepereira.mcdrone.Json;
import com.catherinepereira.mcdrone.ModContent;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.net.DroneSyncPayload;
import com.catherinepereira.mcdrone.task.Arena;
import com.catherinepereira.mcdrone.task.ArenaRecord;
import com.catherinepereira.mcdrone.task.DroneContainers;
import com.catherinepereira.mcdrone.task.HarvestJob;
import com.catherinepereira.mcdrone.task.Region;
import com.catherinepereira.mcdrone.task.RegionStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Applies one tick of drone tool use on the server.
 * Mining follows vanilla's player formula with the pickaxe of the drone's tier, so block hardness and harvest rules match survival
 */
public final class DroneTools {
	public static final double REACH = 4.5;
	// a container stays open while its center is within this distance of the drone's camera
	public static final double CONTAINER_RANGE = REACH + 1.5;

	private DroneTools() {
	}

	private static ItemStack tool(DroneEntity drone) {
		return new ItemStack(drone.tier().pickaxe);
	}

	public static DroneSyncPayload apply(ServerPlayer player, DroneEntity drone, int seq, ToolRequest req) {
		ServerLevel level = (ServerLevel) drone.level();
		List<BlockPos> changed = new ArrayList<>();
		JsonArray events = new JsonArray();
		ArenaRecord record = Arena.record(player);

		validateContainer(level, drone, events);
		if (req.slot() < 0 || req.slot() >= DroneEntity.INVENTORY_SIZE) {
			events.add(event("error", "message", "slot out of range"));
		} else if (drone.flat() && req.tool() != DroneTool.NONE && req.tool() != DroneTool.CLOSE) {
			events.add(event(req.tool().name().toLowerCase(java.util.Locale.ROOT) + "_failed", "reason", "battery flat"));
		} else {
			switch (req.tool()) {
				case BREAK -> mine(level, drone, changed, events, record);
				case PLACE -> place(level, drone, req, changed, events, record);
				case OPEN -> open(level, drone, events);
				case CLOSE -> close(drone, events, "requested");
				case NONE -> {
				}
			}
		}
		if (req.tool() != DroneTool.BREAK && drone.breakingPos != null) {
			stopMining(level, drone);
		}
		if (req.transfer() != ToolRequest.NO_TRANSFER) {
			transfer(level, drone, req, events);
		}

		Container container = DroneContainers.resolve(level, drone.openContainer);
		long[] positions = new long[changed.size()];
		int[] states = new int[changed.size()];
		for (int i = 0; i < changed.size(); i++) {
			positions[i] = changed.get(i).asLong();
			states[i] = Block.getId(level.getBlockState(changed.get(i)));
		}
		return new DroneSyncPayload(
			drone.getId(),
			seq,
			flatten(drone.inventory),
			container != null,
			container == null ? BlockPos.ZERO : drone.openContainer,
			container == null ? new int[0] : flatten(container),
			drone.breakingPos != null,
			drone.breakingPos == null ? BlockPos.ZERO : drone.breakingPos,
			drone.breakProgress,
			positions,
			states,
			events.toString(),
			record == null ? new int[0] : record.metrics(level, drone)
		);
	}

	private static @Nullable BlockHitResult pick(ServerLevel level, DroneEntity drone) {
		Vec3 eye = drone.getEyePosition();
		Vec3 end = eye.add(drone.getLookAngle().scale(REACH));
		BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, drone));
		return hit.getType() == HitResult.Type.BLOCK ? hit : null;
	}

	private static void mine(ServerLevel level, DroneEntity drone, List<BlockPos> changed, JsonArray events, @Nullable ArenaRecord record) {
		BlockHitResult hit = pick(level, drone);
		if (hit == null) {
			stopMining(level, drone);
			return;
		}
		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		String refusal = refusal(level, record, pos);
		if (refusal == null && record != null && record.job instanceof HarvestJob harvest) {
			refusal = harvest.unripe(state) ? "not ripe yet" : state.is(harvest.crop) ? null : "a harvest only breaks ripe crops";
		}
		if (refusal != null) {
			stopMining(level, drone);
			events.add(event("break_failed", "reason", refusal));
			return;
		}
		if (!pos.equals(drone.breakingPos)) {
			stopMining(level, drone);
			drone.breakingPos = pos.immutable();
		}
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0 || state.is(ModContent.MARKER)) {
			return;
		}
		ItemStack tool = tool(drone);
		boolean correct = drone.tier().canHarvest(state);
		drone.breakProgress += hardness == 0 ? 1.0F : tool.getDestroySpeed(state) / hardness / (correct ? 30.0F : 100.0F);
		if (drone.breakProgress < 1.0F) {
			level.destroyBlockProgress(drone.getId(), pos, (int) (drone.breakProgress * 10.0F));
			return;
		}
		List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), drone, tool);
		level.destroyBlock(pos, false, drone);
		for (ItemStack drop : drops) {
			ItemStack rest = drone.inventory.addItem(drop);
			if (!rest.isEmpty()) {
				Block.popResource(level, pos, rest);
			}
		}
		changed.add(pos);
		drone.spendBreak();
		JsonObject e = event("break", "block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		e.add("pos", Json.pos(pos));
		events.add(e);
		stopMining(level, drone);
	}

	private static void stopMining(ServerLevel level, DroneEntity drone) {
		if (drone.breakingPos != null) {
			level.destroyBlockProgress(drone.getId(), drone.breakingPos, -1);
		}
		drone.breakingPos = null;
		drone.breakProgress = 0;
	}

	private static void place(ServerLevel level, DroneEntity drone, ToolRequest req, List<BlockPos> changed, JsonArray events, @Nullable ArenaRecord record) {
		ItemStack stack = drone.inventory.getItem(req.slot());
		if (!req.block().isEmpty()) {
			Item item = blockItem(req.block());
			if (item == null) {
				events.add(event("place_failed", "reason", "unknown block " + req.block()));
				return;
			}
			if (req.unlimited()) {
				// a throwaway stack, unlimited placing never touches the inventory
				stack = new ItemStack(item);
			} else {
				int slot = findSlot(drone, item);
				if (slot < 0) {
					events.add(event("place_failed", "reason", "out of " + req.block()));
					return;
				}
				stack = drone.inventory.getItem(slot);
			}
		}
		BlockHitResult hit = pick(level, drone);
		if (!(stack.getItem() instanceof BlockItem blockItem) || hit == null) {
			events.add(event("place_failed", "reason", hit == null ? "nothing in reach" : "slot holds no block"));
			return;
		}
		BlockPlaceContext ctx = new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, stack, hit);
		BlockPos target = ctx.getClickedPos();
		String refusal = refusal(level, record, target);
		if (refusal != null) {
			events.add(event("place_failed", "reason", refusal));
			return;
		}
		if (drone.getBoundingBox().intersects(new AABB(target))) {
			events.add(event("place_failed", "reason", "drone is in the way"));
			return;
		}
		InteractionResult result = blockItem.place(ctx);
		if (!result.consumesAction()) {
			events.add(event("place_failed", "reason", "blocked"));
			return;
		}
		drone.inventory.setChanged();
		changed.add(target);
		JsonObject e = event("place", "block", BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString());
		e.add("pos", Json.pos(target));
		events.add(e);
		if (record != null && record.goal != null && !target.equals(record.goal)) {
			record.wrongPlacements++;
		}
	}

	/** Why the drone may not change the block at pos: a safe region, or a player's job that works somewhere else */
	private static @Nullable String refusal(ServerLevel level, @Nullable ArenaRecord record, BlockPos pos) {
		Region safe = RegionStore.get(level.getServer()).safeAt(pos).orElse(null);
		if (safe != null) {
			return "protected by safe region " + safe.name();
		}
		if (record != null && record.kind.isJob() && record.job != null && !record.job.allows(pos)) {
			return "outside the job's region";
		}
		return null;
	}

	private static @Nullable Item blockItem(String name) {
		Identifier id = Identifier.tryParse(name);
		Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		return item instanceof BlockItem ? item : null;
	}

	private static int findSlot(DroneEntity drone, Item item) {
		for (int i = 0; i < drone.inventory.getContainerSize(); i++) {
			if (drone.inventory.getItem(i).is(item)) {
				return i;
			}
		}
		return -1;
	}

	private static void open(ServerLevel level, DroneEntity drone, JsonArray events) {
		BlockHitResult hit = pick(level, drone);
		BlockPos pos = hit == null ? null : hit.getBlockPos();
		if (pos == null || DroneContainers.resolve(level, pos) == null) {
			events.add(event("open_failed", "reason", "not facing a container"));
			return;
		}
		drone.openContainer = pos.immutable();
		JsonObject e = event("open", "block", BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
		e.add("pos", Json.pos(pos));
		events.add(e);
	}

	private static void close(DroneEntity drone, JsonArray events, String reason) {
		if (drone.openContainer != null) {
			drone.openContainer = null;
			events.add(event("close", "reason", reason));
		}
	}

	private static void validateContainer(ServerLevel level, DroneEntity drone, JsonArray events) {
		if (drone.openContainer == null) {
			return;
		}
		if (DroneContainers.resolve(level, drone.openContainer) == null) {
			close(drone, events, "container gone");
		} else if (drone.getEyePosition().distanceTo(Vec3.atCenterOf(drone.openContainer)) > CONTAINER_RANGE) {
			close(drone, events, "out of range");
		}
	}

	private static void transfer(ServerLevel level, DroneEntity drone, ToolRequest req, JsonArray events) {
		Container container = DroneContainers.resolve(level, drone.openContainer);
		if (container == null) {
			events.add(event("transfer_failed", "reason", "no open container"));
			return;
		}
		boolean toContainer = req.transfer() == ToolRequest.DRONE_TO_CONTAINER;
		Container from = toContainer ? drone.inventory : container;
		Container to = toContainer ? container : drone.inventory;
		if (req.fromSlot() < 0 || req.fromSlot() >= from.getContainerSize() || req.toSlot() >= to.getContainerSize()) {
			events.add(event("transfer_failed", "reason", "slot out of range"));
			return;
		}
		ItemStack source = from.getItem(req.fromSlot());
		if (source.isEmpty()) {
			events.add(event("transfer_failed", "reason", "empty slot"));
			return;
		}
		int wanted = req.count() <= 0 ? source.getCount() : Math.min(req.count(), source.getCount());
		int moved = req.toSlot() >= 0 ? insertInto(to, req.toSlot(), source, wanted) : quickMove(to, source, wanted);
		if (moved == 0) {
			events.add(event("transfer_failed", "reason", "no room"));
			return;
		}
		String item = BuiltInRegistries.ITEM.getKey(source.getItem()).toString();
		from.removeItem(req.fromSlot(), moved);
		from.setChanged();
		to.setChanged();
		JsonObject e = event("transfer", "item", item);
		e.addProperty("from", toContainer ? "drone" : "container");
		e.addProperty("slot", req.fromSlot());
		e.addProperty("count", moved);
		events.add(e);
	}

	// merges into matching stacks first, then empty slots, the way a shift-click does
	private static int quickMove(Container to, ItemStack source, int wanted) {
		int moved = 0;
		for (int pass = 0; pass < 2 && moved < wanted; pass++) {
			for (int i = 0; i < to.getContainerSize() && moved < wanted; i++) {
				ItemStack existing = to.getItem(i);
				if ((pass == 0) == existing.isEmpty()) {
					continue;
				}
				moved += insertInto(to, i, source, wanted - moved);
			}
		}
		return moved;
	}

	private static int insertInto(Container to, int slot, ItemStack source, int wanted) {
		if (!to.canPlaceItem(slot, source)) {
			return 0;
		}
		ItemStack existing = to.getItem(slot);
		int limit = Math.min(to.getMaxStackSize(source), source.getMaxStackSize());
		if (existing.isEmpty()) {
			int n = Math.min(wanted, limit);
			to.setItem(slot, source.copyWithCount(n));
			return n;
		}
		if (!ItemStack.isSameItemSameComponents(existing, source)) {
			return 0;
		}
		int n = Math.min(wanted, limit - existing.getCount());
		if (n <= 0) {
			return 0;
		}
		existing.grow(n);
		return n;
	}

	private static int[] flatten(Container container) {
		int[] flat = new int[container.getContainerSize() * 2];
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			flat[i * 2] = stack.isEmpty() ? 0 : BuiltInRegistries.ITEM.getId(stack.getItem());
			flat[i * 2 + 1] = stack.getCount();
		}
		return flat;
	}

	private static JsonObject event(String type, String key, String value) {
		JsonObject e = new JsonObject();
		e.addProperty("type", type);
		e.addProperty(key, value);
		return e;
	}
}
