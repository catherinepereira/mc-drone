package com.catherinepereira.mcdrone.tool;

/** break holds across ticks, the others fire once per action */
public enum DroneTool {
	NONE,
	BREAK,
	PLACE,
	OPEN,
	CLOSE;

	public static DroneTool parse(String name) {
		for (DroneTool tool : values()) {
			if (tool.name().equalsIgnoreCase(name)) {
				return tool;
			}
		}
		return NONE;
	}
}
