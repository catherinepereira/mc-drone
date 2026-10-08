package com.catherinepereira.mcdrone.client.render;

import com.catherinepereira.mcdrone.entity.DroneTier;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class DroneRenderState extends EntityRenderState {
	public DroneTier tier = DroneTier.COPPER;
	public float yRot;
	public float cameraPitch;
	// degrees the body leans toward its motion, forward and to the right
	public float leanForward;
	public float leanRight;
	// flashes red while hurt, like a mob
	public boolean hurt;
	// the attack beam's end from the camera pod, how far it has charged from 0 to 1, and ticks for its animation
	public @Nullable Vec3 beam;
	public float beamScale;
	public float beamTime;
	public float eyeHeight;
}
