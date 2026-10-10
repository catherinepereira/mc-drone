package com.catherinepereira.mcdrone.task;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

/** Which mobs a hunt or guard goes after: every hostile one, every mob, or the mobs of some kinds. Never players or drones */
public record Prey(Mode mode, Set<String> kinds) {
	public enum Mode {
		HOSTILE,
		ALL,
		KINDS
	}

	public static final Prey HOSTILE = new Prey(Mode.HOSTILE, Set.of());
	public static final Prey ALL = new Prey(Mode.ALL, Set.of());

	/** "hostile" or blank, "all", or mob kinds separated by commas such as "cow, minecraft:zombie" */
	public static Prey parse(String spec) {
		String s = spec.trim();
		if (s.isEmpty() || s.equals("hostile")) {
			return HOSTILE;
		}
		if (s.equals("all")) {
			return ALL;
		}
		Set<String> kinds = new TreeSet<>();
		for (String part : s.split(",")) {
			String name = part.trim();
			if (name.isEmpty()) {
				continue;
			}
			Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
			if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
				throw new IllegalArgumentException("unknown mob " + name + ", try hostile, all, or kinds such as cow, zombie");
			}
			kinds.add(id.toString());
		}
		if (kinds.isEmpty()) {
			throw new IllegalArgumentException("prey is hostile, all, or mob kinds such as cow, zombie");
		}
		return of(kinds);
	}

	public static Prey of(Set<String> kinds) {
		return new Prey(Mode.KINDS, Set.copyOf(kinds));
	}

	public boolean matches(Entity entity) {
		// a drone is no Mob, nor is a player
		if (!(entity instanceof Mob) || !entity.isAlive()) {
			return false;
		}
		return switch (this.mode) {
			case HOSTILE -> entity instanceof Enemy;
			case ALL -> true;
			case KINDS -> this.kinds.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
		};
	}

	/** The spec parse reads back */
	public String spec() {
		return switch (this.mode) {
			case HOSTILE -> "hostile";
			case ALL -> "all";
			case KINDS -> String.join(",", new TreeSet<>(this.kinds));
		};
	}

	/** "hostile", "all", or the kinds as a list, as the drone's job reads it */
	public JsonElement toJson() {
		if (this.mode != Mode.KINDS) {
			return new JsonPrimitive(this.spec());
		}
		JsonArray out = new JsonArray();
		List.copyOf(new TreeSet<>(this.kinds)).forEach(out::add);
		return out;
	}
}
