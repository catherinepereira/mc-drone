package com.catherinepereira.mcdrone.net;

import com.catherinepereira.mcdrone.McDrone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server, makes one of the sender's drones their active one, by entity id */
public record SelectDronePayload(int droneId) implements CustomPacketPayload {
	public static final Type<SelectDronePayload> TYPE = new Type<>(McDrone.id("select_drone"));
	public static final StreamCodec<FriendlyByteBuf, SelectDronePayload> CODEC = CustomPacketPayload.codec(SelectDronePayload::write, SelectDronePayload::read);

	private static SelectDronePayload read(FriendlyByteBuf buf) {
		return new SelectDronePayload(buf.readVarInt());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.droneId);
	}

	@Override
	public Type<SelectDronePayload> type() {
		return TYPE;
	}
}
