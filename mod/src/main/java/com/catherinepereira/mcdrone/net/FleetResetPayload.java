package com.catherinepereira.mcdrone.net;

import com.catherinepereira.mcdrone.McDrone;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server, one training arena for several drones, each request a drone's task in it. Each gets its own TaskReadyPayload */
public record FleetResetPayload(List<ResetTaskPayload> members) implements CustomPacketPayload {
	public static final Type<FleetResetPayload> TYPE = new Type<>(McDrone.id("fleet_reset"));
	public static final StreamCodec<FriendlyByteBuf, FleetResetPayload> CODEC = CustomPacketPayload.codec(FleetResetPayload::write, FleetResetPayload::read);
	public static final int MAX_MEMBERS = 8;

	private static FleetResetPayload read(FriendlyByteBuf buf) {
		int n = Math.min(buf.readVarInt(), MAX_MEMBERS);
		List<ResetTaskPayload> members = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			members.add(ResetTaskPayload.CODEC.decode(buf));
		}
		return new FleetResetPayload(members);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.members.size());
		for (ResetTaskPayload member : this.members) {
			ResetTaskPayload.CODEC.encode(buf, member);
		}
	}

	@Override
	public Type<FleetResetPayload> type() {
		return TYPE;
	}
}
