package com.catherinepereira.mcdrone.task;

import com.catherinepereira.mcdrone.ModContent;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.entity.DroneItem;
import com.catherinepereira.mcdrone.entity.DroneTier;
import com.catherinepereira.mcdrone.entity.Drones;
import com.catherinepereira.mcdrone.map.MapChanges;
import com.catherinepereira.mcdrone.net.ResetTaskPayload;
import com.catherinepereira.mcdrone.net.TaskReadyPayload;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Builds the training arenas under the player: terrain, obstacles, and the task's goals, then spawns or resets the drone.
 * Everything random comes from the request seed, so a seed always produces the same layout
 */
public final class TrainingArenas {
	public static final int HEIGHT = 16;
	public static final double MIN_SPAWN_DIST = 8.0;
	public static final double KEY_SPAWN_DIST = 5.0;
	public static final double CLEARANCE = 2.0;
	// skip block entity side effects so clearing a filled chest doesn't spill its items
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
	private static final List<Item> TRANSFER_ITEMS = List.of(
		Items.COBBLESTONE, Items.OAK_LOG, Items.IRON_INGOT, Items.WHEAT, Items.REDSTONE, Items.COAL, Items.BRICK, Items.STRING
	);

	private TrainingArenas() {
	}

	static TaskReadyPayload build(ServerPlayer player, ResetTaskPayload req) {
		return buildShared(player, List.of(req)).getFirst();
	}

	// one drone's part of a shared arena: its record, where it starts, and what it carries
	private record Member(
		ResetTaskPayload req, Random rng, @Nullable DroneEntity existing, ArenaRecord record, Vec3 spawn, float yaw, BlockPos primary, @Nullable List<ItemStack> stock
	) {
	}

	/**
	 * One arena for several drones, each with its own task, sites, and spawn, see client.FleetResets.
	 * The first request's seed, terrain, origin, and obstacles make the arena, which grows with the number of drones.
	 * With one request it's the arena that request always builds
	 */
	static List<TaskReadyPayload> buildShared(ServerPlayer player, List<ResetTaskPayload> reqs) {
		ServerLevel level = player.level();
		ResetTaskPayload first = reqs.getFirst();
		List<DroneEntity> existing = new ArrayList<>();
		int radius = 4;
		for (ResetTaskPayload req : reqs) {
			TaskKind kind = TaskKind.parse(req.task());
			if (kind.isJob()) {
				throw new IllegalArgumentException(kind.id + " is a player job, an arena takes training tasks");
			}
			DroneEntity drone = Drones.resolve(player, req.droneId());
			if (drone != null && existing.contains(drone)) {
				throw new IllegalArgumentException(drone.shownName() + " can't take two tasks in one arena");
			}
			existing.add(drone);
			int size = Math.clamp(req.size(), 3, BuildJob.MAX_SIZE);
			// two or three sites of size blocks across have to fit side by side
			radius = Math.max(radius, isStructure(kind) ? Math.max(req.radius(), 2 * size + 8) : req.radius());
		}
		// every drone's sites take about the room of an arena of their own
		radius = Math.clamp((int) Math.round(radius * Math.sqrt(reqs.size())), 4, 48);
		int ox = first.hasOrigin() ? first.originX() : player.getBlockX();
		int oz = first.hasOrigin() ? first.originZ() : player.getBlockZ();
		int floorY = findFloor(level, ox, oz);
		BlockPos origin = new BlockPos(ox, floorY, oz);
		Random rng = new Random(first.seed());

		// a bigger previous arena on the same spot leaves cave walls and roof outside this one's footprint
		int clearRadius = radius;
		for (DroneEntity drone : existing) {
			ArenaRecord previous = drone == null ? null : Arena.record(drone);
			if (previous != null && previous.origin.equals(origin)) {
				clearRadius = Math.max(clearRadius, previous.radius);
			}
		}
		build(level, ox, floorY, oz, radius, clearRadius);
		Terrain terrain = Terrain.create(first.terrain(), new Random(first.seed() ^ 0x7E44A1L), floorY, ox, oz, radius);
		terrain.place(level, new Random(first.seed() ^ 0x5EEDL));
		List<Vec3> keyPoints = new ArrayList<>();
		List<Member> members = new ArrayList<>();
		for (int i = 0; i < reqs.size(); i++) {
			// the first drone draws from the arena's random source, so a one-drone arena is laid out as it always was
			members.add(placeTask(level, i == 0 ? rng : new Random(reqs.get(i).seed()), reqs.get(i), existing.get(i), origin, radius, terrain, keyPoints));
		}

		List<int[]> obstacles = placeObstacles(level, rng, terrain, ox, oz, radius, first.obstacles(), keyPoints);
		List<int[]> trees = terrain.kind.equals("rough") ? plantTrees(level, rng, terrain, ox, oz, radius, keyPoints, obstacles) : List.of();
		List<int[]> stalactites = terrain.isCave() ? hangStalactites(level, rng, terrain, ox, oz, radius, keyPoints, obstacles) : List.of();
		for (Member m : members) {
			m.record.obstacles = obstacles;
			m.record.trees = trees;
			m.record.stalactites = stalactites;
			if (m.record.marker != null) {
				level.setBlock(m.record.marker, ModContent.MARKER.defaultBlockState(), FLAGS);
			}
		}
		parkPlayer(level, player, ox, floorY, oz);
		int edge = clearRadius + 2;
		MapChanges.changed(level, origin.offset(-edge, 0, -edge), origin.offset(edge, HEIGHT, edge));
		List<TaskReadyPayload> ready = new ArrayList<>();
		for (Member m : members) {
			ready.add(startDrone(level, player, m));
		}
		return ready;
	}

	private static boolean isStructure(TaskKind kind) {
		return kind == TaskKind.COPY_BUILD || kind == TaskKind.SCHEMATIC_BUILD || kind == TaskKind.MINE_DEPOSIT || kind == TaskKind.GATHER_BUILD;
	}

	// the task's sites and the drone's spawn, clear of every key point so far
	private static Member placeTask(
		ServerLevel level, Random rng, ResetTaskPayload req, @Nullable DroneEntity existing, BlockPos origin, int radius, Terrain terrain, List<Vec3> keyPoints
	) {
		TaskKind kind = TaskKind.parse(req.task());
		int ox = origin.getX();
		int oz = origin.getZ();
		int size = Math.clamp(req.size(), 3, BuildJob.MAX_SIZE);
		ArenaRecord record = new ArenaRecord(kind, origin, radius);
		record.terrain = terrain.kind;
		record.tier = !req.tier().isEmpty() ? DroneTier.parse(req.tier()) : existing != null ? existing.tier() : DroneTier.COPPER;
		int start = keyPoints.size();
		int targets = Math.clamp(req.targets(), 1, 8);
		List<ItemStack> stock = null;

		switch (kind) {
			case NAVIGATE_TO -> {
				BlockPos cell = randomInside(rng, ox, oz, radius - 1, 0);
				int markerY = Math.min(terrain.ground(cell.getX(), cell.getZ()) + 1 + rng.nextInt(6), terrain.ceiling(cell.getX(), cell.getZ()) - 2);
				record.marker = new BlockPos(cell.getX(), markerY, cell.getZ());
				keyPoints.add(Vec3.atCenterOf(record.marker));
			}
			case DIG_BLOCK, MINE_AND_DELIVER -> {
				placePedestals(level, rng, record, keyPoints, kind == TaskKind.DIG_BLOCK ? targets : Math.max(2, targets), terrain);
				if (kind == TaskKind.MINE_AND_DELIVER) {
					record.requiredCount = record.targets.size();
					record.targetChest = freeCell(rng, record, keyPoints, terrain, 1, 4.0);
					placeChest(level, record.targetChest, ox, oz);
					keyPoints.add(Vec3.atCenterOf(record.targetChest));
				}
			}
			case PLACE_BLOCK -> {
				BlockPos pad = freeCell(rng, record, keyPoints, terrain, 0, 0.0);
				level.setBlock(pad, Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState(), FLAGS);
				record.goal = pad.above();
				record.goalBlock = Blocks.OAK_PLANKS;
				keyPoints.add(Vec3.atCenterOf(record.goal));
			}
			case CHEST_TRANSFER -> {
				record.sourceChest = freeCell(rng, record, keyPoints, terrain, 1, 0.0);
				keyPoints.add(Vec3.atCenterOf(record.sourceChest));
				record.targetChest = freeCell(rng, record, keyPoints, terrain, 1, 8.0);
				keyPoints.add(Vec3.atCenterOf(record.targetChest));
				placeChest(level, record.sourceChest, ox, oz);
				placeChest(level, record.targetChest, ox, oz);
				fillSource(level, rng, record);
			}
			case REPLICATE_BUILD -> {
				record.blueprint = Blueprint.random(rng);
				record.referenceBase = buildSite(level, rng, record, keyPoints, terrain, DyeColor.CYAN, 0.0);
				record.buildBase = buildSite(level, rng, record, keyPoints, terrain, DyeColor.LIME, 9.0);
				record.blueprint.build(level, record.referenceBase);
				// the same job input a player's copy job gives, the 3x3 column of air above each base
				int top = Blueprint.SIZE;
				record.job = BuildJob.copy(level, record.referenceBase.offset(-1, 1, -1), record.referenceBase.offset(1, top, 1), record.buildBase.offset(-1, 1, -1));
			}
			case HARVEST_CROPS -> {
				BlockPos[] plot = buildFarm(level, rng, record, keyPoints, terrain);
				record.job = HarvestJob.start(level, plot[0], plot[1], "minecraft:wheat");
			}
			case COPY_BUILD, SCHEMATIC_BUILD, GATHER_BUILD -> {
				int height = Math.max(3, size * 2 / 3);
				Schematic target = Structures.random(rng, size, height, size);
				BlockPos max = new BlockPos(size - 1, height - 1, size - 1);
				BlockPos dest = site(level, rng, record, keyPoints, terrain, size, size, Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState(), 0.0).above();
				if (kind == TaskKind.SCHEMATIC_BUILD) {
					String name = "arena-" + Long.toHexString(req.seed()) + ".schem";
					try {
						Files.createDirectories(Arena.SCHEMATICS);
						target.write(Arena.SCHEMATICS.resolve(name));
					} catch (IOException e) {
						throw new IllegalStateException("could not write " + name, e);
					}
					record.job = BuildJob.build(target, name, dest);
				} else {
					BlockPos source = site(level, rng, record, keyPoints, terrain, size, size, Blocks.CONCRETE.pick(DyeColor.CYAN).defaultBlockState(), size + 4.0).above();
					Structures.paste(level, target, source);
					BuildJob job = BuildJob.copy(level, source, source.offset(max), dest);
					if (kind == TaskKind.GATHER_BUILD) {
						// the materials, each kind with one spare, set into a stone deposit about three times their volume
						List<Block> materials = new ArrayList<>();
						Structures.counts(target).forEach((block, n) -> materials.addAll(Collections.nCopies(n + 1, block)));
						int side = Math.max(4, size);
						int depth = Math.clamp((3 * materials.size() + side * side - 1) / (side * side), 2, 6);
						BlockPos deposit = site(level, rng, record, keyPoints, terrain, side, side, Blocks.STONE.defaultBlockState(), size + 4.0).above();
						Structures.deposit(level, rng, deposit, deposit.offset(side - 1, depth - 1, side - 1), materials);
						job.gatherFrom(deposit, deposit.offset(side - 1, depth - 1, side - 1));
					}
					record.job = job;
				}
				if (kind != TaskKind.GATHER_BUILD) {
					stock = Structures.stock(rng, target, 27);
				}
			}
			case MINE_DEPOSIT -> {
				int depth = Math.max(3, size / 2 + 1);
				BlockPos min = site(level, rng, record, keyPoints, terrain, size, size, Blocks.STONE.defaultBlockState(), 0.0).above();
				int ores = Math.max(3, size * size * depth / 15);
				Structures.deposit(level, rng, min, min.offset(size - 1, depth - 1, size - 1), Collections.nCopies(ores, Blocks.COAL_ORE));
				record.job = MineJob.start(level, min, min.offset(size - 1, depth - 1, size - 1), "minecraft:coal_ore", record.tier);
			}
		}

		Vec3 primary = keyPoints.get(start);
		Vec3 spawn = null;
		for (int i = 0; i < 96 && spawn == null; i++) {
			BlockPos cell = randomInside(rng, ox, oz, radius - 1, 0);
			int ground = terrain.ground(cell.getX(), cell.getZ());
			double y = Math.min(ground + 1.5 + rng.nextDouble() * 4.0, terrain.ceiling(cell.getX(), cell.getZ()) - 1.0);
			Vec3 candidate = new Vec3(cell.getX() + 0.5, y, cell.getZ() + 0.5);
			boolean farFromPrimary = candidate.distanceTo(primary) >= MIN_SPAWN_DIST;
			boolean clearOfKeys = keyPoints.stream().allMatch(k -> horizontal(candidate, k) >= KEY_SPAWN_DIST);
			if (farFromPrimary && clearOfKeys) {
				spawn = candidate;
			}
		}
		if (spawn == null) {
			spawn = new Vec3(ox - radius + 1.5, terrain.ground(ox - radius + 1, oz - radius + 1) + 4.0, oz - radius + 1.5);
		}
		float yaw = rng.nextFloat() * 360.0F - 180.0F;
		keyPoints.add(spawn);
		return new Member(req, rng, existing, record, spawn, yaw, BlockPos.containing(primary), stock);
	}

	// spawns or moves the drone onto its spawn with a full battery and the task's items, and answers its request
	private static TaskReadyPayload startDrone(ServerLevel level, ServerPlayer player, Member m) {
		ArenaRecord record = m.record;
		TaskKind kind = record.kind;
		Vec3 spawn = m.spawn;
		DroneEntity drone = m.existing;
		if (drone == null) {
			drone = DroneItem.spawnFor(level, player, spawn, m.yaw, record.tier);
		}
		drone.snapTo(spawn.x, spawn.y, spawn.z, m.yaw, 0.0F);
		// arenas hand out a full drone, a player job may have run it down
		drone.trainingArena = true;
		drone.recharge();
		drone.setTier(record.tier);
		drone.inventory.clearContent();
		drone.openContainer = null;
		drone.breakingPos = null;
		drone.breakProgress = 0;
		if (kind == TaskKind.PLACE_BLOCK) {
			drone.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS, 16));
		}
		if (kind == TaskKind.HARVEST_CROPS) {
			drone.inventory.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 4));
		}
		List<ItemStack> stock = record.blueprint != null ? record.blueprint.stock(m.rng, drone.inventory.getContainerSize()) : m.stock;
		if (stock != null) {
			for (int i = 0; i < stock.size(); i++) {
				drone.inventory.setItem(i, stock.get(i));
			}
		}

		if (m.req.scan() && record.job != null) {
			record.scan = Scans.write(level, record.job, kind.id + "-" + Long.toHexString(m.req.seed()));
		}
		Arena.put(drone, record);
		return new TaskReadyPayload(
			m.req.requestId(), drone.getId(), m.primary, record.origin, spawn.x, spawn.y, spawn.z, m.yaw, record.toJson().toString(), ""
		);
	}

	/**
	 * A square wheat field, 5 or 7 blocks across, level at the highest ground under it, with a water channel
	 * down the middle row. Each farmland cell holds ripe wheat, young wheat, or nothing, with at least two ripe and one
	 * empty. Returns the field's min and max corners at farmland height
	 */
	private static BlockPos[] buildFarm(ServerLevel level, Random rng, ArenaRecord record, List<Vec3> keyPoints, Terrain terrain) {
		int half = 2 + rng.nextInt(2);
		BlockPos center = null;
		int baseY = 0;
		for (int i = 0; i < 200; i++) {
			BlockPos candidate = randomInside(rng, record.origin.getX(), record.origin.getZ(), record.radius - half - 2, 0);
			int[] span = groundAndCeiling(terrain, candidate.getX() - half, candidate.getZ() - half, 2 * half + 1, 2 * half + 1);
			int top = span[0];
			int roof = span[1];
			center = candidate;
			baseY = top;
			if (roof - top >= 6) {
				break;
			}
		}
		CropBlock wheat = (CropBlock) Blocks.WHEAT;
		BlockState farmland = Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, FarmlandBlock.MAX_MOISTURE);
		BlockState fill = terrain.isCave() ? Blocks.STONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
		List<BlockPos> cells = new ArrayList<>();
		for (int dx = -half; dx <= half; dx++) {
			for (int dz = -half; dz <= half; dz++) {
				int x = center.getX() + dx;
				int z = center.getZ() + dz;
				for (int y = terrain.ground(x, z) + 1; y < baseY; y++) {
					level.setBlock(new BlockPos(x, y, z), fill, FLAGS);
				}
				BlockPos ground = new BlockPos(x, baseY, z);
				if (dx == 0) {
					level.setBlock(ground, Blocks.WATER.defaultBlockState(), FLAGS);
				} else {
					level.setBlock(ground, farmland, FLAGS);
					cells.add(ground.above());
				}
				keyPoints.add(new Vec3(x + 0.5, baseY + 1.5, z + 0.5));
			}
		}
		java.util.Collections.shuffle(cells, rng);
		for (int i = 0; i < cells.size(); i++) {
			double roll = rng.nextDouble();
			// the first two cells are always ripe and the third always empty
			boolean ripe = i < 2 || (i > 2 && roll < 0.45);
			boolean empty = i == 2 || (i > 2 && roll > 0.75);
			if (ripe) {
				level.setBlock(cells.get(i), wheat.getStateForAge(wheat.getMaxAge()), FLAGS);
			} else if (!empty) {
				level.setBlock(cells.get(i), wheat.getStateForAge(rng.nextInt(5)), FLAGS);
			}
		}
		return new BlockPos[] {new BlockPos(center.getX() - half, baseY, center.getZ() - half), new BlockPos(center.getX() + half, baseY, center.getZ() + half)};
	}

	// coal ore on stone pedestals 0 to 2 high, plus twice as many plain stone pedestals as distractors
	private static void placePedestals(ServerLevel level, Random rng, ArenaRecord record, List<Vec3> keyPoints, int count, Terrain terrain) {
		for (int i = 0; i < count * 3; i++) {
			boolean target = i < count;
			BlockPos base = freeCell(rng, record, keyPoints, terrain, 1, 3.0);
			int height = rng.nextInt(3);
			for (int h = 0; h < height; h++) {
				level.setBlock(base.above(h), Blocks.STONE.defaultBlockState(), FLAGS);
			}
			BlockPos top = base.above(height);
			level.setBlock(top, target ? Blocks.COAL_ORE.defaultBlockState() : Blocks.STONE.defaultBlockState(), FLAGS);
			(target ? record.targets : record.distractors).add(top);
			keyPoints.add(Vec3.atCenterOf(top));
		}
	}

	/**
	 * A width x length footprint inside the arena, at least minDistFromKeys from the key points so far, levelled to the
	 * highest ground under it with a layer of base. Returns its min corner at base height
	 */
	private static BlockPos site(
		ServerLevel level, Random rng, ArenaRecord record, List<Vec3> keyPoints, Terrain terrain, int width, int length, BlockState base, double minDistFromKeys
	) {
		int reach = record.radius - Math.max(width, length) - 2;
		BlockPos min = null;
		int baseY = 0;
		double best = -1;
		for (int i = 0; i < 300; i++) {
			BlockPos candidate = randomInside(rng, record.origin.getX(), record.origin.getZ(), Math.max(1, reach), 0);
			double gap = gap(new Vec3(candidate.getX() + width / 2.0, 0, candidate.getZ() + length / 2.0), keyPoints);
			// with no candidate far enough out, the one farthest from what's already placed
			if (gap > best) {
				best = gap;
				min = candidate;
				baseY = groundAndCeiling(terrain, candidate.getX(), candidate.getZ(), width, length)[0];
			}
			// clear of every earlier key point by the given distance plus this footprint's own half width
			if (gap >= Math.max(minDistFromKeys + Math.max(width, length) / 2.0, 4.0)) {
				break;
			}
		}
		pad(level, terrain, min.getX(), min.getZ(), width, length, baseY, base, keyPoints);
		return new BlockPos(min.getX(), baseY, min.getZ());
	}

	/** The highest ground and the lowest ceiling under a footprint, from its min corner */
	private static int[] groundAndCeiling(Terrain terrain, int x0, int z0, int width, int length) {
		int top = Integer.MIN_VALUE;
		int roof = Integer.MAX_VALUE;
		for (int x = x0; x < x0 + width; x++) {
			for (int z = z0; z < z0 + length; z++) {
				top = Math.max(top, terrain.ground(x, z));
				roof = Math.min(roof, terrain.ceiling(x, z));
			}
		}
		return new int[] {top, roof};
	}

	/** Fills the ground under a footprint up to baseY and lays base on top, every cell of it becomes a key point */
	private static void pad(ServerLevel level, Terrain terrain, int x0, int z0, int width, int length, int baseY, BlockState base, List<Vec3> keyPoints) {
		BlockState fill = terrain.isCave() ? Blocks.STONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
		for (int x = x0; x < x0 + width; x++) {
			for (int z = z0; z < z0 + length; z++) {
				for (int y = terrain.ground(x, z) + 1; y < baseY; y++) {
					level.setBlock(new BlockPos(x, y, z), fill, FLAGS);
				}
				level.setBlock(new BlockPos(x, baseY, z), base, FLAGS);
				keyPoints.add(new Vec3(x + 0.5, baseY + 1.5, z + 0.5));
			}
		}
	}

	/**
	 * A level 3x3 concrete base at the highest ground under it, with room for a Blueprint.SIZE build and a drone above.
	 * Returns the base's center block, every cell of it becomes a key point so obstacles keep clear
	 */
	private static BlockPos buildSite(ServerLevel level, Random rng, ArenaRecord record, List<Vec3> keyPoints, Terrain terrain, DyeColor color, double minDistFromKeys) {
		int half = record.radius - 3;
		BlockPos center = null;
		int baseY = 0;
		double best = -Double.MAX_VALUE;
		for (int i = 0; i < 200; i++) {
			BlockPos candidate = randomInside(rng, record.origin.getX(), record.origin.getZ(), half, 0);
			int[] span = groundAndCeiling(terrain, candidate.getX() - 1, candidate.getZ() - 1, 3, 3);
			boolean roomy = span[1] - span[0] >= Blueprint.SIZE + 4;
			double gap = gap(Vec3.atCenterOf(candidate), keyPoints);
			// with no candidate that fits, the roomy one farthest from what's already placed
			double score = roomy ? gap : gap - 1000.0;
			if (score > best) {
				best = score;
				center = candidate;
				baseY = span[0];
			}
			if (roomy && gap >= Math.max(minDistFromKeys, 4.0)) {
				break;
			}
		}
		BlockState base = Blocks.CONCRETE.pick(color).defaultBlockState();
		pad(level, terrain, center.getX() - 1, center.getZ() - 1, 3, 3, baseY, base, keyPoints);
		return new BlockPos(center.getX(), baseY, center.getZ());
	}

	private static void placeChest(ServerLevel level, BlockPos pos, int ox, int oz) {
		int dx = ox - pos.getX();
		int dz = oz - pos.getZ();
		Direction facing = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
		level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), Block.UPDATE_ALL);
	}

	private static void fillSource(ServerLevel level, Random rng, ArenaRecord record) {
		Container chest = DroneContainers.resolve(level, record.sourceChest);
		if (chest == null) {
			throw new IllegalStateException("source chest missing at " + record.sourceChest);
		}
		int stacks = 2 + rng.nextInt(4);
		List<Integer> slots = new ArrayList<>();
		for (int i = 0; i < chest.getContainerSize(); i++) {
			slots.add(i);
		}
		java.util.Collections.shuffle(slots, rng);
		for (int i = 0; i < stacks; i++) {
			Item item = TRANSFER_ITEMS.get(rng.nextInt(TRANSFER_ITEMS.size()));
			int count = 8 + rng.nextInt(57);
			chest.setItem(slots.get(i), new ItemStack(item, count));
			record.required.merge(item, count, Integer::sum);
		}
		chest.setChanged();
	}

	/** A random cell yAboveGround blocks over the local ground, at least minDistFromKeys from every key point */
	private static BlockPos freeCell(Random rng, ArenaRecord record, List<Vec3> keyPoints, Terrain terrain, int yAboveGround, double minDistFromKeys) {
		int half = record.radius - 1;
		BlockPos cell = null;
		double best = -1;
		for (int i = 0; i < 200; i++) {
			BlockPos candidate = randomInside(rng, record.origin.getX(), record.origin.getZ(), half, 0);
			double gap = gap(Vec3.atCenterOf(candidate), keyPoints);
			// with no candidate far enough out, the one farthest from what's already placed
			if (gap > best) {
				best = gap;
				cell = candidate;
			}
			if (gap >= Math.max(minDistFromKeys, 1.5)) {
				break;
			}
		}
		return new BlockPos(cell.getX(), terrain.ground(cell.getX(), cell.getZ()) + yAboveGround, cell.getZ());
	}

	// oaks on rough ground, kept clear of key points and pillars like the pillars are
	private static List<int[]> plantTrees(ServerLevel level, Random rng, Terrain terrain, int ox, int oz, int radius, List<Vec3> keyPoints, List<int[]> pillars) {
		List<int[]> trees = new ArrayList<>();
		int wanted = 3 + rng.nextInt(radius / 3 + 1);
		for (int attempt = 0; attempt < wanted * 20 && trees.size() < wanted; attempt++) {
			BlockPos cell = randomInside(rng, ox, oz, radius - 2, 0);
			BlockPos crown = new BlockPos(cell.getX() - 2, 0, cell.getZ() - 2);
			boolean crowded = keyPoints.stream().anyMatch(k -> tooClose(crown, 5, k.x, k.z))
				|| pillars.stream().anyMatch(p -> Math.abs(p[0] - cell.getX()) < 4 && Math.abs(p[1] - cell.getZ()) < 4)
				|| trees.stream().anyMatch(t -> Math.abs(t[0] - cell.getX()) < 5 && Math.abs(t[1] - cell.getZ()) < 5);
			if (crowded) {
				continue;
			}
			int height = terrain.plantTree(level, rng, cell.getX(), cell.getZ());
			trees.add(new int[] {cell.getX(), cell.getZ(), height});
		}
		return trees;
	}

	// dripstone hanging to 2 or 3 blocks over the floor, at flying height, kept clear of key points and pillars
	private static List<int[]> hangStalactites(ServerLevel level, Random rng, Terrain terrain, int ox, int oz, int radius, List<Vec3> keyPoints, List<int[]> pillars) {
		List<int[]> stalactites = new ArrayList<>();
		int wanted = 4 + rng.nextInt(radius / 2 + 1);
		for (int attempt = 0; attempt < wanted * 20 && stalactites.size() < wanted; attempt++) {
			BlockPos cell = randomInside(rng, ox, oz, radius - 1, 0);
			boolean crowded = keyPoints.stream().anyMatch(k -> tooClose(cell, 1, k.x, k.z))
				|| pillars.stream().anyMatch(p -> tooClose(new BlockPos(p[0], 0, p[1]), p[2], cell.getX() + 0.5, cell.getZ() + 0.5))
				|| stalactites.stream().anyMatch(t -> Math.abs(t[0] - cell.getX()) < 3 && Math.abs(t[1] - cell.getZ()) < 3);
			if (crowded) {
				continue;
			}
			int bottom = terrain.ground(cell.getX(), cell.getZ()) + 3 + rng.nextInt(2);
			terrain.hangStalactite(level, cell.getX(), cell.getZ(), bottom);
			stalactites.add(new int[] {cell.getX(), cell.getZ(), bottom});
		}
		return stalactites;
	}

	/** How far point is from the nearest key point, across the ground */
	private static double gap(Vec3 point, List<Vec3> keyPoints) {
		return keyPoints.stream().mapToDouble(k -> horizontal(point, k)).min().orElse(Double.MAX_VALUE);
	}

	private static double horizontal(Vec3 a, Vec3 b) {
		return Math.hypot(a.x - b.x, a.z - b.z);
	}

	private static int findFloor(ServerLevel level, int x, int z) {
		// a previous build leaves light gray concrete as the floor, otherwise use the natural surface
		int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		BlockState floorBlock = Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState();
		for (int y = top; y > top - HEIGHT - 2 && y > level.getMinY(); y--) {
			if (level.getBlockState(new BlockPos(x, y, z)).equals(floorBlock)) {
				return y;
			}
		}
		return top;
	}

	private static void build(ServerLevel level, int ox, int floorY, int oz, int radius, int clearRadius) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int edge = radius + 2;
		int clearEdge = Math.max(edge, clearRadius + 2);
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState floor = Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState();
		BlockState border = Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState();
		for (int dx = -clearEdge; dx <= clearEdge; dx++) {
			for (int dz = -clearEdge; dz <= clearEdge; dz++) {
				boolean inside = Math.abs(dx) <= radius && Math.abs(dz) <= radius;
				pos.set(ox + dx, floorY, oz + dz);
				if (Math.abs(dx) <= edge && Math.abs(dz) <= edge) {
					level.setBlock(pos, inside ? floor : border, FLAGS);
				}
				for (int y = floorY + 1; y <= floorY + HEIGHT; y++) {
					pos.setY(y);
					if (!level.getBlockState(pos).isAir()) {
						level.setBlock(pos, air, FLAGS);
					}
				}
			}
		}
		AABB box = new AABB(ox - clearEdge, floorY, oz - clearEdge, ox + clearEdge + 1, floorY + HEIGHT + 1, oz + clearEdge + 1);
		level.getEntitiesOfClass(ItemEntity.class, box).forEach(ItemEntity::discard);
	}

	/**
	 * Stone brick pillars, 1x1 or 2x2 and 3 to 9 tall, kept CLEARANCE blocks from every key point.
	 * In a cave a pillar taller than the room joins the roof.
	 * Returns them as x, z, width, height per pillar
	 */
	private static List<int[]> placeObstacles(ServerLevel level, Random rng, Terrain terrain, int ox, int oz, int radius, int count, List<Vec3> keyPoints) {
		List<int[]> pillars = new ArrayList<>();
		BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
		int wanted = Math.clamp(count, 0, 32);
		for (int attempt = 0; attempt < wanted * 20 && pillars.size() < wanted; attempt++) {
			int width = rng.nextFloat() < 0.35F ? 2 : 1;
			BlockPos base = randomInside(rng, ox, oz, radius - width, 0);
			int height = 3 + rng.nextInt(7);
			if (keyPoints.stream().anyMatch(k -> tooClose(base, width, k.x, k.z))) {
				continue;
			}
			// the top is level even when the ground under a 2x2 pillar isn't
			int top = 0;
			for (int dx = 0; dx < width; dx++) {
				for (int dz = 0; dz < width; dz++) {
					top = Math.max(top, terrain.ground(base.getX() + dx, base.getZ() + dz) + height);
				}
			}
			for (int dx = 0; dx < width; dx++) {
				for (int dz = 0; dz < width; dz++) {
					top = Math.min(top, terrain.ceiling(base.getX() + dx, base.getZ() + dz) - 1);
				}
			}
			for (int dx = 0; dx < width; dx++) {
				for (int dz = 0; dz < width; dz++) {
					for (int y = terrain.ground(base.getX() + dx, base.getZ() + dz) + 1; y <= top; y++) {
						level.setBlock(new BlockPos(base.getX() + dx, y, base.getZ() + dz), brick, FLAGS);
					}
				}
			}
			pillars.add(new int[] {base.getX(), base.getZ(), width, Math.min(height, top - terrain.ground(base.getX(), base.getZ()))});
		}
		return pillars;
	}

	private static boolean tooClose(BlockPos base, int width, double x, double z) {
		double nearestX = Math.clamp(x, base.getX(), base.getX() + width);
		double nearestZ = Math.clamp(z, base.getZ(), base.getZ() + width);
		return Math.hypot(x - nearestX, z - nearestZ) < CLEARANCE;
	}

	// a two-block pocket under the floor keeps the player out of the drone's view and the arena chunks loaded.
	// It is walled in stone on every side, water from a farm channel or the terrain below drowned the player
	private static void parkPlayer(ServerLevel level, ServerPlayer player, int ox, int floorY, int oz) {
		BlockPos feet = new BlockPos(ox, floorY - 3, oz);
		BlockState stone = Blocks.STONE.defaultBlockState();
		for (int dy = -1; dy <= 2; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					boolean inside = dx == 0 && dz == 0 && (dy == 0 || dy == 1);
					level.setBlock(feet.offset(dx, dy, dz), inside ? Blocks.AIR.defaultBlockState() : stone, FLAGS);
				}
			}
		}
		player.connection.teleport(ox + 0.5, feet.getY(), oz + 0.5, player.getYRot(), player.getXRot());
	}

	private static BlockPos randomInside(Random rng, int ox, int oz, int half, int y) {
		int x = ox - half + rng.nextInt(half * 2 + 1);
		int z = oz - half + rng.nextInt(half * 2 + 1);
		return new BlockPos(x, y, z);
	}
}
