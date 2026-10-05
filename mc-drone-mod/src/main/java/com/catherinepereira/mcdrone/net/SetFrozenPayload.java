package com.catherinepereira.mcdrone.net;

import com.catherinepereira.mcdrone.McDrone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server, freezes world ticking for lockstep mode */
public record SetFrozenPayload(boolean frozen) implements CustomPacketPayload {
	public static final Type<SetFrozenPayload> TYPE = new Type<>(McDrone.id("set_frozen"));
	public static final StreamCodec<FriendlyByteBuf, SetFrozenPayload> CODEC = CustomPacketPayload.codec(SetFrozenPayload::write, SetFrozenPayload::read);

	private static SetFrozenPayload read(FriendlyByteBuf buf) {
		return new SetFrozenPayload(buf.readBoolean());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeBoolean(this.frozen);
	}

	@Override
	public Type<SetFrozenPayload> type() {
		return TYPE;
	}
}
