package com.catherinepereira.mcdrone.tool;

import net.minecraft.network.FriendlyByteBuf;

/**
 * One tick of tool intent from the client.
 * transfer moves a stack between the drone and the open container: toSlot -1 picks the first slot that fits, count 0 moves the whole stack.
 * block, when set, names the block to place: the drone swaps a stack of it into the first slot and places from there, or
 * with unlimited set conjures it
 */
public record ToolRequest(DroneTool tool, int slot, int transfer, int fromSlot, int toSlot, int count, String block, boolean unlimited) {
	public static final int NO_TRANSFER = 0;
	public static final int DRONE_TO_CONTAINER = 1;
	public static final int CONTAINER_TO_DRONE = 2;
	public static final ToolRequest IDLE = new ToolRequest(DroneTool.NONE, 0, NO_TRANSFER, 0, -1, 0);

	public ToolRequest(DroneTool tool, int slot, int transfer, int fromSlot, int toSlot, int count) {
		this(tool, slot, transfer, fromSlot, toSlot, count, "", false);
	}

	public boolean idle() {
		return this.tool == DroneTool.NONE && this.transfer == NO_TRANSFER;
	}

	/** Same request with the one-shot parts removed, for the 2nd and later ticks of a multi-tick step */
	public ToolRequest continued() {
		return new ToolRequest(this.tool == DroneTool.BREAK ? DroneTool.BREAK : DroneTool.NONE, this.slot, NO_TRANSFER, 0, -1, 0, "", this.unlimited);
	}

	public ToolRequest withMaterials(boolean unlimited) {
		return new ToolRequest(this.tool, this.slot, this.transfer, this.fromSlot, this.toSlot, this.count, this.block, unlimited);
	}

	public void write(FriendlyByteBuf buf) {
		DroneTool.STREAM_CODEC.encode(buf, this.tool);
		buf.writeVarInt(this.slot);
		buf.writeVarInt(this.transfer);
		buf.writeVarInt(this.fromSlot);
		buf.writeVarInt(this.toSlot);
		buf.writeVarInt(this.count);
		buf.writeUtf(this.block);
		buf.writeBoolean(this.unlimited);
	}

	public static ToolRequest read(FriendlyByteBuf buf) {
		return new ToolRequest(
			DroneTool.STREAM_CODEC.decode(buf), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(256), buf.readBoolean()
		);
	}
}
