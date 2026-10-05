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
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import org.jspecify.annotations.Nullable;

/**
 * Writes episodes to data/<task>/<id>/.
 * Step n pairs the frame captured before action n with action n, and the reward that action earned,
 * so each row is written when the following frame arrives
 */
public final class Recorder {
	private final Path dataDir;
	private final DroneLog log;
	private final ThreadPoolExecutor io = (ThreadPoolExecutor) Executors.newFixedThreadPool(1, r -> {
		Thread t = new Thread(r, "mcdrone-recorder");
		t.setDaemon(true);
		return t;
	});

	private boolean armed;
	private @Nullable Episode episode;

	public Recorder(Path dataDir, DroneLog log) {
		this.dataDir = dataDir;
		this.log = log;
	}

	public Path dataDir() {
		return this.dataDir;
	}

	public boolean armed() {
		return this.armed;
	}

	public boolean recording() {
		return this.episode != null;
	}

	public int queueDepth() {
		return this.io.getQueue().size();
	}

	/** Recording starts with the next episode and covers every episode until disarmed */
	public void setArmed(boolean armed) {
		this.armed = armed;
		if (!armed && this.episode != null) {
			this.finish("stopped");
		}
		this.log.info("record.armed", DroneLog.fields("on", armed));
	}

	public void beginEpisode(String task, String id, JsonObject meta) {
		if (this.episode != null) {
			this.finish("replaced");
		}
		if (!this.armed) {
			return;
		}
		Path dir = this.dataDir.resolve(task).resolve(id);
		this.episode = new Episode(dir, meta);
		Episode ep = this.episode;
		this.io.execute(() -> ep.open());
		this.log.info("record.begin", DroneLog.fields("dir", dir.toString()));
	}

	/** Remembers the action applied after the latest frame */
	public void onAction(DroneAction action) {
		Episode ep = this.episode;
		if (ep == null) {
			return;
		}
		if (ep.pendingAction != null) {
			ep.droppedFrames++;
		}
		ep.pendingAction = action;
	}

	public void onObservation(Observation obs) {
		Episode ep = this.episode;
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
			this.finish(obs.episode.get("success").getAsBoolean() ? "success" : "timeout");
		}
	}

	public void appendLog(JsonObject line) {
		Episode ep = this.episode;
		if (ep != null) {
			String text = line.toString();
			this.io.execute(() -> ep.writeLog(text));
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

	private void finish(String outcome) {
		Episode ep = this.episode;
		this.episode = null;
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
		private BufferedWriter logOut;

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
				this.logOut = Files.newBufferedWriter(this.dir.resolve("log.jsonl"), StandardCharsets.UTF_8);
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

		void writeLog(String line) {
			if (this.logOut == null) {
				return;
			}
			try {
				this.logOut.write(line);
				this.logOut.newLine();
				this.logOut.flush();
			} catch (IOException e) {
				ClientRuntime.LOGGER.warn("episode log write failed", e);
			}
		}

		void close() {
			try {
				this.writeMeta();
				if (this.stepsOut != null) {
					this.stepsOut.close();
				}
				if (this.logOut != null) {
					this.logOut.close();
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
