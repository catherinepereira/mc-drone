package com.catherinepereira.mcdrone.net;

import com.catherinepereira.mcdrone.McDrone;
import com.catherinepereira.mcdrone.tool.ToolRequest;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server, tool intent for one simulated tick, answered by a DroneSyncPayload with the same seq */
public record DroneToolPayload(int entityId, int seq, ToolRequest request) implements CustomPacketPayload {
	public static final Type<DroneToolPayload> TYPE = new Type<>(McDrone.id("drone_tool"));
	public static final StreamCodec<FriendlyByteBuf, DroneToolPayload> CODEC = CustomPacketPayload.codec(DroneToolPayload::write, DroneToolPayload::read);

	private static DroneToolPayload read(FriendlyByteBuf buf) {
		return new DroneToolPayload(buf.readVarInt(), buf.readVarInt(), ToolRequest.read(buf));
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.entityId);
		buf.writeVarInt(this.seq);
		this.request.write(buf);
	}

	@Override
	public Type<DroneToolPayload> type() {
		return TYPE;
	}
}
