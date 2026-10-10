package com.catherinepereira.mcdrone.task;

import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.entity.DroneItem;
import com.catherinepereira.mcdrone.entity.DroneTier;
import com.catherinepereira.mcdrone.entity.Drones;
import com.catherinepereira.mcdrone.net.ResetTaskPayload;
import com.catherinepereira.mcdrone.net.TaskReadyPayload;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Starts the player's jobs in their own world, within reach of where they stand, see Jobs in docs/PROTOCOL.md */
public final class PlayerJobs {
	private static final int JOB_REACH = 96;
	private static final int JOB_MARGIN = 6;

	private PlayerJobs() {
	}

	/**
	 * A copy, build, mine, harvest, patrol, guard, fly-to, seek, follow, or return-home job in the player's own world: nothing is built or cleared, the drone keeps its inventory, and
	 * the player stays put. Every box has to be within JOB_REACH of the player, which keeps it in loaded chunks
	 */
	static TaskReadyPayload start(ServerPlayer player, ResetTaskPayload req, TaskKind kind) {
		ServerLevel level = player.level();
		int[] r = req.region();
		if (r.length != 9 && r.length != 15) {
			throw new IllegalArgumentException("a job needs its box corners and a paste point, and optionally a gather box");
		}
		BlockPos a = new BlockPos(r[0], r[1], r[2]);
		BlockPos b = new BlockPos(r[3], r[4], r[5]);
		BlockPos dest = new BlockPos(r[6], r[7], r[8]);
		DroneEntity drone = Drones.resolve(player, req.droneId());
		// where a search's blocks are, for the client's scoring only, the drone finds them with its camera
		List<BlockPos> found = new ArrayList<>();
		DroneJob job = switch (kind) {
			case RETURN_HOME -> {
				if (drone == null || drone.home() == null) {
					throw new IllegalArgumentException("the drone has no charging station, right click one with the tablet");
				}
				yield new DockJob(drone.home());
			}
			case COPY_REGION -> BuildJob.copy(level, a, b, dest);
			case BUILD_SCHEMATIC -> {
				Path file = Arena.schematicFile(req.subject());
				try {
					yield BuildJob.build(Schematic.read(file), req.subject(), dest);
				} catch (IOException e) {
					throw new IllegalArgumentException("could not read " + req.subject() + ": " + e.getMessage(), e);
				}
			}
			case MINE_REGION -> MineJob.start(level, a, b, req.subject(), drone != null ? drone.tier() : DroneTier.COPPER);
			case HARVEST_REGION -> HarvestJob.start(level, a, b, req.subject());
			case PATROL_REGION, GUARD_REGION -> PatrolJob.start(level, a, b, req.subject(), kind == TaskKind.GUARD_REGION, Prey.parse(req.prey()));
			case FLY_TO -> GotoJob.point(dest);
			case SEEK_BLOCK -> GotoJob.block(level, a, b, req.subject(), found);
			case FOLLOW_PLAYER -> GotoJob.follow(player, 0);
			default -> throw new IllegalArgumentException(kind.id + " is not a job");
		};
		if (r.length == 15) {
			if (!(job instanceof BuildJob build)) {
				throw new IllegalArgumentException("only copy and build jobs gather their materials");
			}
			gather(build, new BlockPos(r[9], r[10], r[11]), new BlockPos(r[12], r[13], r[14]));
		}
		BlockPos[] corners = job.corners();
		for (BlockPos corner : corners) {
			if (!inReach(player, corner)) {
				throw new IllegalArgumentException("the job reaches " + corner.toShortString() + ", more than " + JOB_REACH + " blocks from you");
			}
		}
		if (drone != null) {
			checkClear(level, drone, job, corners);
		}

		Vec3 start = drone != null ? drone.position() : player.getEyePosition().add(0, 1.0, 0);
		if (drone == null) {
			drone = DroneItem.spawnFor(level, player, start, player.getYRot(), DroneTier.COPPER);
		}
		drone.trainingArena = false;

		// the geofence: the job's boxes and where the drone starts, plus room to fly around and over them
		BlockPos lo = BlockPos.containing(start);
		BlockPos hi = lo;
		for (BlockPos c : corners) {
			lo = BlockPos.min(lo, c);
			hi = BlockPos.max(hi, c);
		}
		// the home station too, so the drone can fly back to charge mid-job
		BlockPos home = drone.home();
		if (home != null && inReach(player, home)) {
			lo = BlockPos.min(lo, home);
			hi = BlockPos.max(hi, home.above());
		}
		ArenaRecord record = new ArenaRecord(kind, corners[0], 0);
		record.job = job;
		record.targets.addAll(found);
		record.tier = drone.tier();
		if (req.scan()) {
			record.scan = Scans.write(level, job, kind.id + "-" + Long.toHexString(req.seed()));
		}
		record.fence = new int[] {lo.getX() - JOB_MARGIN, lo.getY() - 2, lo.getZ() - JOB_MARGIN, hi.getX() + JOB_MARGIN + 1, hi.getY() + JOB_MARGIN + 1, hi.getZ() + JOB_MARGIN + 1};
		Arena.put(drone, record);
		return new TaskReadyPayload(
			req.requestId(), drone.getId(), corners[0], corners[0], start.x, start.y, start.z, drone.getYRot(), record.toJson().toString(), ""
		);
	}

	// two drones working the same blocks would undo each other, so a job's boxes can't touch another drone's running job
	private static void checkClear(ServerLevel level, DroneEntity drone, DroneJob job, BlockPos[] corners) {
		if (!job.changesBlocks()) {
			return;
		}
		for (DroneEntity other : Arena.working(level, drone)) {
			DroneJob their = Arena.record(other).job;
			if (!their.changesBlocks()) {
				continue;
			}
			BlockPos[] theirs = their.corners();
			for (int i = 0; i + 1 < corners.length; i += 2) {
				for (int j = 0; j + 1 < theirs.length; j += 2) {
					if (DroneJob.overlaps(corners[i], corners[i + 1], theirs[j], theirs[j + 1])) {
						throw new IllegalArgumentException(other.shownName() + " is working there, wait for its job or pick another place");
					}
				}
			}
		}
	}

	private static boolean inReach(ServerPlayer player, BlockPos pos) {
		return pos.distManhattan(player.blockPosition()) <= JOB_REACH * 2 && Math.abs(pos.getX() - player.getBlockX()) <= JOB_REACH
			&& Math.abs(pos.getZ() - player.getBlockZ()) <= JOB_REACH;
	}

	/**
	 * Has a copy or build job mine its materials from the box between a and b first. The box can't touch the copy's source,
	 * which the drone must leave as it is, or the destination it builds in
	 */
	private static void gather(BuildJob job, BlockPos a, BlockPos b) {
		BlockPos min = BlockPos.min(a, b);
		BlockPos max = BlockPos.max(a, b);
		DroneJob.checkSize(max.subtract(min).offset(1, 1, 1), MineJob.MAX_SIDE, "gather region");
		if (job.sourceMin != null && DroneJob.overlaps(min, max, job.sourceMin, job.sourceMin.offset(job.target.size()).offset(-1, -1, -1))) {
			throw new IllegalArgumentException("the gather region overlaps the copy's source, which the drone doesn't mine");
		}
		if (DroneJob.overlaps(min, max, job.dest, job.destMax())) {
			throw new IllegalArgumentException("the gather region overlaps where the drone builds");
		}
		job.gatherFrom(min, max);
	}
}
