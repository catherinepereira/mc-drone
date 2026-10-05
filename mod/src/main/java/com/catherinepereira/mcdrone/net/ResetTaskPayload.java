package com.catherinepereira.mcdrone.net;

import com.catherinepereira.mcdrone.McDrone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server, rebuild the arena for task (a TaskKind id), or start a job in the player's world.
 * hasOrigin false means build the arena under the player and report the origin back.
 * region is empty for arenas, or the job's box corners a and b and its paste point as 9 ints. subject is the schematic file
 * for a build job and the block for a mine job
 */
public record ResetTaskPayload(
	int requestId, String task, String terrain, long seed, int radius, int obstacles, int targets, boolean hasOrigin, int originX, int originZ,
	int[] region, String subject
) implements CustomPacketPayload {
	public static final Type<ResetTaskPayload> TYPE = new Type<>(McDrone.id("reset_task"));
	public static final StreamCodec<FriendlyByteBuf, ResetTaskPayload> CODEC = CustomPacketPayload.codec(ResetTaskPayload::write, ResetTaskPayload::read);

	private static ResetTaskPayload read(FriendlyByteBuf buf) {
		return new ResetTaskPayload(
			buf.readVarInt(), buf.readUtf(), buf.readUtf(), buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
			buf.readVarIntArray(9), buf.readUtf(256)
		);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.requestId);
		buf.writeUtf(this.task);
		buf.writeUtf(this.terrain);
		buf.writeLong(this.seed);
		buf.writeVarInt(this.radius);
		buf.writeVarInt(this.obstacles);
		buf.writeVarInt(this.targets);
		buf.writeBoolean(this.hasOrigin);
		buf.writeVarInt(this.originX);
		buf.writeVarInt(this.originZ);
		buf.writeVarIntArray(this.region);
		buf.writeUtf(this.subject);
	}

	@Override
	public Type<ResetTaskPayload> type() {
		return TYPE;
	}
}
