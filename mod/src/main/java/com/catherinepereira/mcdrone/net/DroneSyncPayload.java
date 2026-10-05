package com.catherinepereira.mcdrone.net;

import com.catherinepereira.mcdrone.McDrone;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client, the drone's inventory, open container, mining state, and the task metrics after a tool tick.
 * Item arrays are raw item id and count per slot. changedPositions and changedStates let the client apply
 * block edits before its next capture instead of waiting for the chunk broadcast
 */
public record DroneSyncPayload(
	int entityId,
	int seq,
	int[] inventory,
	boolean containerOpen,
	BlockPos containerPos,
	int[] container,
	boolean breaking,
	BlockPos breakingPos,
	float breakProgress,
	long[] changedPositions,
	int[] changedStates,
	String events,
	int[] metrics
) implements CustomPacketPayload {
	public static final Type<DroneSyncPayload> TYPE = new Type<>(McDrone.id("drone_sync"));
	public static final StreamCodec<FriendlyByteBuf, DroneSyncPayload> CODEC = CustomPacketPayload.codec(DroneSyncPayload::write, DroneSyncPayload::read);

	private static DroneSyncPayload read(FriendlyByteBuf buf) {
		return new DroneSyncPayload(
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readVarIntArray(),
			buf.readBoolean(),
			buf.readBlockPos(),
			buf.readVarIntArray(),
			buf.readBoolean(),
			buf.readBlockPos(),
			buf.readFloat(),
			buf.readLongArray(),
			buf.readVarIntArray(),
			buf.readUtf(),
			buf.readVarIntArray()
		);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.entityId);
		buf.writeVarInt(this.seq);
		buf.writeVarIntArray(this.inventory);
		buf.writeBoolean(this.containerOpen);
		buf.writeBlockPos(this.containerPos);
		buf.writeVarIntArray(this.container);
		buf.writeBoolean(this.breaking);
		buf.writeBlockPos(this.breakingPos);
		buf.writeFloat(this.breakProgress);
		buf.writeLongArray(this.changedPositions);
		buf.writeVarIntArray(this.changedStates);
		buf.writeUtf(this.events);
		buf.writeVarIntArray(this.metrics);
	}

	@Override
	public Type<DroneSyncPayload> type() {
		return TYPE;
	}
}
