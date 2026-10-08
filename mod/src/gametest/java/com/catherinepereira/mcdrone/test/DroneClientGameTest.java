package com.catherinepereira.mcdrone.test;

import com.catherinepereira.mcdrone.client.ClientRuntime;
import com.catherinepereira.mcdrone.client.McDroneClient;
import com.catherinepereira.mcdrone.client.hud.JobScreen;
import com.catherinepereira.mcdrone.client.obs.Raycaster;
import com.catherinepereira.mcdrone.ModContent;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.entity.DroneItem;
import com.catherinepereira.mcdrone.entity.DroneTier;
import com.catherinepereira.mcdrone.entity.Drones;
import com.catherinepereira.mcdrone.task.TaskKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Runs in a full game client: builds a superflat world, flies navigate_to over the bridge in lockstep
 * with a scripted policy that reads the marker position, and checks frames, scoring, and recording
 */
public class DroneClientGameTest implements FabricClientGameTest {
	private static final int PORT = 8318;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			// lockstep waits on rendered frames, so keep full frame rate while the window is in the background
			ctx.runOnClient(mc -> {
				mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
				mc.options.enableVsync().set(false);
				mc.options.framerateLimit().set(260);
			});
			ctx.waitTicks(20);
			ClientRuntime runtime = ctx.computeOnClient(mc -> McDroneClient.runtime());
			check(runtime != null, "runtime not started");

			BridgeTestClient bridge = new BridgeTestClient();
			try {
				bridge.connect(PORT);
			} catch (Exception e) {
				throw new AssertionError("bridge not reachable on " + PORT, e);
			}

			JsonObject hello = msg("hello");
			hello.addProperty("role", "controller");
			hello.addProperty("schema", ClientRuntime.SCHEMA);
			hello.addProperty("client", "gametest");
			bridge.send(hello);
			JsonObject welcome = awaitText(ctx, bridge, "welcome");
			check(welcome.get("role").getAsString().equals("controller"), "expected controller role, got " + welcome);

			JsonObject configure = msg("configure");
			configure.addProperty("mode", "lockstep");
			bridge.send(configure);
			// the scripted policy reads the marker, so its episodes go to the test folder, out of the training data
			JsonObject record = msg("record");
			record.addProperty("on", true);
			record.addProperty("test", true);
			bridge.send(record);
			ctx.waitTicks(2);
			Path testData = runtime.recorder().dataDir();
			check(!testData.equals(Path.of(System.getProperty("mcdrone.data", "data"))), "test recordings went to the training data");

			JsonObject reset = msg("reset");
			reset.addProperty("id", 1);
			reset.addProperty("seed", 7);
			bridge.send(reset);
			BridgeTestClient.Obs obs = awaitObs(ctx, bridge, 1);
			ctx.takeScreenshot("mcdrone-navigate-start");
			checkFrame(obs);

			int id = 2;
			boolean success = false;
			for (int i = 0; i < 400 && !success; i++) {
				JsonObject step = msg("step");
				step.addProperty("id", id);
				step.add("action", scriptedAction(obs.header().getAsJsonObject("state")));
				step.addProperty("ticks", 1);
				bridge.send(step);
				obs = awaitObs(ctx, bridge, id++);
				JsonObject episode = obs.header().getAsJsonObject("episode");
				success = episode.get("success").getAsBoolean();
				if (episode.get("done").getAsBoolean() && !success) {
					throw new AssertionError("episode ended without success: " + episode);
				}
				if (i == 10) {
					ctx.takeScreenshot("mcdrone-navigate-flying");
				}
			}
			check(success, "scripted policy never reached the marker");
			ctx.takeScreenshot("mcdrone-navigate-done");

			JsonObject obstacleReset = msg("reset");
			obstacleReset.addProperty("id", id);
			obstacleReset.addProperty("seed", 8);
			JsonObject options = new JsonObject();
			options.addProperty("obstacles", 6);
			obstacleReset.add("options", options);
			bridge.send(obstacleReset);
			obs = awaitObs(ctx, bridge, id++);
			checkArena(obs.header().getAsJsonObject("state"), 6);
			ctx.takeScreenshot("mcdrone-navigate-obstacles");

			JsonObject caveReset = msg("reset");
			caveReset.addProperty("id", id);
			caveReset.addProperty("seed", 9);
			JsonObject caveOptions = new JsonObject();
			caveOptions.addProperty("obstacles", 4);
			caveOptions.addProperty("terrain", "cave");
			caveReset.add("options", caveOptions);
			bridge.send(caveReset);
			obs = awaitObs(ctx, bridge, id++);
			JsonObject caveArena = obs.header().getAsJsonObject("state").getAsJsonObject("arena");
			check(caveArena.get("terrain").getAsString().equals("cave"), "expected a cave arena, got " + caveArena.get("terrain"));
			check(caveArena.getAsJsonArray("stalactites").size() >= 4, "expected at least 4 stalactites, got " + caveArena.get("stalactites"));
			checkArena(obs.header().getAsJsonObject("state"), 4);
			ctx.takeScreenshot("mcdrone-navigate-cave");

			JsonObject buildReset = msg("reset");
			buildReset.addProperty("id", id);
			buildReset.addProperty("seed", 11);
			JsonObject buildOptions = new JsonObject();
			buildOptions.addProperty("task", "replicate_build");
			buildReset.add("options", buildOptions);
			bridge.send(buildReset);
			obs = awaitObs(ctx, bridge, id++);
			JsonObject buildState = obs.header().getAsJsonObject("state");
			JsonArray blueprint = buildState.getAsJsonObject("arena").getAsJsonArray("blueprint");
			check(blueprint.size() >= 3, "expected a blueprint of at least 3 blocks, got " + blueprint);
			for (var cell : blueprint) {
				String block = cell.getAsJsonObject().get("block").getAsString();
				long needed = blueprint.asList().stream().filter(c -> c.getAsJsonObject().get("block").getAsString().equals(block)).count();
				check(countItem(buildState, block) >= needed, "drone carries too few " + block + " for the blueprint");
			}
			ctx.takeScreenshot("mcdrone-replicate-build");

			// a copy job over the same reference build, then the source saved and rebuilt from the schematic
			JsonObject arenaJson = buildState.getAsJsonObject("arena");
			JsonArray ref = arenaJson.getAsJsonArray("referenceBase");
			JsonArray site = arenaJson.getAsJsonArray("buildBase");
			int rx = ref.get(0).getAsInt();
			int ry = ref.get(1).getAsInt();
			int rz = ref.get(2).getAsInt();
			JsonArray source = ints(rx - 1, ry + 1, rz - 1, rx + 1, ry + 3, rz + 1);
			JsonArray dest = ints(site.get(0).getAsInt() - 1, site.get(1).getAsInt() + 1, site.get(2).getAsInt() - 1);
			JsonObject copyReset = msg("reset");
			copyReset.addProperty("id", id);
			JsonObject copyOptions = new JsonObject();
			copyOptions.addProperty("task", "copy_region");
			copyOptions.add("source", source);
			copyOptions.add("dest", dest);
			copyReset.add("options", copyOptions);
			bridge.send(copyReset);
			obs = awaitObs(ctx, bridge, id++);
			JsonObject job = obs.header().getAsJsonObject("state").getAsJsonObject("job");
			check(job != null && job.get("kind").getAsString().equals("copy"), "expected a copy job in the state, got " + job);
			JsonArray metrics = obs.header().getAsJsonObject("episode").getAsJsonArray("metrics");
			check(metrics.size() == 3 && metrics.get(1).getAsInt() == blueprint.size() && metrics.get(0).getAsInt() == 0, "copy job metrics " + metrics);

			JsonObject select = msg("select");
			select.add("cornerA", ints(rx - 1, ry + 1, rz - 1));
			select.add("cornerB", ints(rx + 1, ry + 3, rz + 1));
			bridge.send(select);
			JsonObject export = msg("export");
			export.addProperty("name", "gametest-copy");
			bridge.send(export);
			Path exported = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("gametest-copy.schem");
			ctx.waitFor(mc -> Files.exists(exported), 100);
			JsonObject buildJob = msg("reset");
			buildJob.addProperty("id", id);
			JsonObject buildJobOptions = new JsonObject();
			buildJobOptions.addProperty("task", "build_schematic");
			buildJobOptions.addProperty("schematic", "gametest-copy.schem");
			buildJobOptions.add("dest", dest);
			buildJob.add("options", buildJobOptions);
			bridge.send(buildJob);
			obs = awaitObs(ctx, bridge, id++);
			JsonObject schemJob = obs.header().getAsJsonObject("state").getAsJsonObject("job");
			check(schemJob != null && schemJob.get("kind").getAsString().equals("build"), "expected a build job, got " + schemJob);
			metrics = obs.header().getAsJsonObject("episode").getAsJsonArray("metrics");
			check(metrics.get(1).getAsInt() == blueprint.size(), "schematic round trip lost blocks, metrics " + metrics);

			// the same copy mining its materials from a box under the reference build, which the job hands the drone as gather
			JsonArray gather = ints(rx - 1, ry - 6, rz - 1, rx + 1, ry - 4, rz + 1);
			JsonObject gatherReset = msg("reset");
			gatherReset.addProperty("id", id);
			JsonObject gatherOptions = copyOptions.deepCopy();
			gatherOptions.add("gather", gather);
			gatherReset.add("options", gatherOptions);
			bridge.send(gatherReset);
			obs = awaitObs(ctx, bridge, id++);
			JsonObject gatherJob = obs.header().getAsJsonObject("state").getAsJsonObject("job");
			check(gatherJob != null && gather.equals(gatherJob.get("gather")), "expected the copy job to carry the gather box, got " + gatherJob);

			JsonObject digReset = msg("reset");
			digReset.addProperty("id", id);
			digReset.addProperty("seed", 5);
			JsonObject digOptions = new JsonObject();
			digOptions.addProperty("task", "dig_block");
			digReset.add("options", digOptions);
			bridge.send(digReset);
			obs = awaitObs(ctx, bridge, id++);

			// a safe region over the ore keeps the drone from breaking it, removing the region lets it through
			JsonArray ore = obs.header().getAsJsonObject("state").getAsJsonObject("arena").getAsJsonArray("targets").get(0).getAsJsonArray();
			JsonObject guard = msg("select");
			guard.add("cornerA", ore);
			guard.add("cornerB", ore);
			bridge.send(guard);
			JsonObject saveGuard = msg("region_save");
			saveGuard.addProperty("name", "gametest guard");
			saveGuard.addProperty("purpose", "safe");
			bridge.send(saveGuard);
			ctx.waitFor(mc -> runtime.regions().all().size() == 1, 100);
			boolean refused = false;
			for (int i = 0; i < 150 && !refused; i++) {
				JsonObject step = msg("step");
				step.addProperty("id", id);
				step.add("action", digAction(obs.header().getAsJsonObject("state")));
				bridge.send(step);
				obs = awaitObs(ctx, bridge, id++);
				check(!obs.header().getAsJsonObject("episode").get("success").getAsBoolean(), "the drone broke ore inside a safe region");
				for (var e : obs.header().getAsJsonObject("state").getAsJsonArray("events")) {
					refused |= e.getAsJsonObject().get("type").getAsString().equals("break_failed");
				}
			}
			check(refused, "no break_failed event while mining inside a safe region");
			JsonObject dropGuard = msg("region_delete");
			dropGuard.addProperty("region", runtime.regions().all().get(0).getAsJsonObject().get("id").getAsString());
			bridge.send(dropGuard);
			ctx.waitFor(mc -> runtime.regions().all().isEmpty(), 100);

			boolean dug = false;
			for (int i = 0; i < 400 && !dug; i++) {
				JsonObject step = msg("step");
				step.addProperty("id", id);
				step.add("action", digAction(obs.header().getAsJsonObject("state")));
				bridge.send(step);
				obs = awaitObs(ctx, bridge, id++);
				dug = obs.header().getAsJsonObject("episode").get("success").getAsBoolean();
				if (i == 30) {
					ctx.takeScreenshot("mcdrone-dig-mining");
				}
			}
			check(dug, "scripted miner never broke the coal ore");
			check(countItem(obs.header().getAsJsonObject("state"), "minecraft:coal") >= 1, "no coal in the drone inventory after mining");
			ctx.takeScreenshot("mcdrone-dig-done");

			// the same arena rebuilt, then mined as a player's mine_region job over a box around the ore
			JsonObject digAgain = msg("reset");
			digAgain.addProperty("id", id);
			digAgain.addProperty("seed", 5);
			digAgain.add("options", digOptions);
			bridge.send(digAgain);
			obs = awaitObs(ctx, bridge, id++);
			int ox = ore.get(0).getAsInt();
			int oy = ore.get(1).getAsInt();
			int oz = ore.get(2).getAsInt();
			JsonObject mineJob = msg("reset");
			mineJob.addProperty("id", id);
			JsonObject mineOptions = new JsonObject();
			mineOptions.addProperty("task", "mine_region");
			// two kinds, only coal is in the box, so the job is done once the ore is out
			mineOptions.addProperty("blocks", "coal_ore, minecraft:iron_ore");
			mineOptions.add("region", ints(ox - 1, oy - 1, oz - 1, ox + 1, oy + 1, oz + 1));
			mineJob.add("options", mineOptions);
			bridge.send(mineJob);
			obs = awaitObs(ctx, bridge, id++);
			JsonObject mine = obs.header().getAsJsonObject("state").getAsJsonObject("job");
			check(mine != null && mine.get("kind").getAsString().equals("mine"), "expected a mine job, got " + mine);
			check(mine.getAsJsonArray("blocks").size() == 2, "expected both kinds in the mine job, got " + mine);
			boolean mined = false;
			for (int i = 0; i < 400 && !mined; i++) {
				JsonObject step = msg("step");
				step.addProperty("id", id);
				step.add("action", digActionAt(obs.header().getAsJsonObject("state"), ore));
				bridge.send(step);
				obs = awaitObs(ctx, bridge, id++);
				mined = obs.header().getAsJsonObject("episode").get("success").getAsBoolean();
			}
			check(mined, "mine_region job never finished, metrics " + obs.header().getAsJsonObject("episode").get("metrics"));

			JsonObject farmReset = msg("reset");
			farmReset.addProperty("id", id);
			farmReset.addProperty("seed", 13);
			JsonObject farmOptions = new JsonObject();
			farmOptions.addProperty("task", "harvest_crops");
			farmReset.add("options", farmOptions);
			bridge.send(farmReset);
			obs = awaitObs(ctx, bridge, id++);
			JsonObject farm = obs.header().getAsJsonObject("state").getAsJsonObject("job");
			JsonArray farmMetrics = obs.header().getAsJsonObject("episode").getAsJsonArray("metrics");
			check(farm != null && farm.get("kind").getAsString().equals("harvest"), "expected a harvest job, got " + farm);
			check(farmMetrics.get(0).getAsInt() >= 2 && farmMetrics.get(3).getAsInt() >= 1 && farmMetrics.get(4).getAsInt() >= 20, "farm metrics " + farmMetrics);
			check(countItem(obs.header().getAsJsonObject("state"), "minecraft:wheat_seeds") == 4, "the drone should start with 4 wheat seeds");
			ctx.takeScreenshot("mcdrone-harvest-crops");

			ctx.setScreen(() -> new JobScreen(runtime));
			ctx.waitTicks(5);
			ctx.takeScreenshot("mcdrone-job-screen");
			ctx.setScreen(() -> null);

			// a player using the drone opens its inventory as a chest and can put items in
			String chestProblem = world.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				DroneEntity drone = Drones.active(player);
				if (drone == null) {
					return "no drone";
				}
				drone.interact(player, InteractionHand.MAIN_HAND, drone.position());
				if (!(player.containerMenu instanceof ChestMenu menu) || menu.getContainer() != drone.inventory) {
					return "using the drone opened " + player.containerMenu + ", not its chest";
				}
				int before = drone.inventory.countItem(Items.DIRT);
				player.getInventory().setItem(0, new ItemStack(Items.DIRT, 5));
				// the first hotbar slot comes after the drone's 27 slots and the player's 27 main slots
				menu.quickMoveStack(player, 27 + 27);
				player.closeContainer();
				return drone.inventory.countItem(Items.DIRT) == before + 5 ? "" : "the dirt didn't move into the drone";
			});
			check(chestProblem.isEmpty(), chestProblem);

			// the nametag reads "name (owner)", renamed over the bridge and then with a name tag
			JsonObject rename = msg("rename");
			rename.addProperty("name", "Harvester");
			bridge.send(rename);
			ctx.waitTicks(5);
			String nameProblem = world.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				DroneEntity drone = Drones.active(player);
				String owner = player.getName().getString();
				if (!drone.getCustomName().getString().equals("Harvester (" + owner + ")")) {
					return "bridge rename gave " + drone.getCustomName().getString();
				}
				ItemStack tag = new ItemStack(Items.NAME_TAG);
				tag.set(DataComponents.CUSTOM_NAME, Component.literal("Miner"));
				player.setItemInHand(InteractionHand.MAIN_HAND, tag);
				drone.interact(player, InteractionHand.MAIN_HAND, drone.position());
				player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				return drone.getCustomName().getString().equals("Miner (" + owner + ")") ? "" : "name tag rename gave " + drone.getCustomName().getString();
			});
			check(nameProblem.isEmpty(), nameProblem);

			// tiers: a second drone, crafted as iron, joins the player's drones
			// then a charging station beside the active drone becomes its home
			int[] station = world.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				DroneEntity drone = Drones.active(player);
				DroneEntity iron = DroneItem.spawnFor(player.level(), player, drone.position().add(0, 2, 0), 0.0F, DroneTier.IRON);
				boolean tiered = iron.tier() == DroneTier.IRON && iron.getItem().is(ModContent.droneItem(DroneTier.IRON)) && Drones.owned(player).size() >= 2;
				iron.discard();
				if (!tiered) {
					return null;
				}
				BlockPos pos = BlockPos.containing(drone.position()).offset(4, -1, 0);
				player.level().setBlock(pos, ModContent.CHARGING_STATION.defaultBlockState(), Block.UPDATE_ALL);
				player.level().setBlock(pos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				player.level().setBlock(pos.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				drone.setHome(pos);
				return new int[] {pos.getX(), pos.getY(), pos.getZ()};
			});
			check(station != null, "an iron drone didn't come out as iron");

			// return_home flies the drone onto its station, the flight drains the battery
			JsonObject home = msg("reset");
			home.addProperty("id", id);
			JsonObject homeOptions = new JsonObject();
			homeOptions.addProperty("task", "return_home");
			home.add("options", homeOptions);
			bridge.send(home);
			obs = awaitObs(ctx, bridge, id++);
			JsonObject homeState = obs.header().getAsJsonObject("state");
			check(homeState.getAsJsonObject("job").get("kind").getAsString().equals("return_home"), "expected a return_home job, got " + homeState.get("job"));
			check(homeState.getAsJsonObject("battery").get("home").equals(ints(station)), "battery home " + homeState.getAsJsonObject("battery").get("home"));
			double flownFrom = homeState.getAsJsonObject("battery").get("charge").getAsDouble();
			boolean docked = false;
			for (int i = 0; i < 300 && !docked; i++) {
				JsonObject step = msg("step");
				step.addProperty("id", id);
				step.add("action", dockAction(obs.header().getAsJsonObject("state"), station));
				bridge.send(step);
				obs = awaitObs(ctx, bridge, id++);
				docked = obs.header().getAsJsonObject("episode").get("success").getAsBoolean();
			}
			check(docked, "return_home never docked, drone at " + obs.header().getAsJsonObject("state").get("pos"));
			JsonObject dockedBattery = obs.header().getAsJsonObject("state").getAsJsonObject("battery");
			check(dockedBattery.get("docked").getAsBoolean(), "the episode ended but the drone isn't docked");
			check(dockedBattery.get("charge").getAsDouble() < flownFrom, "flying home didn't use any charge, " + flownFrom + " to " + dockedBattery.get("charge"));

			// docked, every pose charges, lockstep freezes server ticks so poses drive the battery.
			// Poses without a tool request don't wait for the server, so let it catch up to the docked pose first
			for (int i = 0; i < 100 && !world.getServer().computeOnServer(server -> Drones.active(server.getPlayerList().getPlayers().getFirst()).docked()); i++) {
				ctx.waitTick();
			}
			String chargeProblem = world.getServer().computeOnServer(server -> {
				DroneEntity drone = Drones.active(server.getPlayerList().getPlayers().getFirst());
				for (int i = 0; i < 50; i++) {
					drone.spendBreak();
				}
				float before = drone.charge();
				for (int i = 0; i < 100; i++) {
					drone.onPose(drone.getX(), drone.getY(), drone.getZ(), drone.getYRot(), drone.getXRot());
				}
				return drone.charge() > before ? "" : "docked poses didn't charge, " + before + " to " + drone.charge() + ", drone at " + drone.position()
					+ ", home " + drone.home() + " holds " + drone.level().getBlockState(drone.home()) + ", docked " + drone.docked();
			});
			check(chargeProblem.isEmpty(), chargeProblem);

			// a queued job keeps its label, the dashboard sees it, and Run queue starts it and takes it off the queue
			JsonObject queued = new JsonObject();
			queued.addProperty("task", "return_home");
			String queueProblem = ctx.computeOnClient(mc -> runtime.queueJob(queued));
			check(queueProblem == null, "queueing failed: " + queueProblem);
			ctx.waitFor(mc -> runtime.controller().drone() != null && runtime.controller().drone().queue().size() == 1, 100);
			JsonArray drones = ctx.computeOnClient(mc -> runtime.statusJson().getAsJsonArray("drones"));
			boolean listed = false;
			for (var d : drones) {
				JsonArray queue = d.getAsJsonObject().getAsJsonArray("queue");
				listed |= queue.size() == 1 && queue.get(0).getAsJsonObject().get("label").getAsString().equals("return home");
			}
			check(listed, "the status doesn't list the queued job: " + drones);
			String runProblem = ctx.computeOnClient(mc -> runtime.runQueue());
			check(runProblem == null, "Run queue failed: " + runProblem);
			ctx.waitFor(mc -> runtime.controller().drone().queue().isEmpty() && runtime.task().active() && runtime.task().kind() == TaskKind.RETURN_HOME, 200);
			ctx.takeScreenshot("mcdrone-docked");

			// several drones at once: a second drone gets its own controller and a mine job, a job on the same blocks is
			// refused, and both drones step in turn with every frame from the drone that asked for it
			int[] scout = world.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				DroneEntity drone = Drones.active(player);
				ServerLevel level = player.level();
				BlockPos column = BlockPos.containing(drone.position()).offset(-8, 0, 0);
				BlockPos coal = new BlockPos(column.getX(), level.getHeight(Heightmap.Types.WORLD_SURFACE, column.getX(), column.getZ()) - 1, column.getZ());
				level.setBlock(coal, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
				DroneEntity second = DroneItem.spawnFor(level, player, Vec3.atCenterOf(coal.above(3)), 0.0F, DroneTier.IRON);
				Drones.setActive(player, drone);
				return new int[] {second.getId(), coal.getX(), coal.getY(), coal.getZ()};
			});
			ctx.waitFor(mc -> mc.level.getEntity(scout[0]) instanceof DroneEntity, 100);
			BridgeTestClient second = new BridgeTestClient();
			try {
				second.connect(PORT);
			} catch (Exception e) {
				throw new AssertionError("second bridge client couldn't connect", e);
			}
			JsonObject secondHello = msg("hello");
			secondHello.addProperty("role", "controller");
			secondHello.addProperty("schema", ClientRuntime.SCHEMA);
			secondHello.addProperty("client", "gametest-scout");
			secondHello.addProperty("drone", scout[0]);
			second.send(secondHello);
			JsonObject secondWelcome = awaitText(ctx, second, "welcome");
			check(secondWelcome.get("role").getAsString().equals("controller") && secondWelcome.get("drone").getAsInt() == scout[0], "the scout's controller got " + secondWelcome);

			JsonArray scoutOre = ints(scout[1], scout[2], scout[3]);
			JsonObject scoutOptions = new JsonObject();
			scoutOptions.addProperty("task", "mine_region");
			scoutOptions.addProperty("blocks", "coal_ore");
			scoutOptions.add("region", ints(scout[1] - 1, scout[2] - 1, scout[3] - 1, scout[1] + 1, scout[2] + 1, scout[3] + 1));
			JsonObject scoutJob = msg("reset");
			scoutJob.addProperty("id", id);
			scoutJob.add("options", scoutOptions);
			second.send(scoutJob);
			BridgeTestClient.Obs scoutObs = awaitObs(ctx, second, id++);
			check(droneId(scoutObs) == scout[0], "the scout's reset answered with drone " + droneId(scoutObs));

			JsonObject clash = msg("reset");
			clash.addProperty("id", id);
			clash.add("options", scoutOptions);
			bridge.send(clash);
			String clashError = awaitError(ctx, bridge, id++);
			check(clashError.contains("is working there"), "a job on the scout's blocks wasn't refused: " + clashError);

			JsonObject homeAgain = msg("reset");
			homeAgain.addProperty("id", id);
			homeAgain.add("options", homeOptions);
			bridge.send(homeAgain);
			obs = awaitObs(ctx, bridge, id++);
			int mainDrone = droneId(obs);
			check(mainDrone != scout[0], "both controllers fly drone " + mainDrone);
			boolean scoutDone = false;
			boolean mainDone = false;
			for (int i = 0; i < 400 && !scoutDone; i++) {
				if (!mainDone) {
					JsonObject step = msg("step");
					step.addProperty("id", id);
					step.add("action", dockAction(obs.header().getAsJsonObject("state"), station));
					bridge.send(step);
					obs = awaitObs(ctx, bridge, id++);
					check(droneId(obs) == mainDrone, "a step of drone " + mainDrone + " answered with drone " + droneId(obs));
					mainDone = obs.header().getAsJsonObject("episode").get("success").getAsBoolean();
				}
				JsonObject step = msg("step");
				step.addProperty("id", id);
				step.add("action", digActionAt(scoutObs.header().getAsJsonObject("state"), scoutOre));
				second.send(step);
				scoutObs = awaitObs(ctx, second, id++);
				check(droneId(scoutObs) == scout[0], "a step of the scout answered with drone " + droneId(scoutObs));
				scoutDone = scoutObs.header().getAsJsonObject("episode").get("success").getAsBoolean();
			}
			check(scoutDone, "the scout never mined its ore, metrics " + scoutObs.header().getAsJsonObject("episode").get("metrics"));
			checkFrame(scoutObs);
			String scoutController = ctx.computeOnClient(mc -> {
				for (var d : runtime.statusJson().getAsJsonArray("drones")) {
					if (d.getAsJsonObject().get("id").getAsInt() == scout[0]) {
						return d.getAsJsonObject().get("controller").getAsString();
					}
				}
				return "missing";
			});
			check(scoutController.equals("gametest-scout"), "the status lists the scout's controller as " + scoutController);

			// drones are solid to each other: the scout's path into the main drone runs into its box
			boolean bumps = ctx.computeOnClient(mc -> {
				DroneEntity scoutDrone = (DroneEntity) mc.level.getEntity(scout[0]);
				DroneEntity main = (DroneEntity) mc.level.getEntity(mainDrone);
				Vec3 toward = main.position().subtract(scoutDrone.position());
				return !mc.level.getEntityCollisions(scoutDrone, scoutDrone.getBoundingBox().expandTowards(toward)).isEmpty();
			});
			check(bumps, "the scout's path into the main drone found nothing to collide with");

			// the raycaster skips all-air sections, it has to find what Level.clip finds
			String clipProblem = ctx.computeOnClient(mc -> {
				Vec3 eye = mc.level.getEntity(mainDrone).getEyePosition();
				Random rays = new Random(7);
				for (int i = 0; i < 2000; i++) {
					Vec3 to = eye.add(new Vec3(rays.nextGaussian(), rays.nextGaussian(), rays.nextGaussian()).normalize().scale(64.0));
					BlockHitResult fast = Raycaster.clip(mc.level, eye, to);
					BlockHitResult vanilla = mc.level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, CollisionContext.empty()));
					boolean same = vanilla.getType() == HitResult.Type.MISS
						? fast == null
						: fast != null && fast.getBlockPos().equals(vanilla.getBlockPos()) && fast.getLocation().distanceTo(vanilla.getLocation()) < 1.0E-6;
					if (!same) {
						return "ray to " + to + " hit " + (fast == null ? "nothing" : fast.getBlockPos()) + ", Level.clip hit " + vanilla.getType() + " " + vanilla.getBlockPos();
					}
				}
				return "";
			});
			check(clipProblem.isEmpty(), clipProblem);
			ctx.takeScreenshot("mcdrone-two-drones");
			second.send(msg("release"));
			ctx.waitTicks(10);
			second.close();

			// a fleet: two drones with different tasks share one arena, and the reset that comes first waits for the other
			BridgeTestClient scoutAgain = new BridgeTestClient();
			try {
				scoutAgain.connect(PORT);
			} catch (Exception e) {
				throw new AssertionError("the scout's controller couldn't reconnect", e);
			}
			scoutAgain.send(secondHello);
			awaitText(ctx, scoutAgain, "welcome");
			int scoutReset = id++;
			scoutAgain.send(fleetReset(scoutReset, "dig_block", 1));
			ctx.waitTicks(20);
			check(scoutAgain.frames.isEmpty(), "a fleet reset was answered before the whole fleet asked");
			int mainReset = id++;
			bridge.send(fleetReset(mainReset, "navigate_to", 0));
			BridgeTestClient.Obs mainFleet = awaitObs(ctx, bridge, mainReset);
			BridgeTestClient.Obs scoutFleet = awaitObs(ctx, scoutAgain, scoutReset);
			JsonObject mainArena = mainFleet.header().getAsJsonObject("state").getAsJsonObject("arena");
			JsonObject scoutArena = scoutFleet.header().getAsJsonObject("state").getAsJsonObject("arena");
			check(droneId(mainFleet) == mainDrone && droneId(scoutFleet) == scout[0], "the fleet answered with drones " + droneId(mainFleet) + " and " + droneId(scoutFleet));
			check(mainArena.get("origin").equals(scoutArena.get("origin")) && mainArena.get("radius").equals(scoutArena.get("radius")), "the fleet got two arenas, " + mainArena.get("origin") + " and " + scoutArena.get("origin"));
			check(mainArena.get("task").getAsString().equals("navigate_to") && scoutArena.get("task").getAsString().equals("dig_block"), "the fleet's tasks got mixed up");
			check(mainArena.get("radius").getAsInt() > 12, "the shared arena didn't grow for two drones, radius " + mainArena.get("radius"));
			for (BridgeTestClient client : new BridgeTestClient[] {bridge, scoutAgain}) {
				JsonObject step = msg("step");
				step.addProperty("id", id);
				client.send(step);
				awaitObs(ctx, client, id++);
			}
			scoutAgain.send(msg("release"));
			ctx.waitTicks(10);
			scoutAgain.close();
			// a third drone, so the held world can run three-drone fleets for the training package
			world.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				DroneEntity main = Drones.active(player);
				DroneItem.spawnFor(player.level(), player, main.position().add(3.0, 0.0, 0.0), 0.0F, DroneTier.COPPER);
				Drones.setActive(player, main);
				return null;
			});

			JsonObject stop = msg("record");
			stop.addProperty("on", false);
			bridge.send(stop);
			ctx.waitFor(mc -> !runtime.recorder().armed(), 100);
			JsonObject release = msg("release");
			bridge.send(release);
			ctx.waitTicks(20);
			bridge.close();

			Path data = testData.resolve("navigate_to");
			ctx.waitFor(mc -> runtime.recorder().queueDepth() == 0, 200);
			check(Files.isDirectory(data), "no recordings in " + data);
			check(!runtime.recorder().dataDir().equals(testData), "recording stayed on the test folder after it stopped");

			// with BlueMap installed its web app gets the drones' positions every second and the script that moves their markers
			if (FabricLoader.getInstance().isModLoaded("bluemap")) {
				Path script = FabricLoader.getInstance().getGameDir().resolve("bluemap/web/mcdrone/drones.js");
				ctx.waitFor(mc -> Files.exists(script) && Files.exists(script.resolveSibling("drones.json")), 600);
			}

			holdForExternalDriver(ctx);
		}
	}

	/** Leaves the world up for the training package's end-to-end tests when -Pe2eHold is set */
	private static void holdForExternalDriver(ClientGameTestContext ctx) {
		int seconds = Integer.getInteger("mcdrone.e2eHoldSeconds", 0);
		if (seconds <= 0) {
			return;
		}
		Path done = FabricLoader.getInstance().getGameDir().resolve("e2e-done");
		try {
			Files.deleteIfExists(done);
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		long deadline = System.currentTimeMillis() + seconds * 1000L;
		ctx.waitFor(mc -> Files.exists(done) || System.currentTimeMillis() > deadline, ClientGameTestContext.NO_TIMEOUT);
		ctx.takeScreenshot("mcdrone-after-external");
	}

	private static void checkArena(JsonObject state, int expected) {
		JsonArray pillars = state.getAsJsonObject("arena").getAsJsonArray("obstacles");
		check(pillars.size() == expected, "expected " + expected + " pillars, got " + pillars);
		JsonArray marker = state.getAsJsonArray("marker");
		double mx = marker.get(0).getAsInt() + 0.5;
		double mz = marker.get(2).getAsInt() + 0.5;
		for (var p : pillars) {
			JsonArray pillar = p.getAsJsonArray();
			int x = pillar.get(0).getAsInt();
			int z = pillar.get(1).getAsInt();
			int w = pillar.get(2).getAsInt();
			double nx = Math.clamp(mx, x, x + w);
			double nz = Math.clamp(mz, z, z + w);
			check(Math.hypot(mx - nx, mz - nz) >= 2.0, "pillar " + pillar + " crowds the marker " + marker);
		}
	}

	// flies to about 2.5 blocks from the ore, aims at its center, and holds break
	private static JsonObject digAction(JsonObject state) {
		return digActionAt(state, state.getAsJsonObject("arena").getAsJsonArray("targets").get(0).getAsJsonArray());
	}

	private static JsonObject digActionAt(JsonObject state, JsonArray target) {
		JsonArray pos = state.getAsJsonArray("pos");
		double ex = pos.get(0).getAsDouble();
		double ey = pos.get(1).getAsDouble() + 0.34;
		double ez = pos.get(2).getAsDouble();
		double dx = target.get(0).getAsInt() + 0.5 - ex;
		double dy = target.get(1).getAsInt() + 0.5 - ey;
		double dz = target.get(2).getAsInt() + 0.5 - ez;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		double yawError = wrap(Math.toDegrees(Math.atan2(-dx, dz)) - state.get("yaw").getAsDouble());
		double pitchError = -Math.toDegrees(Math.atan2(dy, horizontal)) - state.get("pitch").getAsDouble();
		boolean aimed = Math.abs(yawError) < 4 && Math.abs(pitchError) < 4;
		JsonObject action = new JsonObject();
		JsonArray move = new JsonArray();
		move.add(Math.abs(yawError) < 30 && horizontal > 2.5 ? Math.min(1.0, (horizontal - 2.5) / 2.0) : 0.0);
		move.add(0.0);
		move.add(Math.max(-1.0, Math.min(1.0, dy + 1.0)));
		JsonArray look = new JsonArray();
		look.add(Math.max(-15.0, Math.min(15.0, yawError)));
		look.add(Math.max(-15.0, Math.min(15.0, pitchError)));
		action.add("move", move);
		action.add("look", look);
		action.addProperty("tool", aimed && horizontal <= 3.5 ? "break" : "none");
		return action;
	}

	private static int countItem(JsonObject state, String item) {
		int total = 0;
		for (var slot : state.getAsJsonArray("inventory")) {
			if (slot.getAsJsonArray().get(0).getAsString().equals(item)) {
				total += slot.getAsJsonArray().get(1).getAsInt();
			}
		}
		return total;
	}

	// flies to a little above the station's top, slowing as it closes in so it stops inside the dock
	private static JsonObject dockAction(JsonObject state, int[] station) {
		JsonArray pos = state.getAsJsonArray("pos");
		double dx = station[0] + 0.5 - pos.get(0).getAsDouble();
		double dy = station[1] + 1.3 - pos.get(1).getAsDouble();
		double dz = station[2] + 0.5 - pos.get(2).getAsDouble();
		double yaw = Math.toRadians(state.get("yaw").getAsDouble());
		double forward = dx * -Math.sin(yaw) + dz * Math.cos(yaw);
		double right = dx * -Math.cos(yaw) + dz * -Math.sin(yaw);
		JsonObject action = new JsonObject();
		JsonArray move = new JsonArray();
		move.add(Math.max(-1.0, Math.min(1.0, forward)));
		move.add(Math.max(-1.0, Math.min(1.0, right)));
		move.add(Math.max(-1.0, Math.min(1.0, dy)));
		JsonArray look = new JsonArray();
		look.add(0.0);
		look.add(0.0);
		action.add("move", move);
		action.add("look", look);
		return action;
	}

	private static JsonObject scriptedAction(JsonObject state) {
		JsonArray pos = state.getAsJsonArray("pos");
		JsonArray marker = state.getAsJsonArray("marker");
		double dx = marker.get(0).getAsDouble() + 0.5 - pos.get(0).getAsDouble();
		double dy = marker.get(1).getAsDouble() + 0.5 - (pos.get(1).getAsDouble() + 0.2);
		double dz = marker.get(2).getAsDouble() + 0.5 - pos.get(2).getAsDouble();
		double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
		double yawError = wrap(targetYaw - state.get("yaw").getAsDouble());
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		JsonObject action = new JsonObject();
		JsonArray move = new JsonArray();
		move.add(Math.abs(yawError) < 30 ? Math.min(1.0, horizontal / 2.0) : 0.0);
		move.add(0.0);
		move.add(Math.max(-1.0, Math.min(1.0, dy)));
		JsonArray look = new JsonArray();
		look.add(Math.max(-15.0, Math.min(15.0, yawError)));
		look.add(0.0);
		action.add("move", move);
		action.add("look", look);
		return action;
	}

	private static double wrap(double degrees) {
		double d = degrees % 360.0;
		if (d >= 180.0) {
			d -= 360.0;
		}
		if (d < -180.0) {
			d += 360.0;
		}
		return d;
	}

	private static void checkFrame(BridgeTestClient.Obs obs) {
		JsonObject streams = obs.header().getAsJsonObject("streams");
		check(streams.has("rgb") && streams.has("depth") && streams.has("mask"), "missing streams: " + streams);
		JsonObject rgb = streams.getAsJsonObject("rgb");
		int offset = rgb.get("offset").getAsInt();
		int length = rgb.get("length").getAsInt();
		int min = 255;
		int max = 0;
		for (int i = offset; i < offset + length; i++) {
			int v = obs.payload()[i] & 0xFF;
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		check(max - min > 40, "rgb frame looks blank, range " + min + ".." + max);
		JsonObject mask = streams.getAsJsonObject("mask");
		boolean anyBlock = false;
		for (int i = mask.get("offset").getAsInt(); i < mask.get("offset").getAsInt() + mask.get("length").getAsInt(); i += 2) {
			if (obs.payload()[i] != 0 || obs.payload()[i + 1] != 0) {
				anyBlock = true;
				break;
			}
		}
		check(anyBlock, "mask is all sky");
	}

	private static JsonObject awaitText(ClientGameTestContext ctx, BridgeTestClient bridge, String type) {
		JsonObject[] found = new JsonObject[1];
		ctx.waitFor(mc -> {
			JsonObject next;
			while ((next = bridge.texts.poll()) != null) {
				if (next.get("type").getAsString().equals("error")) {
					throw new AssertionError("bridge error: " + next);
				}
				if (next.get("type").getAsString().equals(type)) {
					found[0] = next;
					return true;
				}
			}
			return false;
		}, 200);
		return found[0];
	}

	private static BridgeTestClient.Obs awaitObs(ClientGameTestContext ctx, BridgeTestClient bridge, int replyTo) {
		BridgeTestClient.Obs[] found = new BridgeTestClient.Obs[1];
		ctx.waitFor(mc -> {
			JsonObject text;
			while ((text = bridge.texts.poll()) != null) {
				if (text.get("type").getAsString().equals("error")) {
					throw new AssertionError("bridge error: " + text);
				}
			}
			BridgeTestClient.Obs next;
			while ((next = bridge.frames.poll()) != null) {
				var r = next.header().get("replyTo");
				if (r != null && !r.isJsonNull() && r.getAsInt() == replyTo) {
					found[0] = next;
					return true;
				}
			}
			return false;
		}, 400);
		return found[0];
	}

	private static int droneId(BridgeTestClient.Obs obs) {
		return obs.header().getAsJsonObject("state").get("droneId").getAsInt();
	}

	/** The message of the error answering request replyTo */
	private static String awaitError(ClientGameTestContext ctx, BridgeTestClient bridge, int replyTo) {
		String[] found = new String[1];
		ctx.waitFor(mc -> {
			JsonObject next;
			while ((next = bridge.texts.poll()) != null) {
				var r = next.get("replyTo");
				if (next.get("type").getAsString().equals("error") && r != null && !r.isJsonNull() && r.getAsInt() == replyTo) {
					found[0] = next.get("message").getAsString();
					return true;
				}
			}
			return false;
		}, 200);
		return found[0];
	}

	private static JsonArray ints(int... values) {
		JsonArray a = new JsonArray();
		for (int v : values) {
			a.add(v);
		}
		return a;
	}

	// a reset into the gametest's two-drone fleet
	private static JsonObject fleetReset(int id, String task, int member) {
		JsonObject reset = msg("reset");
		reset.addProperty("id", id);
		JsonObject options = new JsonObject();
		options.addProperty("task", task);
		JsonObject fleet = new JsonObject();
		fleet.addProperty("group", "gametest");
		fleet.addProperty("size", 2);
		fleet.addProperty("member", member);
		options.add("fleet", fleet);
		reset.add("options", options);
		return reset;
	}

	private static JsonObject msg(String type) {
		JsonObject json = new JsonObject();
		json.addProperty("type", type);
		return json;
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
