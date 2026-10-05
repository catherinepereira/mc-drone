package com.catherinepereira.mcdrone.client.obs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * One captured frame plus the drone state at capture time.
 * Wire format is described in protocol/PROTOCOL.md
 */
public final class Observation {
	public final long seq;
	public final long tick;
	public final int width;
	public final int height;
	public final JsonObject state;
	public final @Nullable JsonObject episode;
	public final @Nullable JsonObject action;
	public final byte @Nullable [] rgb;
	public final float @Nullable [] depth;
	public final short @Nullable [] mask;
	public final short @Nullable [] blockStates;

	public Observation(
		long seq,
		long tick,
		int width,
		int height,
		JsonObject state,
		@Nullable JsonObject episode,
		@Nullable JsonObject action,
		byte @Nullable [] rgb,
		float @Nullable [] depth,
		short @Nullable [] mask,
		short @Nullable [] blockStates
	) {
		this.seq = seq;
		this.tick = tick;
		this.width = width;
		this.height = height;
		this.state = state;
		this.episode = episode;
		this.action = action;
		this.rgb = rgb;
		this.depth = depth;
		this.mask = mask;
		this.blockStates = blockStates;
	}

	public boolean done() {
		return this.episode != null && this.episode.get("done").getAsBoolean();
	}

	public byte[] encode(@Nullable Integer replyTo) {
		JsonObject streams = new JsonObject();
		int offset = 0;
		if (this.rgb != null) {
			streams.add("rgb", stream(offset, this.rgb.length, "uint8", this.height, this.width, 3));
			offset += this.rgb.length;
		}
		if (this.depth != null) {
			streams.add("depth", stream(offset, this.depth.length * 4, "float32", this.height, this.width));
			offset += this.depth.length * 4;
		}
		if (this.mask != null) {
			streams.add("mask", stream(offset, this.mask.length * 2, "uint16", this.height, this.width));
			offset += this.mask.length * 2;
		}
		if (this.blockStates != null) {
			streams.add("state", stream(offset, this.blockStates.length * 2, "uint16", this.height, this.width));
			offset += this.blockStates.length * 2;
		}

		JsonObject header = new JsonObject();
		header.addProperty("type", "obs");
		header.addProperty("seq", this.seq);
		header.addProperty("tick", this.tick);
		header.add("replyTo", replyTo == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(replyTo));
		header.add("state", this.state);
		header.add("episode", orNull(this.episode));
		header.add("action", orNull(this.action));
		header.add("streams", streams);
		byte[] headerBytes = header.toString().getBytes(StandardCharsets.UTF_8);

		ByteBuffer out = ByteBuffer.allocate(4 + headerBytes.length + offset).order(ByteOrder.LITTLE_ENDIAN);
		out.putInt(headerBytes.length);
		out.put(headerBytes);
		if (this.rgb != null) {
			out.put(this.rgb);
		}
		if (this.depth != null) {
			for (float v : this.depth) {
				out.putFloat(v);
			}
		}
		if (this.mask != null) {
			for (short v : this.mask) {
				out.putShort(v);
			}
		}
		if (this.blockStates != null) {
			for (short v : this.blockStates) {
				out.putShort(v);
			}
		}
		return out.array();
	}

	private static JsonElement orNull(@Nullable JsonObject value) {
		return value == null ? JsonNull.INSTANCE : value;
	}

	private static JsonObject stream(int offset, int length, String dtype, int... shape) {
		JsonObject json = new JsonObject();
		json.addProperty("offset", offset);
		json.addProperty("length", length);
		JsonArray dims = new JsonArray();
		for (int d : shape) {
			dims.add(d);
		}
		json.add("shape", dims);
		json.addProperty("dtype", dtype);
		return json;
	}
}
