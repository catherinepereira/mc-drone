package com.catherinepereira.mcdrone.task;

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
 * each to a schematic the drone can load. Vision perception leaves these out and the drone reads blocks with its camera
 */
public final class Scans {
	public static final String FOLDER = "scans";

	private Scans() {
	}

	/** Writes each box in the job's JSON (source, dest, region, gather) and returns their files by box name, relative to the schematics folder */
	public static JsonObject write(ServerLevel level, DroneJob job, String stamp) {
		JsonObject files = new JsonObject();
		Path dir = Arena.SCHEMATICS.resolve(FOLDER);
		try {
			Files.createDirectories(dir);
			for (Map.Entry<String, JsonElement> entry : job.toJson().entrySet()) {
				if (!(entry.getValue() instanceof JsonArray box) || box.size() != 6) {
					continue;
				}
				BlockPos min = new BlockPos(box.get(0).getAsInt(), box.get(1).getAsInt(), box.get(2).getAsInt());
				BlockPos max = new BlockPos(box.get(3).getAsInt(), box.get(4).getAsInt(), box.get(5).getAsInt());
				String name = stamp + "-" + entry.getKey() + ".schem";
				Schematic.fromWorld(level, min, max).write(dir.resolve(name));
				files.addProperty(entry.getKey(), FOLDER + "/" + name);
			}
		} catch (IOException e) {
			throw new IllegalStateException("could not write the scans: " + e.getMessage(), e);
		}
		return files;
	}
}
