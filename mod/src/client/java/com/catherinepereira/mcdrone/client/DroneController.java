package com.catherinepereira.mcdrone.client;

import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.net.DronePosePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Simulates the drone on the client.
 * The client is authoritative for its own drone and sends the pose to the server after each step
 */
public final class DroneController {
	private final Minecraft mc;
	private final Config config;
	private int droneId = -1;
	private Vec3 velocity = Vec3.ZERO;
	private boolean piloting;
	private DroneAction lastAction = DroneAction.ZERO;
	private boolean lastCollided;
	private boolean lastHitDrone;
	// blocks per tick a drone with a flat battery sinks
	private static final double FLAT_SINK = 0.08;

	public DroneController(Minecraft mc, Config config) {
		this.mc = mc;
		this.config = config;
	}

	public @Nullable DroneEntity drone() {
		if (this.mc.level == null || this.droneId < 0) {
			return null;
		}
		return this.mc.level.getEntity(this.droneId) instanceof DroneEntity drone ? drone : null;
	}

	public int droneId() {
		return this.droneId;
	}

	public boolean piloting() {
		return this.piloting;
	}

	public Vec3 velocity() {
		return this.velocity;
	}

	public boolean lastCollided() {
		return this.lastCollided;
	}

	/** Whether the last collision was with another drone */
	public boolean lastHitDrone() {
		return this.lastHitDrone;
	}

	public DroneAction lastAction() {
		return this.lastAction;
	}

	public void adopt(DroneEntity drone) {
		DroneEntity previous = this.drone();
		if (previous != null && previous != drone) {
			previous.clientControlled = false;
		}
		this.droneId = drone.getId();
		this.velocity = Vec3.ZERO;
		drone.clientControlled = true;
	}

	/** Hands the drone's position back to the server */
	public void release() {
		DroneEntity drone = this.drone();
		if (drone != null) {
			drone.clientControlled = false;
		}
		this.droneId = -1;
		this.piloting = false;
	}

	/** Nearest loaded drone owned by the local player */
	public @Nullable DroneEntity findOwned() {
		if (this.mc.level == null || this.mc.player == null) {
			return null;
		}
		DroneEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (Entity entity : this.mc.level.entitiesForRendering()) {
			if (entity instanceof DroneEntity drone && drone.isOwnedBy(this.mc.player)) {
				double dist = drone.distanceToSqr(this.mc.player);
				if (dist < bestDist) {
					best = drone;
					bestDist = dist;
				}
			}
		}
		return best;
	}

	public boolean setPiloting(boolean on) {
		if (on) {
			DroneEntity drone = this.drone();
			if (drone == null) {
				drone = this.findOwned();
				if (drone == null) {
					return false;
				}
				this.adopt(drone);
			}
			this.mc.options.setCameraType(CameraType.FIRST_PERSON);
			this.mc.setCameraEntity(drone);
			if (this.mc.player != null) {
				// keyboard piloting reads mouse deltas from the player's rotation, so start them aligned
				this.mc.player.setYRot(drone.getYRot());
				this.mc.player.setXRot(drone.getXRot());
			}
			this.piloting = true;
		} else {
			if (this.mc.player != null) {
				this.mc.setCameraEntity(this.mc.player);
			}
			this.piloting = false;
		}
		return true;
	}

	/** Drops piloting if the drone despawned or the level changed */
	public void validate() {
		if (this.droneId >= 0 && this.drone() == null) {
			if (this.piloting) {
				this.setPiloting(false);
			}
			this.droneId = -1;
		}
	}

	/** Returns true when a block or entity stopped the drone on any axis this tick */
	public boolean simulate(DroneAction action) {
		DroneEntity drone = this.drone();
		if (drone == null) {
			return false;
		}
		float yaw = drone.getYRot() + action.yaw();
		float pitch = Mth.clamp(drone.getXRot() + action.pitch(), -90.0F, 90.0F);
		double rad = Math.toRadians(yaw);
		double fx = -Math.sin(rad);
		double fz = Math.cos(rad);
		double rx = -Math.cos(rad);
		double rz = -Math.sin(rad);
		Vec3 target = new Vec3(
			(fx * action.forward() + rx * action.right()) * this.config.maxSpeed,
			action.up() * this.config.maxVerticalSpeed,
			(fz * action.forward() + rz * action.right()) * this.config.maxSpeed
		);
		if (drone.flat()) {
			// out of charge, the rotors spin down and it sinks to the ground wherever it is
			target = new Vec3(0.0, -FLAT_SINK, 0.0);
		}
		this.velocity = this.velocity.add(target.subtract(this.velocity).scale(this.config.smoothing));

		drone.setYRot(yaw);
		drone.setXRot(pitch);
		Vec3 before = drone.position();
		drone.move(MoverType.SELF, this.velocity);
		Vec3 moved = drone.position().subtract(before);
		boolean stoppedX = Math.abs(moved.x - this.velocity.x) > 1.0E-4;
		boolean stoppedY = Math.abs(moved.y - this.velocity.y) > 1.0E-4;
		boolean stoppedZ = Math.abs(moved.z - this.velocity.z) > 1.0E-4;
		// kill velocity on any axis a wall stopped, so the drone doesn't push into it forever
		this.velocity = new Vec3(stoppedX ? moved.x : this.velocity.x, stoppedY ? moved.y : this.velocity.y, stoppedZ ? moved.z : this.velocity.z);
		this.lastAction = action;
		this.lastCollided = stoppedX || stoppedY || stoppedZ;
		this.lastHitDrone = this.lastCollided && !drone.level().getEntities(drone, drone.getBoundingBox().inflate(1.0E-3), e -> e instanceof DroneEntity).isEmpty();
		return this.lastCollided;
	}

	public void setPose(double x, double y, double z, float yaw, float pitch) {
		DroneEntity drone = this.drone();
		if (drone == null) {
			return;
		}
		drone.snapTo(x, y, z, yaw, pitch);
		drone.setOldPosAndRot();
		this.velocity = Vec3.ZERO;
	}

	/** Makes the next rendered frame show the current pose exactly instead of interpolating from the last tick */
	public void pinRenderPose() {
		DroneEntity drone = this.drone();
		if (drone != null) {
			drone.setOldPosAndRot();
		}
	}

	public void sendPose() {
		DroneEntity drone = this.drone();
		if (drone != null && ClientPlayNetworking.canSend(DronePosePayload.TYPE)) {
			ClientPlayNetworking.send(new DronePosePayload(drone.getId(), drone.getX(), drone.getY(), drone.getZ(), drone.getYRot(), drone.getXRot()));
		}
	}
}
