package com.catherinepereira.mcdrone.map;

import com.catherinepereira.mcdrone.McDrone;
import com.catherinepereira.mcdrone.ModContent;
import com.catherinepereira.mcdrone.entity.BatteryConfig;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.entity.DroneTier;
import com.catherinepereira.mcdrone.task.Region;
import com.catherinepereira.mcdrone.task.RegionStore;
import com.flowpowered.math.vector.Vector2i;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Draws the saved regions, the drones, and their charging stations on BlueMap's web map. Only loaded when BlueMap is
 * installed, see McDrone. The markers are rebuilt every second from the server's state. BlueMap pushes marker sets to the
 * web app every 10 seconds and players every second, so the drones' positions also go to a file in the web root every
 * second, and a script added to the web app moves the drone markers from it, as often as BlueMap moves players.
 * BlueMap renders from the region files, so blocks drones and arenas change are saved and rendered every few seconds
 */
public final class BlueMapMarkers {
	private static final String REGIONS = "mcdrone-regions";
	private static final String DRONES = "mcdrone-drones";
	private static final int UPDATE_TICKS = 20;
	private static final String SCRIPT = "mcdrone/drones.js";
	private static final String POSITIONS = "mcdrone/drones.json";
	private static final int RENDER_TICKS = 100;
	// region files with changed blocks, by level, written by the server thread only
	private static final Map<ServerLevel, Set<Vector2i>> CHANGED = new HashMap<>();
	// a drone's marker sits this far over its feet
	private static final double MARKER_HEIGHT = 0.3;
	// the item textures are 16 pixels, drawn at twice that on the map
	private static final int ICON_SIZE = 32;

	private static volatile @Nullable BlueMapAPI api;

	private BlueMapMarkers() {
	}

	public static void register() {
		BlueMapAPI.onEnable(enabled -> {
			for (BlueMapMap map : enabled.getMaps()) {
				writeIcons(map);
			}
			writeScript(enabled);
			api = enabled;
		});
		BlueMapAPI.onDisable(disabled -> api = null);
		MapChanges.listen((level, min, max) -> {
			Set<Vector2i> regions = CHANGED.computeIfAbsent(level, l -> new HashSet<>());
			for (int x = min.getX() >> 9; x <= max.getX() >> 9; x++) {
				for (int z = min.getZ() >> 9; z <= max.getZ() >> 9; z++) {
					regions.add(new Vector2i(x, z));
				}
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			BlueMapAPI current = api;
			if (current != null && server.getTickCount() % UPDATE_TICKS == 0) {
				update(current, server);
			}
			if (current != null && server.getTickCount() % RENDER_TICKS == 0 && !CHANGED.isEmpty()) {
				renderChanges(current);
			}
		});
	}

	// saves the changed chunks and asks BlueMap to render their regions again, it reads the world from disk
	private static void renderChanges(BlueMapAPI api) {
		CHANGED.forEach((level, regions) -> {
			level.getChunkSource().save(false);
			api.getWorld(level).ifPresent(world -> {
				for (BlueMapMap map : world.getMaps()) {
					api.getRenderManager().scheduleMapUpdateTask(map, regions, true);
				}
			});
		});
		CHANGED.clear();
	}

	private static void update(BlueMapAPI api, MinecraftServer server) {
		writePositions(api, server);
		for (ServerLevel level : server.getAllLevels()) {
			api.getWorld(level).ifPresent(world -> {
				List<DroneEntity> drones = List.copyOf(level.getEntities(ModContent.DRONE, d -> true));
				MarkerSet regions = MarkerSet.builder().label("Drone regions").toggleable(true).build();
				// regions are saved per world, not per dimension, and players mark them in the overworld
				if (level == server.overworld()) {
					for (Region r : RegionStore.get(server).all()) {
						int rgb = r.purpose().color;
						String label = r.name() + " (" + r.purpose().id() + ")";
						regions.put("region-" + r.id(), ExtrudeMarker.builder()
							.label(label)
							.detail(escape(label))
							.shape(Shape.createRect(r.min().getX(), r.min().getZ(), r.max().getX() + 1, r.max().getZ() + 1), r.min().getY(), r.max().getY() + 1)
							.centerPosition()
							.lineColor(new Color(rgb, 1.0F))
							.fillColor(new Color(rgb, 0.25F))
							.lineWidth(2)
							.build());
					}
				}
				for (BlueMapMap map : world.getMaps()) {
					map.getMarkerSets().put(DRONES, droneMarkers(map, drones));
					map.getMarkerSets().put(REGIONS, regions);
				}
			});
		}
	}

	// the icon's address depends on the map, so each map gets its own set
	private static MarkerSet droneMarkers(BlueMapMap map, List<DroneEntity> drones) {
		MarkerSet set = MarkerSet.builder().label("Drones").toggleable(true).build();
		for (DroneEntity drone : drones) {
			String id = drone.getUUID().toString();
			set.put("drone-" + id, POIMarker.builder()
				.label(drone.getDisplayName().getString())
				.detail(droneDetail(drone))
				.position(drone.getX(), drone.getY() + MARKER_HEIGHT, drone.getZ())
				.icon(map.getAssetStorage().getAssetUrl(icon(drone.tier())), ICON_SIZE / 2, ICON_SIZE / 2)
				.build());
			BlockPos home = drone.home();
			if (home != null) {
				String label = drone.shownName() + "'s charging station";
				set.put("station-" + id, POIMarker.builder()
					.label(label)
					.detail(escape(label))
					.position(home.getX() + 0.5, home.getY() + 1.0, home.getZ() + 0.5)
					.build());
			}
		}
		return set;
	}

	private static String droneDetail(DroneEntity drone) {
		StringBuilder html = new StringBuilder("<b>").append(escape(drone.getDisplayName().getString())).append("</b><br>")
			.append(drone.tier().id).append(" drone");
		if (BatteryConfig.get().enabled) {
			html.append(", ").append(Math.round(drone.charge() * 100)).append("% charge").append(drone.docked() ? ", docked" : "");
		}
		int queued = drone.queue().size();
		if (queued > 0) {
			html.append("<br>").append(queued).append(queued == 1 ? " job queued" : " jobs queued");
		}
		return html.toString();
	}

	// names are player-chosen and BlueMap renders details as HTML, every marker gets an escaped detail
	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
	}

	private static String icon(DroneTier tier) {
		return "mcdrone-" + tier.id + "-drone.png";
	}

	// every drone's position by uuid, as [x, y, z] at its marker, replaced whole so the web app never reads half a file
	private static void writePositions(BlueMapAPI api, MinecraftServer server) {
		JsonObject positions = new JsonObject();
		for (ServerLevel level : server.getAllLevels()) {
			for (DroneEntity drone : level.getEntities(ModContent.DRONE, d -> true)) {
				JsonArray pos = new JsonArray();
				pos.add(drone.getX());
				pos.add(drone.getY() + MARKER_HEIGHT);
				pos.add(drone.getZ());
				positions.add(drone.getUUID().toString(), pos);
			}
		}
		Path out = api.getWebApp().getWebRoot().resolve(POSITIONS);
		try {
			Path next = out.resolveSibling("drones.json.next");
			Files.writeString(next, positions.toString());
			Files.move(next, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			McDrone.LOGGER.warn("could not write the drone positions for BlueMap", e);
		}
	}

	private static void writeScript(BlueMapAPI api) {
		Path out = api.getWebApp().getWebRoot().resolve(SCRIPT);
		try (InputStream in = BlueMapMarkers.class.getResourceAsStream("/assets/mcdrone/bluemap/drones.js")) {
			if (in == null) {
				return;
			}
			Files.createDirectories(out.getParent());
			Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
			api.getWebApp().registerScript(SCRIPT);
		} catch (IOException e) {
			McDrone.LOGGER.warn("could not add the drone script to BlueMap", e);
		}
	}

	// the drone item textures, scaled up without smoothing, into the map's assets once per start
	private static void writeIcons(BlueMapMap map) {
		for (DroneTier tier : DroneTier.values()) {
			String texture = "/assets/mcdrone/textures/item/" + tier.id + "_drone.png";
			try (InputStream in = BlueMapMarkers.class.getResourceAsStream(texture)) {
				if (in == null) {
					continue;
				}
				BufferedImage source = ImageIO.read(in);
				BufferedImage icon = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
				icon.getGraphics().drawImage(source.getScaledInstance(ICON_SIZE, ICON_SIZE, Image.SCALE_REPLICATE), 0, 0, null);
				try (OutputStream out = map.getAssetStorage().writeAsset(icon(tier))) {
					ImageIO.write(icon, "png", out);
				}
			} catch (IOException e) {
				McDrone.LOGGER.warn("could not write the {} drone icon to BlueMap", tier.id, e);
			}
		}
	}
}
