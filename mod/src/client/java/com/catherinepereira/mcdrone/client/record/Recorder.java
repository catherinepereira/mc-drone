package com.catherinepereira.mcdrone.client.record;

import com.catherinepereira.mcdrone.client.ClientRuntime;
import com.catherinepereira.mcdrone.client.DroneAction;
import com.catherinepereira.mcdrone.client.DroneLog;
import com.catherinepereira.mcdrone.client.obs.Observation;
import com.catherinepereira.mcdrone.client.obs.Png;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import org.jspecify.annotations.Nullable;

/**
 * Writes episodes to data/<task>/<id>/, one at a time per drone, keyed by the drone's entity id.
 * Step n pairs the frame captured before action n with action n, and the reward that action earned,
 * so each row is written when the following frame arrives
 */
public final class Recorder {
	private final Path dataDir;
	private final Path testDir;
	private final DroneLog log;
	private final ThreadPoolExecutor io = (ThreadPoolExecutor) Executors.newFixedThreadPool(1, r -> {
		Thread t = new Thread(r, "mcdrone-recorder");
		t.setDaemon(true);
		return t;
	});

	private boolean armed;
	private boolean test;
	// appendLog runs on the bridge threads too
	private final Map<Integer, Episode> episodes = new ConcurrentHashMap<>();
	// drones whose latest episode saw an action that moved, turned, or used a tool
	private final Set<Integer> sawInput = new HashSet<>();

	/** Test recordings go to testDir, out of the training data */
	public Recorder(Path dataDir, Path testDir, DroneLog log) {
		this.dataDir = dataDir;
		this.testDir = testDir;
		this.log = log;
	}

	/** Where the next episode goes */
	public Path dataDir() {
		return this.test ? this.testDir : this.dataDir;
	}

	/** Whether any action since the drone's latest episode began moved it, turned it, or used a tool */
	public boolean sawInput(int drone) {
		return this.sawInput.contains(drone);
	}

	public boolean armed() {
		return this.armed;
	}

	public boolean recording() {
		return !this.episodes.isEmpty();
	}

	public boolean recording(int drone) {
		return this.episodes.containsKey(drone);
	}

	public int queueDepth() {
		return this.io.getQueue().size();
	}

	/** Recording starts with the next episode and covers every episode until disarmed */
	public void setArmed(boolean armed) {
		this.setArmed(armed, false);
	}

	public void setArmed(boolean armed, boolean test) {
		this.armed = armed;
		this.test = armed && test;
		if (!armed) {
			List.copyOf(this.episodes.keySet()).forEach(drone -> this.finish(drone, "stopped"));
		}
		this.log.info("record.armed", DroneLog.fields("on", armed, "test", this.test));
	}

	public void beginEpisode(int drone, String task, String id, JsonObject meta) {
		if (this.episodes.containsKey(drone)) {
			this.finish(drone, "replaced");
		}
		this.sawInput.remove(drone);
		if (!this.armed) {
			return;
		}
		Path dir = this.dataDir().resolve(task).resolve(id);
		Episode ep = new Episode(dir, meta);
		this.episodes.put(drone, ep);
		this.io.execute(() -> ep.open());
		this.log.info("record.begin", DroneLog.fields("dir", dir.toString()));
	}

	/** Remembers the action applied after the drone's latest frame */
	public void onAction(int drone, DroneAction action) {
		if (!action.idle()) {
			this.sawInput.add(drone);
		}
		Episode ep = this.episodes.get(drone);
		if (ep == null) {
			return;
		}
		if (ep.pendingAction != null) {
			ep.droppedFrames++;
		}
		ep.pendingAction = action;
	}

	public void onObservation(int drone, Observation obs) {
		Episode ep = this.episodes.get(drone);
		if (ep == null) {
			return;
		}
		if (ep.lastObs != null && ep.pendingAction != null) {
			this.writeStep(ep, ep.lastObs, ep.pendingAction, obs);
		}
		ep.pendingAction = null;
		ep.lastObs = obs;
		if (obs.done()) {
			this.writeStep(ep, obs, null, null);
			this.finish(drone, obs.episode.get("success").getAsBoolean() ? "success" : "timeout");
		}
	}

	private void writeStep(Episode ep, Observation frame, @Nullable DroneAction action, @Nullable Observation next) {
		int n = ep.steps++;
		JsonObject row = new JsonObject();
		row.addProperty("step", n);
		row.addProperty("tick", frame.tick);
		row.add("state", frame.state);
		row.add("action", action == null ? JsonNull.INSTANCE : action.toJson());
		row.addProperty("reward", next == null ? 0.0 : next.episode.get("reward").getAsDouble());
		row.addProperty("done", next == null || next.done());
		if (next != null) {
			ep.totalReward += next.episode.get("reward").getAsDouble();
		}
		String line = row.toString();
		this.io.execute(() -> ep.writeFrame(n, frame, line));
	}

	private void finish(int drone, String outcome) {
		Episode ep = this.episodes.remove(drone);
		if (ep == null) {
			return;
		}
		ep.meta.addProperty("outcome", outcome);
		ep.meta.addProperty("steps", ep.steps);
		ep.meta.addProperty("totalReward", ep.totalReward);
		ep.meta.addProperty("droppedFrames", ep.droppedFrames);
		this.io.execute(ep::close);
		this.log.info("record.end", DroneLog.fields("outcome", outcome, "steps", ep.steps, "droppedFrames", ep.droppedFrames));
	}

	private static final class Episode {
		final Path dir;
		final JsonObject meta;
		@Nullable Observation lastObs;
		@Nullable DroneAction pendingAction;
		int steps;
		int droppedFrames;
		double totalReward;
		private BufferedWriter stepsOut;

		Episode(Path dir, JsonObject meta) {
			this.dir = dir;
			this.meta = meta;
		}

		void open() {
			try {
				Files.createDirectories(this.dir.resolve("rgb"));
				Files.createDirectories(this.dir.resolve("depth"));
				Files.createDirectories(this.dir.resolve("mask"));
				Files.createDirectories(this.dir.resolve("state"));
				this.stepsOut = Files.newBufferedWriter(this.dir.resolve("steps.jsonl"), StandardCharsets.UTF_8);
				this.writeMeta();
			} catch (IOException e) {
				ClientRuntime.LOGGER.error("could not open episode {}", this.dir, e);
			}
		}

		void writeFrame(int n, Observation obs, String stepLine) {
			if (this.stepsOut == null) {
				return;
			}
			String name = String.format("%06d", n);
			try {
				if (obs.rgb != null) {
					Png.writeRgb(this.dir.resolve("rgb").resolve(name + ".png"), obs.rgb, obs.width, obs.height);
				}
				if (obs.depth != null) {
					ByteBuffer buf = ByteBuffer.allocate(obs.depth.length * 4).order(ByteOrder.LITTLE_ENDIAN);
					for (float v : obs.depth) {
						buf.putFloat(v);
					}
					Files.write(this.dir.resolve("depth").resolve(name + ".f32"), buf.array());
				}
				if (obs.mask != null) {
					Png.writeGray16(this.dir.resolve("mask").resolve(name + ".png"), obs.mask, obs.width, obs.height);
				}
				if (obs.blockStates != null) {
					Png.writeGray16(this.dir.resolve("state").resolve(name + ".png"), obs.blockStates, obs.width, obs.height);
				}
				this.stepsOut.write(stepLine);
				this.stepsOut.newLine();
				this.stepsOut.flush();
			} catch (IOException e) {
				ClientRuntime.LOGGER.error("frame write failed in {}", this.dir, e);
			}
		}

		void close() {
			try {
				this.writeMeta();
				if (this.stepsOut != null) {
					this.stepsOut.close();
				}
			} catch (IOException e) {
				ClientRuntime.LOGGER.error("could not close episode {}", this.dir, e);
			}
		}

		private void writeMeta() throws IOException {
			Files.writeString(this.dir.resolve("meta.json"), new GsonBuilder().setPrettyPrinting().create().toJson(this.meta), StandardCharsets.UTF_8);
		}
	}
}
