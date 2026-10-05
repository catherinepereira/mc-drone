package com.catherinepereira.mcdrone.net;

import com.catherinepereira.mcdrone.McDrone;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server, save the box between two corners as name in the schematics folder */
public record ExportSchematicPayload(BlockPos cornerA, BlockPos cornerB, String name) implements CustomPacketPayload {
	public static final Type<ExportSchematicPayload> TYPE = new Type<>(McDrone.id("export_schematic"));
	public static final StreamCodec<FriendlyByteBuf, ExportSchematicPayload> CODEC = CustomPacketPayload.codec(ExportSchematicPayload::write, ExportSchematicPayload::read);

	private static ExportSchematicPayload read(FriendlyByteBuf buf) {
		return new ExportSchematicPayload(buf.readBlockPos(), buf.readBlockPos(), buf.readUtf(256));
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeBlockPos(this.cornerA);
		buf.writeBlockPos(this.cornerB);
		buf.writeUtf(this.name);
	}

	@Override
	public Type<ExportSchematicPayload> type() {
		return TYPE;
	}
}
