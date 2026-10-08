package com.catherinepereira.mcdrone.client;

import com.catherinepereira.mcdrone.Json;
import com.catherinepereira.mcdrone.client.task.TaskScorer;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** The drone's state as every observation carries it, see the state section of docs/PROTOCOL.md */
public final class StateJson {
	private StateJson() {
	}

	/** Empty without a drone. Drains the tool events, so each one goes out with a single observation */
	public static JsonObject of(Minecraft mc, DroneController controller, TaskScorer task, ToolState tools, int selectedSlot) {
		JsonObject json = new JsonObject();
		DroneEntity drone = controller.drone();
		if (drone == null || mc.level == null) {
			return json;
		}
		json.addProperty("droneId", drone.getId());
		json.add("pos", vec(drone.position()));
		json.add("vel", vec(controller.velocity()));
		json.addProperty("tier", drone.tier().id);
		json.add("battery", drone.batteryJson());
		json.addProperty("yaw", drone.getYRot());
		json.addProperty("pitch", drone.getXRot());
		Vec3 eye = drone.getEyePosition();
		Vec3 end = eye.add(drone.getLookAngle().scale(32.0));
		BlockHitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, drone));
		if (hit.getType() == HitResult.Type.BLOCK) {
			JsonObject looking = new JsonObject();
			looking.addProperty("block", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(hit.getBlockPos()).getBlock()).toString());
			looking.add("pos", Json.pos(hit.getBlockPos()));
			looking.addProperty("face", hit.getDirection().getName());
			looking.addProperty("dist", hit.getLocation().distanceTo(eye));
			json.add("lookingAt", looking);
		} else {
			json.add("lookingAt", JsonNull.INSTANCE);
		}
		json.addProperty("collided", controller.lastCollided());
		// lets scripts project world points into the frame, same frustum math as Raycaster
		RenderTarget frame = mc.gameRenderer.mainRenderTarget();
		JsonObject camera = new JsonObject();
		camera.addProperty("fov", mc.gameRenderer.mainCamera().getFov());
		camera.addProperty("windowAspect", (float) frame.width / frame.height);
		camera.addProperty("eyeHeight", drone.getEyeHeight());
		json.add("camera", camera);
		boolean episode = task.episodeId() != null;
		json.add("marker", episode ? Json.pos(task.marker()) : JsonNull.INSTANCE);
		// privileged layout for scripted experts and the dashboard minimap, DroneEnv keeps it out of the policy's observation
		json.add("arena", episode ? task.arena() : JsonNull.INSTANCE);
		// the job (boxes and schematic name) is the drone's instruction, so policies may read it too
		JsonObject arena = episode ? task.arena() : null;
		json.add("job", arena != null && arena.has("job") ? arena.get("job") : JsonNull.INSTANCE);
		// the geofence is part of the task, so policies may read it, unlike the arena layout
		if (episode) {
			JsonArray box = new JsonArray();
			for (double v : task.bounds()) {
				box.add(v);
			}
			json.add("bounds", box);
		} else {
			json.add("bounds", JsonNull.INSTANCE);
		}
		tools.addTo(json, mc.level, selectedSlot);
		json.add("events", tools.drainEvents());
		return json;
	}

	private static JsonArray vec(Vec3 v) {
		JsonArray a = new JsonArray();
		a.add(v.x);
		a.add(v.y);
		a.add(v.z);
		return a;
	}
}
