package com.catherinepereira.mcdrone.task;

import com.catherinepereira.mcdrone.McDrone;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/** The world's named regions, saved as mcdrone-regions.json in the world folder */
public final class RegionStore {
	public static final int MAX_SIDE = 128;
	private static @Nullable RegionStore current;

	private final Path file;
	private final List<Region> regions = new ArrayList<>();

	private RegionStore(Path file) {
		this.file = file;
	}

	/** The store for this server's world, loaded on first use */
	public static RegionStore get(MinecraftServer server) {
		Path file = server.getWorldPath(LevelResource.ROOT).resolve("mcdrone-regions.json");
		if (current == null || !current.file.equals(file)) {
			current = new RegionStore(file);
			current.load();
		}
		return current;
	}

	public List<Region> all() {
		return List.copyOf(this.regions);
	}

	/** The first safe region holding pos */
	public Optional<Region> safeAt(BlockPos pos) {
		return this.regions.stream().filter(r -> r.purpose() == Region.Purpose.SAFE && r.contains(pos)).findFirst();
	}

	public Optional<Region> byName(String name) {
		return this.regions.stream().filter(r -> r.name().equalsIgnoreCase(name)).findFirst();
	}

	/** Adds a region, or replaces the one with the same name */
	public Region put(String name, Region.Purpose purpose, BlockPos a, BlockPos b) {
		String trimmed = name.trim();
		if (trimmed.isEmpty() || trimmed.length() > 40) {
			throw new IllegalArgumentException("region names are 1 to 40 characters");
		}
		Region region = Region.of(this.byName(trimmed).map(Region::id).orElse(UUID.randomUUID().toString().substring(0, 8)), trimmed, purpose, a, b);
		BlockPos size = region.max().subtract(region.min()).offset(1, 1, 1);
		// any height, so a safe region can cover a house from bedrock to sky
		if (size.getX() > MAX_SIDE || size.getZ() > MAX_SIDE) {
			throw new IllegalArgumentException("regions are at most " + MAX_SIDE + " blocks across");
		}
		this.regions.removeIf(r -> r.id().equals(region.id()));
		this.regions.add(region);
		this.save();
		return region;
	}

	public boolean remove(String id) {
		boolean removed = this.regions.removeIf(r -> r.id().equals(id));
		if (removed) {
			this.save();
		}
		return removed;
	}

	public JsonArray toJson() {
		JsonArray out = new JsonArray();
		this.regions.forEach(r -> out.add(r.toJson()));
		return out;
	}

	private void load() {
		if (!Files.exists(this.file)) {
			return;
		}
		try {
			for (JsonElement e : JsonParser.parseString(Files.readString(this.file, StandardCharsets.UTF_8)).getAsJsonArray()) {
				this.regions.add(Region.fromJson(e.getAsJsonObject()));
			}
		} catch (IOException | RuntimeException e) {
			McDrone.LOGGER.warn("unreadable regions file {}, starting with none", this.file, e);
		}
	}

	private void save() {
		try {
			Files.writeString(this.file, new GsonBuilder().setPrettyPrinting().create().toJson(this.toJson()), StandardCharsets.UTF_8);
		} catch (IOException e) {
			McDrone.LOGGER.warn("could not save regions to {}", this.file, e);
		}
	}
}
