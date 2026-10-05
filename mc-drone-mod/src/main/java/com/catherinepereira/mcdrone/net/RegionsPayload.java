package com.catherinepereira.mcdrone.net;

import com.catherinepereira.mcdrone.McDrone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client, the world's named regions as a JSON array, sent on join and after every edit */
public record RegionsPayload(String json) implements CustomPacketPayload {
	public static final Type<RegionsPayload> TYPE = new Type<>(McDrone.id("regions"));
	public static final StreamCodec<FriendlyByteBuf, RegionsPayload> CODEC = CustomPacketPayload.codec(RegionsPayload::write, RegionsPayload::read);

	private static RegionsPayload read(FriendlyByteBuf buf) {
		return new RegionsPayload(buf.readUtf(1 << 20));
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(this.json, 1 << 20);
	}

	@Override
	public Type<RegionsPayload> type() {
		return TYPE;
	}
}
