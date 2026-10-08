package com.catherinepereira.mcdrone.task;

public enum TaskKind {
	NAVIGATE_TO("navigate_to"),
	DIG_BLOCK("dig_block"),
	PLACE_BLOCK("place_block"),
	CHEST_TRANSFER("chest_transfer"),
	MINE_AND_DELIVER("mine_and_deliver"),
	REPLICATE_BUILD("replicate_build"),
	COPY_REGION("copy_region"),
	BUILD_SCHEMATIC("build_schematic"),
	MINE_REGION("mine_region"),
	HARVEST_CROPS("harvest_crops"),
	HARVEST_REGION("harvest_region"),
	COPY_BUILD("copy_build"),
	SCHEMATIC_BUILD("schematic_build"),
	MINE_DEPOSIT("mine_deposit"),
	GATHER_BUILD("gather_build"),
	RETURN_HOME("return_home"),
	PATROL_AREA("patrol_area"),
	HUNT_MOBS("hunt_mobs"),
	PATROL_REGION("patrol_region"),
	GUARD_REGION("guard_region"),
	GOTO_POINT("goto_point"),
	FIND_BLOCK("find_block"),
	FOLLOW_MOB("follow_mob"),
	FLY_TO("fly_to"),
	SEEK_BLOCK("seek_block"),
	FOLLOW_PLAYER("follow_player");

	/** Jobs run in the player's own world, the rest build a training arena */
	public boolean isJob() {
		return this == COPY_REGION || this == BUILD_SCHEMATIC || this == MINE_REGION || this == HARVEST_REGION || this == RETURN_HOME
			|| this == PATROL_REGION || this == GUARD_REGION || this == FLY_TO || this == SEEK_BLOCK || this == FOLLOW_PLAYER;
	}

	/** Tasks whose target moves, lockstep leaves the world running for them */
	public boolean needsLiveWorld() {
		return this == HUNT_MOBS || this == GUARD_REGION || this == FOLLOW_MOB || this == FOLLOW_PLAYER;
	}

	public final String id;

	TaskKind(String id) {
		this.id = id;
	}

	public static TaskKind parse(String id) {
		for (TaskKind kind : values()) {
			if (kind.id.equals(id)) {
				return kind;
			}
		}
		throw new IllegalArgumentException("unknown task '" + id + "'");
	}
}
