package com.catherinepereira.mcdrone.net;

import com.catherinepereira.mcdrone.McDrone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server, the drone's job ended, so its boxes are free for other drones' jobs */
public record TaskEndPayload(int droneId) implements CustomPacketPayload {
	public static final Type<TaskEndPayload> TYPE = new Type<>(McDrone.id("task_end"));
	public static final StreamCodec<FriendlyByteBuf, TaskEndPayload> CODEC = CustomPacketPayload.codec(TaskEndPayload::write, TaskEndPayload::read);

	private static TaskEndPayload read(FriendlyByteBuf buf) {
		return new TaskEndPayload(buf.readVarInt());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.droneId);
	}

	@Override
	public Type<TaskEndPayload> type() {
		return TYPE;
	}
}
