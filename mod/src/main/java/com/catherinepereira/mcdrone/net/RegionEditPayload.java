package com.catherinepereira.mcdrone.net;

import com.catherinepereira.mcdrone.McDrone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server, one region edit as JSON: {"op": "put", "name", "purpose", "box": [x0, y0, z0, x1, y1, z1]} or {"op": "remove", "id"} */
public record RegionEditPayload(String json) implements CustomPacketPayload {
	public static final Type<RegionEditPayload> TYPE = new Type<>(McDrone.id("region_edit"));
	public static final StreamCodec<FriendlyByteBuf, RegionEditPayload> CODEC = CustomPacketPayload.codec(RegionEditPayload::write, RegionEditPayload::read);

	private static RegionEditPayload read(FriendlyByteBuf buf) {
		return new RegionEditPayload(buf.readUtf(1 << 20));
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(this.json, 1 << 20);
	}

	@Override
	public Type<RegionEditPayload> type() {
		return TYPE;
	}
}
