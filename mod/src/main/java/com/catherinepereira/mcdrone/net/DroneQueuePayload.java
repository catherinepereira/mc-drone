package com.catherinepereira.mcdrone.net;

import com.catherinepereira.mcdrone.McDrone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server, edits one of the sender's drones' job queue. edit is JSON: {"op": "add", "job": {...}},
 * {"op": "remove", "index": n}, or {"op": "clear"}
 */
public record DroneQueuePayload(int droneId, String edit) implements CustomPacketPayload {
	public static final Type<DroneQueuePayload> TYPE = new Type<>(McDrone.id("drone_queue"));
	public static final StreamCodec<FriendlyByteBuf, DroneQueuePayload> CODEC = CustomPacketPayload.codec(DroneQueuePayload::write, DroneQueuePayload::read);

	private static DroneQueuePayload read(FriendlyByteBuf buf) {
		return new DroneQueuePayload(buf.readVarInt(), buf.readUtf(8192));
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.droneId);
		buf.writeUtf(this.edit, 8192);
	}

	@Override
	public Type<DroneQueuePayload> type() {
		return TYPE;
	}
}
