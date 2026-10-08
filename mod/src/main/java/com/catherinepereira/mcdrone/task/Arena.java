package com.catherinepereira.mcdrone.task;

import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.net.ResetTaskPayload;
import com.catherinepereira.mcdrone.net.TaskReadyPayload;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Each drone's current task, a training arena from TrainingArenas or a job from PlayerJobs, and where both find
 * schematic files
 */
public final class Arena {
	public static final Path SCHEMATICS = FabricLoader.getInstance().getGameDir().resolve("schematics");
	private static final Map<UUID, ArenaRecord> RECORDS = new ConcurrentHashMap<>();

	private Arena() {
	}

	public static @Nullable ArenaRecord record(DroneEntity drone) {
		return RECORDS.get(drone.getUUID());
	}

	static void put(DroneEntity drone, ArenaRecord record) {
		RECORDS.put(drone.getUUID(), record);
	}

	/** The drone's job ended, its boxes are free for other drones */
	public static void endJob(DroneEntity drone) {
		RECORDS.computeIfPresent(drone.getUUID(), (id, record) -> record.kind.isJob() ? null : record);
	}

	/** Every loaded drone in level with a job running, other than except */
	static List<DroneEntity> working(ServerLevel level, DroneEntity except) {
		List<DroneEntity> out = new ArrayList<>();
		RECORDS.forEach((id, record) -> {
			if (record.job != null && record.kind.isJob() && !id.equals(except.getUUID()) && level.getEntity(id) instanceof DroneEntity drone) {
				out.add(drone);
			}
		});
		return out;
	}

	/** Starts a job, builds a drone's training arena, or with several requests one arena they share */
	public static List<TaskReadyPayload> reset(ServerPlayer player, List<ResetTaskPayload> reqs) {
		if (reqs.size() > 1) {
			return TrainingArenas.buildShared(player, reqs);
		}
		ResetTaskPayload req = reqs.getFirst();
		TaskKind kind = TaskKind.parse(req.task());
		return List.of(kind.isJob() ? PlayerJobs.start(player, req, kind) : TrainingArenas.build(player, req));
	}

	/** A schematic in the game's schematics folder, by plain file name only so a request can't reach outside it */
	public static Path schematicFile(String name) {
		if (!name.matches("[A-Za-z0-9_. -]{1,64}\\.schem") || name.startsWith(".")) {
			throw new IllegalArgumentException("schematic names are letters, digits, spaces, dots, dashes, and underscores ending in .schem");
		}
		return SCHEMATICS.resolve(name);
	}
}
