package com.catherinepereira.mcdrone.task;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public final class DroneContainers {
	private DroneContainers() {
	}

	/** The container at pos, joining double chests the way a player opening them would see them */
	public static @Nullable Container resolve(ServerLevel level, @Nullable BlockPos pos) {
		if (pos == null) {
			return null;
		}
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock chest) {
			return ChestBlock.getContainer(chest, state, level, pos, true);
		}
		return level.getBlockEntity(pos) instanceof Container container ? container : null;
	}
}
