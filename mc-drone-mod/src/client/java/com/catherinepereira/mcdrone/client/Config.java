package com.catherinepereira.mcdrone.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Saved to config/mcdrone.json, editable from the dashboard */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final List<String> STREAMS = List.of("rgb", "depth", "mask", "state");

	public int port = 8318;
	public int width = 160;
	public int height = 120;
	// state is a label stream for training the block reader, off unless asked for
	public List<String> streams = new ArrayList<>(List.of("rgb", "depth", "mask"));
	public int streamHz = 10;
	public float depthMax = 64.0F;
	public float maxSpeed = 0.4F;
	public float maxVerticalSpeed = 0.3F;
	public float smoothing = 0.35F;
	public float maxLookPerTick = 15.0F;
	public int resetSettleTicks = 10;
	public int autoResetTicks = 20;
	public String task = "navigate_to";
	public String terrain = "flat";
	public int radius = 12;
	public int maxSteps = 400;
	public int obstacles = 0;
	public float successDist = 1.5F;
	public float collisionPenalty = 0.05F;
	// geofence: the arena footprint from its floor up to the cleared height, widened by boundsPadding blocks
	public int boundsPadding = 0;
	public float outOfBoundsPenalty = 10.0F;
	// "inventory" places from the drone's stacks, "unlimited" places any named block without using items
	public String materials = "inventory";
	// a beat after each break, place, open, close, or transfer so a watcher can follow along, 0 for training runs
	public int actionPauseMs = 300;
	public Integer arenaX;
	public Integer arenaZ;
	public String logLevel = "info";
	public List<String> mutedEvents = new ArrayList<>(List.of("action"));

	private transient Path file;

	public static Config load(Path file) {
		Config config = new Config();
		if (Files.exists(file)) {
			try {
				config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class);
			} catch (IOException | RuntimeException e) {
				ClientRuntime.LOGGER.warn("unreadable config {}, using defaults", file, e);
				config = new Config();
			}
		}
		config.file = file;
		config.clamp();
		return config;
	}

	public void save() {
		try {
			Files.createDirectories(this.file.getParent());
			Files.writeString(this.file, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			ClientRuntime.LOGGER.warn("could not save config {}", this.file, e);
		}
	}

	public JsonObject toJson() {
		return GSON.toJsonTree(this).getAsJsonObject();
	}

	/** Copies the known fields present in patch, then saves */
	public void merge(JsonObject patch) {
		JsonObject current = this.toJson();
		for (var entry : patch.entrySet()) {
			if (current.has(entry.getKey()) || entry.getKey().equals("arenaX") || entry.getKey().equals("arenaZ")) {
				current.add(entry.getKey(), entry.getValue());
			}
		}
		Config merged = GSON.fromJson(current, Config.class);
		for (Field field : Config.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
				continue;
			}
			try {
				field.set(this, field.get(merged));
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}
		this.clamp();
		this.save();
	}

	public boolean wants(String stream) {
		return this.streams.contains(stream);
	}

	private void clamp() {
		this.width = Math.clamp(this.width, 16, 1024);
		this.height = Math.clamp(this.height, 16, 1024);
		this.streamHz = Math.clamp(this.streamHz, 1, 20);
		this.radius = Math.clamp(this.radius, 4, 48);
		this.maxSteps = Math.clamp(this.maxSteps, 10, 100000);
		if (this.streams == null) {
			this.streams = new ArrayList<>(List.of("rgb", "depth", "mask"));
		}
		this.streams.removeIf(s -> !STREAMS.contains(s));
		this.actionPauseMs = Math.clamp(this.actionPauseMs, 0, 3000);
		if (!"unlimited".equals(this.materials)) {
			this.materials = "inventory";
		}
		if (this.mutedEvents == null) {
			this.mutedEvents = new ArrayList<>();
		}
	}

	static JsonElement tree(Object value) {
		return GSON.toJsonTree(value);
	}
}
