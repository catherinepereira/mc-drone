package com.catherinepereira.mcdrone.net;

import com.catherinepereira.mcdrone.McDrone;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server, renames the sender's drone, a blank name puts back the default */
public record RenameDronePayload(String name) implements CustomPacketPayload {
	public static final Type<RenameDronePayload> TYPE = new Type<>(McDrone.id("rename_drone"));
	public static final StreamCodec<FriendlyByteBuf, RenameDronePayload> CODEC = CustomPacketPayload.codec(RenameDronePayload::write, RenameDronePayload::read);

	private static RenameDronePayload read(FriendlyByteBuf buf) {
		return new RenameDronePayload(buf.readUtf(DroneEntity.MAX_NAME * 4));
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(this.name);
	}

	@Override
	public Type<RenameDronePayload> type() {
		return TYPE;
	}
}
