package com.catherinepereira.mcdrone.client;

import com.catherinepereira.mcdrone.Json;
import com.catherinepereira.mcdrone.task.TaskKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Reads a job's reset options, as the job screen, a drone's queue, and the bridge send them, into what ResetTaskPayload
 * carries. Boxes and points left out fall back to the tablet selection, see docs/PROTOCOL.md
 */
public final class JobOptions {
	private JobOptions() {
	}

	/** The schematic for a build job, the blocks for a mine job (a list, or names separated by commas), the crop for a harvest job */
	public static String subject(JsonObject options) {
		if (options.has("blocks") && options.get("blocks").isJsonArray()) {
			List<String> names = new ArrayList<>();
			options.getAsJsonArray("blocks").forEach(e -> names.add(e.getAsString()));
			return String.join(",", names);
		}
		for (String key : new String[] {"schematic", "blocks", "block", "crop"}) {
			if (options.has(key)) {
				return options.get(key).getAsString();
			}
		}
		return "";
	}

	/**
	 * The job's box corners and paste point as 9 ints, then a gather box's corners as 6 more when it has one.
	 * Throws IllegalArgumentException saying why the job can't start
	 */
	public static int[] region(TaskKind kind, JsonObject options, String subject, Selection selection, Regions regions) {
		if (kind == TaskKind.RETURN_HOME) {
			// the server knows the drone's station
			return new int[9];
		}
		Selection sel = selection.copy();
		JsonElement region = options.get("region");
		if (region != null && region.isJsonPrimitive()) {
			BlockPos[] box = savedBox(regions, region.getAsString());
			sel.cornerA = box[0];
			sel.cornerB = box[1];
		}
		JsonElement source = region != null && region.isJsonArray() ? region : options.get("source");
		if (source != null && source.isJsonArray()) {
			if (source.getAsJsonArray().size() != 6) {
				throw new IllegalArgumentException("source needs 6 numbers, x0 y0 z0 x1 y1 z1");
			}
			BlockPos[] box = Json.readBox(source.getAsJsonArray());
			sel.cornerA = box[0];
			sel.cornerB = box[1];
		}
		JsonElement dest = options.get("dest");
		if (dest != null && dest.isJsonArray()) {
			if (dest.getAsJsonArray().size() != 3) {
				throw new IllegalArgumentException("dest needs 3 numbers, x y z");
			}
			sel.dest = Json.readPos(dest.getAsJsonArray());
		} else if (dest != null && dest.isJsonPrimitive()) {
			// the build's min corner lands on the saved region's
			BlockPos[] box = savedBox(regions, dest.getAsString());
			sel.dest = BlockPos.min(box[0], box[1]);
		}
		int[] gather = gather(kind, options.get("gather"), regions);

		if (kind == TaskKind.MINE_REGION || kind == TaskKind.HARVEST_REGION) {
			if (subject.isEmpty()) {
				throw new IllegalArgumentException(kind == TaskKind.MINE_REGION ? "mine_region needs blocks, such as coal_ore, iron_ore" : "harvest_region needs a crop, such as minecraft:wheat");
			}
			if (sel.size() == null) {
				throw new IllegalArgumentException("select the region to mine, or name a saved one");
			}
			return Arrays.copyOf(ints(sel.cornerA, sel.cornerB), 9);
		}
		if (kind == TaskKind.BUILD_SCHEMATIC) {
			if (subject.isEmpty()) {
				throw new IllegalArgumentException("build_schematic needs a schematic file name");
			}
			if (sel.dest == null) {
				throw new IllegalArgumentException("set the paste point with sneak and right click");
			}
			return concat(new int[] {0, 0, 0, 0, 0, 0, sel.dest.getX(), sel.dest.getY(), sel.dest.getZ()}, gather);
		}
		String problem = sel.problem();
		if (problem != null) {
			throw new IllegalArgumentException(problem);
		}
		return concat(sel.region(), gather);
	}

	/** "mine coal_ore, iron_ore in quarry", what the tablet and the dashboard show for a queued job */
	public static String label(TaskKind kind, JsonObject options, String subject) {
		String region = savedName(options, "region", "the selection");
		String dest = savedName(options, "dest", "the paste point");
		String what = subject.replace("minecraft:", "").replace(",", ", ");
		return switch (kind) {
			case COPY_REGION -> "copy " + region + " to " + dest;
			case BUILD_SCHEMATIC -> "build " + what + " at " + dest;
			case MINE_REGION -> "mine " + what + " in " + region;
			case HARVEST_REGION -> "harvest " + what + " in " + region;
			case RETURN_HOME -> "return home";
			default -> kind.id;
		};
	}

	/** Tool tasks take more steps: flying, mining, and container work */
	public static int defaultMaxSteps(TaskKind kind, int navigateSteps) {
		return switch (kind) {
			case NAVIGATE_TO -> navigateSteps;
			case DIG_BLOCK, PLACE_BLOCK -> 400;
			case CHEST_TRANSFER -> 600;
			case MINE_AND_DELIVER, REPLICATE_BUILD -> 900;
			case HARVEST_CROPS -> 1500;
			case COPY_BUILD, SCHEMATIC_BUILD, MINE_DEPOSIT, GATHER_BUILD -> 20000;
			// a job runs until it's done or stopped, sized for the largest 16x16x16 builds
			case COPY_REGION, BUILD_SCHEMATIC, MINE_REGION, HARVEST_REGION, RETURN_HOME -> 100000;
		};
	}

	// the box a copy or build mines its materials from: a saved region's name or 6 numbers, empty without one
	private static int[] gather(TaskKind kind, JsonElement gather, Regions regions) {
		if (gather == null) {
			return new int[0];
		}
		if (kind != TaskKind.COPY_REGION && kind != TaskKind.BUILD_SCHEMATIC) {
			throw new IllegalArgumentException("only copy and build jobs gather their materials");
		}
		BlockPos[] box = null;
		if (gather.isJsonPrimitive()) {
			box = regions.box(gather.getAsString());
		} else if (gather.isJsonArray() && gather.getAsJsonArray().size() == 6) {
			box = Json.readBox(gather.getAsJsonArray());
		}
		if (box == null) {
			throw new IllegalArgumentException("gather needs a saved region's name or 6 numbers, x0 y0 z0 x1 y1 z1");
		}
		return ints(box[0], box[1]);
	}

	private static BlockPos[] savedBox(Regions regions, String name) {
		BlockPos[] box = regions.box(name);
		if (box == null) {
			throw new IllegalArgumentException("no saved region named " + name);
		}
		return box;
	}

	private static String savedName(JsonObject options, String key, String otherwise) {
		return options.has(key) && options.get(key).isJsonPrimitive() ? options.get(key).getAsString() : otherwise;
	}

	private static int[] ints(BlockPos a, BlockPos b) {
		return new int[] {a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()};
	}

	private static int[] concat(int[] a, int[] b) {
		int[] out = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}
}
