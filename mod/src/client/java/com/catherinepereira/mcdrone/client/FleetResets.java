package com.catherinepereira.mcdrone.client;

import com.catherinepereira.mcdrone.net.FleetResetPayload;
import com.catherinepereira.mcdrone.net.ResetTaskPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.jspecify.annotations.Nullable;

/**
 * Holds the resets of a fleet group until every drone in it has asked, then has the server build one arena with a task
 * for each, see TrainingArenas.buildShared. A drone that finishes early waits in its reset for the others.
 * Members are ordered by their index, so the first member's seed lays out the arena whichever drone asked first
 */
final class FleetResets {
	private record Member(int index, ResetFlow flow, ResetTaskPayload request) {
	}

	private record Group(int size, List<Member> members) {
	}

	private final Map<String, Group> groups = new HashMap<>();

	/** Adds a drone's reset to its group, sending the group once it's full. Returns why it can't join, or null */
	@Nullable String join(String group, int size, int index, ResetFlow flow, ResetTaskPayload request) {
		if (size < 1 || size > FleetResetPayload.MAX_MEMBERS) {
			return "a fleet has 1 to " + FleetResetPayload.MAX_MEMBERS + " drones";
		}
		if (index < 0 || index >= size) {
			return "fleet member " + index + " is outside a fleet of " + size;
		}
		Group g = this.groups.computeIfAbsent(group, k -> new Group(size, new ArrayList<>()));
		if (g.size != size) {
			return "fleet group " + group + " has " + g.size + " drones, not " + size;
		}
		for (Member m : g.members) {
			if (m.index == index || m.request.droneId() == request.droneId()) {
				return "fleet group " + group + " already has member " + index + " or drone " + request.droneId();
			}
		}
		g.members.add(new Member(index, flow, request));
		if (g.members.size() == size) {
			this.groups.remove(group);
			List<ResetTaskPayload> requests = g.members.stream().sorted(Comparator.comparingInt(Member::index)).map(Member::request).toList();
			ClientPlayNetworking.send(new FleetResetPayload(requests));
		}
		return null;
	}

	/** A drone in a waiting group went away, its group can't fill up, so every reset waiting in it fails */
	void leave(ResetFlow flow) {
		this.groups.entrySet().removeIf(e -> {
			if (e.getValue().members.stream().noneMatch(m -> m.flow == flow)) {
				return false;
			}
			for (Member m : e.getValue().members) {
				m.flow.fail("a drone left fleet group " + e.getKey() + " before it filled up");
			}
			return true;
		});
	}
}
