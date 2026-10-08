package com.catherinepereira.mcdrone;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** Block positions and boxes in the protocol's JSON shapes, see docs/PROTOCOL.md */
public final class Json {
	private Json() {
	}

	/** [x, y, z], or null */
	public static JsonElement pos(@Nullable BlockPos p) {
		if (p == null) {
			return JsonNull.INSTANCE;
		}
		JsonArray a = new JsonArray();
		a.add(p.getX());
		a.add(p.getY());
		a.add(p.getZ());
		return a;
	}

	/** [x0, y0, z0, x1, y1, z1], inclusive corners */
	public static JsonArray box(BlockPos min, BlockPos max) {
		JsonArray a = new JsonArray();
		for (int v : new int[] {min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()}) {
			a.add(v);
		}
		return a;
	}

	/** The x, y, z that start at offset in a, so offset 3 is a box's second corner */
	public static BlockPos readPos(JsonArray a, int offset) {
		return new BlockPos(a.get(offset).getAsInt(), a.get(offset + 1).getAsInt(), a.get(offset + 2).getAsInt());
	}

	public static BlockPos readPos(JsonArray a) {
		return readPos(a, 0);
	}

	/** A box's two corners as written, not sorted into min and max */
	public static BlockPos[] readBox(JsonArray a) {
		return new BlockPos[] {readPos(a, 0), readPos(a, 3)};
	}
}
