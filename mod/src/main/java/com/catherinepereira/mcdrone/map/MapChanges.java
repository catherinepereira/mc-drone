package com.catherinepereira.mcdrone.map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Where drones and arenas changed blocks, so a web map can render them again, see BlueMapMarkers.
 * Holds no BlueMap types, so the rest of the mod can call it whether or not BlueMap is installed
 */
public final class MapChanges {
	public interface Listener {
		void changed(ServerLevel level, BlockPos min, BlockPos max);
	}

	private static @Nullable Listener listener;

	private MapChanges() {
	}

	static void listen(Listener l) {
		listener = l;
	}

	/** Blocks between the two corners changed */
	public static void changed(ServerLevel level, BlockPos a, BlockPos b) {
		Listener l = listener;
		if (l != null) {
			l.changed(level, BlockPos.min(a, b), BlockPos.max(a, b));
		}
	}
}
