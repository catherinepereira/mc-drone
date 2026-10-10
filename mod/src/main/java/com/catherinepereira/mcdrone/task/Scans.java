package com.catherinepereira.mcdrone.task;

import com.catherinepereira.mcdrone.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Scan perception: the server reads every box a job names straight from the world, the way WorldEdit copies, and writes
 * each to a schematic the drone can load. Vision perception leaves these out and the drone reads blocks with its camera.
 * A job that changes no blocks gets an empty scan: in scan perception a guard is handed the mobs in its region instead, see StateJson
 */
public final class Scans {
	public static final String FOLDER = "scans";

	private Scans() {
	}

	/** Writes each box in the job's JSON (source, dest, region, gather) and returns their files by box name, relative to the schematics folder */
	public static JsonObject write(ServerLevel level, DroneJob job, String stamp) {
		JsonObject files = new JsonObject();
		if (!job.changesBlocks()) {
			return files;
		}
		Path dir = Arena.SCHEMATICS.resolve(FOLDER);
		try {
			Files.createDirectories(dir);
			for (Map.Entry<String, JsonElement> entry : job.toJson().entrySet()) {
				if (!(entry.getValue() instanceof JsonArray box) || box.size() != 6) {
					continue;
				}
				BlockPos[] corners = Json.readBox(box);
				String name = stamp + "-" + entry.getKey() + ".schem";
				Schematic.fromWorld(level, corners[0], corners[1]).write(dir.resolve(name));
				files.addProperty(entry.getKey(), FOLDER + "/" + name);
			}
		} catch (IOException e) {
			throw new IllegalStateException("could not write the scans: " + e.getMessage(), e);
		}
		return files;
	}
}
