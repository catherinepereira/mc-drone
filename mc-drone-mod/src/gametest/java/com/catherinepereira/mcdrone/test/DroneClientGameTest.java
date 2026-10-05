package com.catherinepereira.mcdrone.test;

import com.catherinepereira.mcdrone.client.ClientRuntime;
import com.catherinepereira.mcdrone.client.McDroneClient;
import com.catherinepereira.mcdrone.client.hud.JobScreen;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.InactivityFpsLimit;

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
			JsonObject record = msg("record");
			record.addProperty("on", true);
			bridge.send(record);
			ctx.waitTicks(2);

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
			ctx.waitFor(mc -> runtime.regions().size() == 1, 100);
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
			dropGuard.addProperty("region", runtime.regions().get(0).getAsJsonObject().get("id").getAsString());
			bridge.send(dropGuard);
			ctx.waitFor(mc -> runtime.regions().isEmpty(), 100);

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
			mineOptions.addProperty("block", "minecraft:coal_ore");
			mineOptions.add("region", ints(ox - 1, oy - 1, oz - 1, ox + 1, oy + 1, oz + 1));
			mineJob.add("options", mineOptions);
			bridge.send(mineJob);
			obs = awaitObs(ctx, bridge, id++);
			JsonObject mine = obs.header().getAsJsonObject("state").getAsJsonObject("job");
			check(mine != null && mine.get("kind").getAsString().equals("mine"), "expected a mine job, got " + mine);
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

			JsonObject release = msg("release");
			bridge.send(release);
			ctx.waitTicks(20);
			bridge.close();

			Path data = Path.of(System.getProperty("mcdrone.data", "data")).resolve("navigate_to");
			ctx.waitFor(mc -> runtime.recorder().queueDepth() == 0, 200);
			check(Files.isDirectory(data), "no recordings in " + data);

			holdForExternalDriver(ctx);
		}
	}

	/** Leaves the world up for mc-drone-py's end-to-end tests when -Pe2eHold is set */
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

	private static JsonArray ints(int... values) {
		JsonArray a = new JsonArray();
		for (int v : values) {
			a.add(v);
		}
		return a;
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
