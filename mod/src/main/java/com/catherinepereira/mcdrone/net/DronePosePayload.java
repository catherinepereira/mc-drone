package com.catherinepereira.mcdrone.net;

import com.catherinepereira.mcdrone.McDrone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server, the pose the owning client simulated this tick */
public record DronePosePayload(int entityId, double x, double y, double z, float yaw, float pitch) implements CustomPacketPayload {
	public static final Type<DronePosePayload> TYPE = new Type<>(McDrone.id("drone_pose"));
	public static final StreamCodec<FriendlyByteBuf, DronePosePayload> CODEC = CustomPacketPayload.codec(DronePosePayload::write, DronePosePayload::read);

	private static DronePosePayload read(FriendlyByteBuf buf) {
		return new DronePosePayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.entityId);
		buf.writeDouble(this.x);
		buf.writeDouble(this.y);
		buf.writeDouble(this.z);
		buf.writeFloat(this.yaw);
		buf.writeFloat(this.pitch);
	}

	@Override
	public Type<DronePosePayload> type() {
		return TYPE;
	}
}
