package com.catherinepereira.mcdrone.client;

import com.catherinepereira.mcdrone.task.BuildJob;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import org.jspecify.annotations.Nullable;

/**
 * The player's copy selection: two source corners and the paste point, where the source's min corner lands.
 * Set with the drone remote in game or from the dashboard
 */
public final class Selection {
	private static final int SOURCE_COLOR = 0x3D7BD9;
	private static final int DEST_COLOR = 0xE8892B;

	public @Nullable BlockPos cornerA;
	public @Nullable BlockPos cornerB;
	public @Nullable BlockPos dest;

	public @Nullable BlockPos min() {
		if (this.cornerA == null || this.cornerB == null) {
			return null;
		}
		return new BlockPos(
			Math.min(this.cornerA.getX(), this.cornerB.getX()), Math.min(this.cornerA.getY(), this.cornerB.getY()), Math.min(this.cornerA.getZ(), this.cornerB.getZ())
		);
	}

	public @Nullable BlockPos size() {
		if (this.cornerA == null || this.cornerB == null) {
			return null;
		}
		return new BlockPos(
			Math.abs(this.cornerA.getX() - this.cornerB.getX()) + 1, Math.abs(this.cornerA.getY() - this.cornerB.getY()) + 1,
			Math.abs(this.cornerA.getZ() - this.cornerB.getZ()) + 1
		);
	}

	/** Why a copy job can't start yet, or null when it can */
	public @Nullable String problem() {
		BlockPos size = this.size();
		if (size == null) {
			return "select both source corners with the drone remote";
		}
		if (size.getX() > BuildJob.MAX_SIZE || size.getY() > BuildJob.MAX_SIZE || size.getZ() > BuildJob.MAX_SIZE) {
			return "the source is " + size.toShortString() + ", at most " + BuildJob.MAX_SIZE + " per side";
		}
		return this.dest == null ? "set the paste point with sneak and right click" : null;
	}

	/** Source corners and paste point as the 9 ints ResetTaskPayload carries */
	public int[] region() {
		return new int[] {
			this.cornerA.getX(), this.cornerA.getY(), this.cornerA.getZ(), this.cornerB.getX(), this.cornerB.getY(), this.cornerB.getZ(),
			this.dest.getX(), this.dest.getY(), this.dest.getZ()
		};
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.add("cornerA", pos(this.cornerA));
		json.add("cornerB", pos(this.cornerB));
		json.add("dest", pos(this.dest));
		BlockPos size = this.size();
		json.add("size", pos(size));
		json.addProperty("problem", this.problem());
		return json;
	}

	/** Sets whichever of cornerA, cornerB, and dest the patch names, null clears one */
	public void merge(JsonObject patch) {
		if (patch.has("cornerA")) {
			this.cornerA = parse(patch.get("cornerA"));
		}
		if (patch.has("cornerB")) {
			this.cornerB = parse(patch.get("cornerB"));
		}
		if (patch.has("dest")) {
			this.dest = parse(patch.get("dest"));
		}
	}

	/** Dust along the edges of the source box and of where the copy will land */
	public void outline(ClientLevel level) {
		BlockPos min = this.min();
		BlockPos size = this.size();
		if (min == null) {
			return;
		}
		edges(level, min, size, SOURCE_COLOR);
		if (this.dest != null) {
			edges(level, this.dest, size, DEST_COLOR);
		}
	}

	public static void edges(ClientLevel level, BlockPos min, BlockPos size, int color) {
		DustParticleOptions dust = new DustParticleOptions(color, 1.0F);
		double x0 = min.getX();
		double y0 = min.getY();
		double z0 = min.getZ();
		double x1 = x0 + size.getX();
		double y1 = y0 + size.getY();
		double z1 = z0 + size.getZ();
		for (double t = 0; t <= 1.0001; t += 1.0 / Math.max(size.getX(), 1) / 2) {
			double x = x0 + (x1 - x0) * t;
			for (double[] yz : new double[][] {{y0, z0}, {y1, z0}, {y0, z1}, {y1, z1}}) {
				level.addParticle(dust, x, yz[0], yz[1], 0, 0, 0);
			}
		}
		for (double t = 0; t <= 1.0001; t += 1.0 / Math.max(size.getY(), 1) / 2) {
			double y = y0 + (y1 - y0) * t;
			for (double[] xz : new double[][] {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) {
				level.addParticle(dust, xz[0], y, xz[1], 0, 0, 0);
			}
		}
		for (double t = 0; t <= 1.0001; t += 1.0 / Math.max(size.getZ(), 1) / 2) {
			double z = z0 + (z1 - z0) * t;
			for (double[] xy : new double[][] {{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}}) {
				level.addParticle(dust, xy[0], xy[1], z, 0, 0, 0);
			}
		}
	}

	private static JsonElement pos(@Nullable BlockPos p) {
		if (p == null) {
			return JsonNull.INSTANCE;
		}
		JsonArray a = new JsonArray();
		a.add(p.getX());
		a.add(p.getY());
		a.add(p.getZ());
		return a;
	}

	private static @Nullable BlockPos parse(JsonElement e) {
		if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 3) {
			return null;
		}
		JsonArray a = e.getAsJsonArray();
		return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
	}
}
