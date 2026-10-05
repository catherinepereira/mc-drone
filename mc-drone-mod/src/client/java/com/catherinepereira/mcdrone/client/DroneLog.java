package com.catherinepereira.mcdrone.client;

import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * JSONL logger shared by the client subsystems.
 * Lines go to logs/mod-<session>.jsonl, a ring buffer for the HTTP API, and live listeners
 */
public final class DroneLog {
	private static final List<String> LEVELS = List.of("debug", "info", "warn", "error");
	private static final int RING_SIZE = 2000;

	private final String session;
	private final Config config;
	private final ArrayDeque<JsonObject> ring = new ArrayDeque<>();
	private final List<Consumer<JsonObject>> listeners = new ArrayList<>();
	private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "mcdrone-log");
		t.setDaemon(true);
		return t;
	});
	private BufferedWriter out;
	private volatile long tick;
	private volatile String episode;

	public DroneLog(Path dir, String session, Config config) {
		this.session = session;
		this.config = config;
		try {
			Files.createDirectories(dir);
			this.out = Files.newBufferedWriter(
				dir.resolve("mod-" + session + ".jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND
			);
		} catch (IOException e) {
			ClientRuntime.LOGGER.error("log file unavailable, logging to memory only", e);
		}
	}

	public String session() {
		return this.session;
	}

	public void setTick(long tick) {
		this.tick = tick;
	}

	public void setEpisode(String episode) {
		this.episode = episode;
	}

	public synchronized void addListener(Consumer<JsonObject> listener) {
		this.listeners.add(listener);
	}

	public void debug(String event, JsonObject fields) {
		this.log("debug", event, fields);
	}

	public void info(String event, JsonObject fields) {
		this.log("info", event, fields);
	}

	public void warn(String event, JsonObject fields) {
		this.log("warn", event, fields);
	}

	public void error(String event, Throwable error, JsonObject fields) {
		JsonObject f = fields == null ? new JsonObject() : fields;
		f.addProperty("error", error.toString());
		StringBuilder trace = new StringBuilder();
		for (StackTraceElement el : error.getStackTrace()) {
			trace.append(el).append('\n');
		}
		f.addProperty("trace", trace.toString());
		ClientRuntime.LOGGER.error(event, error);
		this.log("error", event, f);
	}

	public void log(String level, String event, JsonObject fields) {
		if (LEVELS.indexOf(level) < LEVELS.indexOf(this.config.logLevel) || this.config.mutedEvents.contains(event)) {
			return;
		}
		JsonObject line = new JsonObject();
		line.addProperty("ts", Instant.now().toString());
		line.addProperty("source", "mod");
		line.addProperty("level", level);
		line.addProperty("event", event);
		line.addProperty("session", this.session);
		if (this.episode != null) {
			line.addProperty("episode", this.episode);
		}
		line.addProperty("tick", this.tick);
		if (fields != null) {
			for (var entry : fields.entrySet()) {
				line.add(entry.getKey(), entry.getValue());
			}
		}
		List<Consumer<JsonObject>> targets;
		synchronized (this) {
			this.ring.addLast(line);
			while (this.ring.size() > RING_SIZE) {
				this.ring.removeFirst();
			}
			targets = List.copyOf(this.listeners);
		}
		if (this.out != null) {
			String text = line.toString();
			this.writer.execute(() -> {
				try {
					this.out.write(text);
					this.out.newLine();
					this.out.flush();
				} catch (IOException e) {
					ClientRuntime.LOGGER.warn("log write failed", e);
				}
			});
		}
		for (Consumer<JsonObject> target : targets) {
			target.accept(line);
		}
	}

	public synchronized List<JsonObject> tail(int limit) {
		List<JsonObject> all = new ArrayList<>(this.ring);
		return all.subList(Math.max(0, all.size() - limit), all.size());
	}

	public static JsonObject fields(Object... kv) {
		JsonObject json = new JsonObject();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			json.add(String.valueOf(kv[i]), Config.tree(kv[i + 1]));
		}
		return json;
	}
}
