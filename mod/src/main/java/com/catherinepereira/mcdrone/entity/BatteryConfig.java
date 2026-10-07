package com.catherinepereira.mcdrone.entity;

import com.catherinepereira.mcdrone.McDrone;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The drone battery's settings, read from config/mcdrone-battery.json when the server starts, written with the defaults
 * when it's missing. With enabled false drones never run down. Charge is a fraction from 0 to 1
 */
public final class BatteryConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mcdrone-battery.json");

	public boolean enabled = true;
	// minutes of flight on a full charge
	public double flightMinutes = 15.0;
	// hovering in place draws this share of what flying does
	public double hoverShare = 0.5;
	// each block broken costs this many seconds of flight
	public double breakSeconds = 2.0;
	// minutes on a charging station from empty to full
	public double chargeMinutes = 3.0;
	// the share of a charge the drone brain keeps in hand for the flight home
	public double reserve = 0.1;

	private static BatteryConfig current = new BatteryConfig();

	public static BatteryConfig get() {
		return current;
	}

	public static void load() {
		BatteryConfig config = new BatteryConfig();
		try {
			if (Files.exists(FILE)) {
				config = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), BatteryConfig.class);
			} else {
				Files.createDirectories(FILE.getParent());
				Files.writeString(FILE, GSON.toJson(config), StandardCharsets.UTF_8);
			}
		} catch (IOException | RuntimeException e) {
			McDrone.LOGGER.warn("unreadable battery config {}, using defaults", FILE, e);
			config = new BatteryConfig();
		}
		config.flightMinutes = Math.max(0.1, config.flightMinutes);
		config.chargeMinutes = Math.max(0.05, config.chargeMinutes);
		config.hoverShare = Math.clamp(config.hoverShare, 0.0, 1.0);
		config.breakSeconds = Math.max(0.0, config.breakSeconds);
		config.reserve = Math.clamp(config.reserve, 0.0, 0.9);
		current = config;
	}

	/** Charge used per tick flying, 20 ticks a second */
	public double flightPerTick() {
		return 1.0 / (this.flightMinutes * 60.0 * 20.0);
	}

	public double chargePerTick() {
		return 1.0 / (this.chargeMinutes * 60.0 * 20.0);
	}

	public double breakCost() {
		return this.breakSeconds * 20.0 * this.flightPerTick();
	}

	/** What the drone is told, so a planner can budget its charge */
	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("enabled", this.enabled);
		json.addProperty("flightPerTick", this.flightPerTick());
		json.addProperty("hoverShare", this.hoverShare);
		json.addProperty("breakCost", this.breakCost());
		json.addProperty("chargePerTick", this.chargePerTick());
		json.addProperty("reserve", this.reserve);
		return json;
	}
}
