package com.catherinepereira.mcdrone.map;

import com.catherinepereira.mcdrone.McDrone;
import com.catherinepereira.mcdrone.ModContent;
import com.catherinepereira.mcdrone.entity.BatteryConfig;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.entity.DroneTier;
import com.catherinepereira.mcdrone.task.Region;
import com.catherinepereira.mcdrone.task.RegionStore;
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
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Draws the saved regions, the drones, and their charging stations on BlueMap's web map. Only loaded when BlueMap is
 * installed, see McDrone. The markers are rebuilt every second from the server's state
 */
public final class BlueMapMarkers {
	private static final String REGIONS = "mcdrone-regions";
	private static final String DRONES = "mcdrone-drones";
	private static final int UPDATE_TICKS = 20;
	// the item textures are 16 pixels, drawn at twice that on the map
	private static final int ICON_SIZE = 32;
	// the in-game outline colors, see ClientRuntime.REGION_COLORS
	private static final Map<Region.Purpose, Integer> REGION_COLORS = Map.of(
		Region.Purpose.SAFE, 0x2E9E63, Region.Purpose.MINE, 0xC98A1B, Region.Purpose.FARM, 0x7DBA3A, Region.Purpose.GENERAL, 0x8A94A6
	);

	private static volatile @Nullable BlueMapAPI api;

	private BlueMapMarkers() {
	}

	public static void register() {
		BlueMapAPI.onEnable(enabled -> {
			for (BlueMapMap map : enabled.getMaps()) {
				writeIcons(map);
			}
			api = enabled;
		});
		BlueMapAPI.onDisable(disabled -> api = null);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			BlueMapAPI current = api;
			if (current != null && server.getTickCount() % UPDATE_TICKS == 0) {
				update(current, server);
			}
		});
	}

	private static void update(BlueMapAPI api, MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			api.getWorld(level).ifPresent(world -> {
				List<DroneEntity> drones = List.copyOf(level.getEntities(ModContent.DRONE, d -> true));
				MarkerSet regions = MarkerSet.builder().label("Drone regions").toggleable(true).build();
				// regions are saved per world, not per dimension, and players mark them in the overworld
				if (level == server.overworld()) {
					for (Region r : RegionStore.get(server).all()) {
						int rgb = REGION_COLORS.getOrDefault(r.purpose(), 0x8A94A6);
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
				.position(drone.getX(), drone.getY() + 0.3, drone.getZ())
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
