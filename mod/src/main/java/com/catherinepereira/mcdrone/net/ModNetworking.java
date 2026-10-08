package com.catherinepereira.mcdrone.net;

import com.catherinepereira.mcdrone.Json;
import com.catherinepereira.mcdrone.McDrone;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.entity.Drones;
import com.catherinepereira.mcdrone.task.Arena;
import com.catherinepereira.mcdrone.task.BuildJob;
import com.catherinepereira.mcdrone.task.Region;
import com.catherinepereira.mcdrone.task.RegionStore;
import com.catherinepereira.mcdrone.task.Schematic;
import com.catherinepereira.mcdrone.tool.DroneTools;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public final class ModNetworking {
	private ModNetworking() {
	}

	// drone poses and tools skip survival permission checks, arena resets rewrite blocks and teleport, freezing
	// stops the whole world, and exports and region edits write files, so a LAN guest gets none of them
	private static boolean isHost(MinecraftServer server, ServerPlayer player) {
		return !server.isDedicatedServer() && server.isSingleplayerOwner(player.nameAndId());
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(DronePosePayload.TYPE, DronePosePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ResetTaskPayload.TYPE, ResetTaskPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(FleetResetPayload.TYPE, FleetResetPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetFrozenPayload.TYPE, SetFrozenPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DroneToolPayload.TYPE, DroneToolPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ExportSchematicPayload.TYPE, ExportSchematicPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RegionEditPayload.TYPE, RegionEditPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RenameDronePayload.TYPE, RenameDronePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SelectDronePayload.TYPE, SelectDronePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DroneQueuePayload.TYPE, DroneQueuePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TaskEndPayload.TYPE, TaskEndPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RegionsPayload.TYPE, RegionsPayload.CODEC);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			sender.sendPacket(new RegionsPayload(RegionStore.get(server).toJson().toString()))
		);

		ServerPlayNetworking.registerGlobalReceiver(RegionEditPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!isHost(context.server(), player)) {
				return;
			}
			RegionStore store = RegionStore.get(context.server());
			String result;
			try {
				JsonObject edit = JsonParser.parseString(payload.json()).getAsJsonObject();
				if (edit.get("op").getAsString().equals("remove")) {
					result = store.remove(edit.get("id").getAsString()) ? "Region removed" : "No such region";
				} else {
					BlockPos[] box = Json.readBox(edit.getAsJsonArray("box"));
					Region region = store.put(edit.get("name").getAsString(), Region.Purpose.parse(edit.get("purpose").getAsString()), box[0], box[1]);
					result = "Saved " + region.purpose().id() + " region " + region.name();
				}
			} catch (RuntimeException e) {
				result = "Region not saved: " + e.getMessage();
			}
			player.sendOverlayMessage(Component.literal(result));
			String json = store.toJson().toString();
			for (ServerPlayer p : context.server().getPlayerList().getPlayers()) {
				ServerPlayNetworking.send(p, new RegionsPayload(json));
			}
		});
		PayloadTypeRegistry.clientboundPlay().register(DroneSyncPayload.TYPE, DroneSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TaskReadyPayload.TYPE, TaskReadyPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(DronePosePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			Entity entity = player.level().getEntity(payload.entityId());
			if (isHost(context.server(), player) && entity instanceof DroneEntity drone && drone.isOwnedBy(player)) {
				drone.onPose(payload.x(), payload.y(), payload.z(), payload.yaw(), payload.pitch());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(DroneToolPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			Entity entity = player.level().getEntity(payload.entityId());
			if (isHost(context.server(), player) && entity instanceof DroneEntity drone && drone.isOwnedBy(player)) {
				context.responseSender().sendPacket(DroneTools.apply(drone, payload.seq(), payload.request()));
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(ResetTaskPayload.TYPE, (payload, context) -> reset(context, List.of(payload)));
		ServerPlayNetworking.registerGlobalReceiver(FleetResetPayload.TYPE, (payload, context) -> reset(context, payload.members()));

		ServerPlayNetworking.registerGlobalReceiver(ExportSchematicPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!isHost(context.server(), player)) {
				return;
			}
			String result;
			try {
				BlockPos a = payload.cornerA();
				BlockPos b = payload.cornerB();
				BlockPos min = BlockPos.min(a, b);
				BlockPos max = BlockPos.max(a, b);
				BlockPos size = max.subtract(min).offset(1, 1, 1);
				if (size.getX() > BuildJob.MAX_SIZE || size.getY() > BuildJob.MAX_SIZE || size.getZ() > BuildJob.MAX_SIZE) {
					throw new IllegalArgumentException("the selection is " + size.toShortString() + ", at most " + BuildJob.MAX_SIZE + " per side");
				}
				Schematic.fromWorld(player.level(), min, max).write(Arena.schematicFile(payload.name()));
				result = "Saved " + payload.name() + " (" + size.toShortString() + ")";
			} catch (IllegalArgumentException | IOException e) {
				result = "Export failed: " + e.getMessage();
			}
			player.sendOverlayMessage(Component.literal(result));
		});

		ServerPlayNetworking.registerGlobalReceiver(RenameDronePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			DroneEntity drone = Drones.active(player);
			if (drone != null) {
				drone.rename(payload.name());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(DroneQueuePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!(player.level().getEntity(payload.droneId()) instanceof DroneEntity drone) || !drone.isOwnedBy(player)) {
				return;
			}
			JsonObject edit;
			try {
				edit = JsonParser.parseString(payload.edit()).getAsJsonObject();
			} catch (RuntimeException e) {
				return;
			}
			JsonArray queue = drone.queue();
			switch (edit.has("op") ? edit.get("op").getAsString() : "") {
				case "add" -> {
					if (queue.size() >= DroneEntity.MAX_QUEUE) {
						player.sendOverlayMessage(Component.literal("The queue is full, " + DroneEntity.MAX_QUEUE + " jobs at most"));
						return;
					}
					if (edit.has("job") && edit.get("job").isJsonObject()) {
						queue.add(edit.getAsJsonObject("job"));
					}
				}
				case "remove" -> {
					int index = edit.has("index") ? edit.get("index").getAsInt() : -1;
					if (index >= 0 && index < queue.size()) {
						queue.remove(index);
					}
				}
				case "clear" -> queue = new JsonArray();
				default -> {
					return;
				}
			}
			drone.setQueue(queue);
		});

		ServerPlayNetworking.registerGlobalReceiver(TaskEndPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player.level().getEntity(payload.droneId()) instanceof DroneEntity drone && drone.isOwnedBy(player)) {
				Arena.endJob(drone);
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(SelectDronePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player.level().getEntity(payload.droneId()) instanceof DroneEntity drone && drone.isOwnedBy(player)) {
				Drones.setActive(player, drone);
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(SetFrozenPayload.TYPE, (payload, context) -> {
			if (isHost(context.server(), context.player())) {
				context.server().tickRateManager().setFrozen(payload.frozen());
			}
		});
	}

	// builds the arena or starts the job, one answer per request, a failure answers every one of them
	private static void reset(ServerPlayNetworking.Context context, List<ResetTaskPayload> requests) {
		ServerPlayer player = context.player();
		List<TaskReadyPayload> replies;
		try {
			if (!isHost(context.server(), player)) {
				throw new IllegalArgumentException("only the singleplayer host can reset the arena");
			}
			replies = Arena.reset(player, requests);
		} catch (IllegalArgumentException e) {
			replies = requests.stream().map(r -> TaskReadyPayload.failed(r.requestId(), e.getMessage())).toList();
		} catch (RuntimeException e) {
			McDrone.LOGGER.error("task reset failed", e);
			replies = requests.stream().map(r -> TaskReadyPayload.failed(r.requestId(), e.toString())).toList();
		}
		replies.forEach(context.responseSender()::sendPacket);
	}
}
