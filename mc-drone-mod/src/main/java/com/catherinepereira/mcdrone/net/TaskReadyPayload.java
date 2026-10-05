package com.catherinepereira.mcdrone.net;

import com.catherinepereira.mcdrone.McDrone;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client, the arena is built and the drone is at its spawn pose. arena is ArenaRecord.toJson, marker is the primary goal block */
public record TaskReadyPayload(int requestId, int droneId, BlockPos marker, BlockPos origin, double x, double y, double z, float yaw, String arena, String error)
	implements CustomPacketPayload {
	public static final Type<TaskReadyPayload> TYPE = new Type<>(McDrone.id("task_ready"));
	public static final StreamCodec<FriendlyByteBuf, TaskReadyPayload> CODEC = CustomPacketPayload.codec(TaskReadyPayload::write, TaskReadyPayload::read);

	public static TaskReadyPayload failed(int requestId, String error) {
		return new TaskReadyPayload(requestId, -1, BlockPos.ZERO, BlockPos.ZERO, 0, 0, 0, 0, "{}", error);
	}

	private static TaskReadyPayload read(FriendlyByteBuf buf) {
		return new TaskReadyPayload(
			buf.readVarInt(), buf.readVarInt(), buf.readBlockPos(), buf.readBlockPos(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readUtf(), buf.readUtf()
		);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.requestId);
		buf.writeVarInt(this.droneId);
		buf.writeBlockPos(this.marker);
		buf.writeBlockPos(this.origin);
		buf.writeDouble(this.x);
		buf.writeDouble(this.y);
		buf.writeDouble(this.z);
		buf.writeFloat(this.yaw);
		buf.writeUtf(this.arena);
		buf.writeUtf(this.error);
	}

	@Override
	public Type<TaskReadyPayload> type() {
		return TYPE;
	}
}
