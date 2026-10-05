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
	HARVEST_REGION("harvest_region");

	/** Jobs run in the player's own world, the rest build a training arena */
	public boolean isJob() {
		return this == COPY_REGION || this == BUILD_SCHEMATIC || this == MINE_REGION || this == HARVEST_REGION;
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
