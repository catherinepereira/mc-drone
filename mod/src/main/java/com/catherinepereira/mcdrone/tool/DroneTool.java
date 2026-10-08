package com.catherinepereira.mcdrone.tool;

import io.netty.buffer.ByteBuf;
import java.util.function.IntFunction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;

/** break and attack hold across ticks, the others fire once per action */
public enum DroneTool {
	NONE,
	BREAK,
	PLACE,
	OPEN,
	CLOSE,
	ATTACK;

	private static final IntFunction<DroneTool> BY_ID = ByIdMap.continuous(DroneTool::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
	public static final StreamCodec<ByteBuf, DroneTool> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, DroneTool::ordinal);

	public static DroneTool parse(String name) {
		for (DroneTool tool : values()) {
			if (tool.name().equalsIgnoreCase(name)) {
				return tool;
			}
		}
		return NONE;
	}
}
