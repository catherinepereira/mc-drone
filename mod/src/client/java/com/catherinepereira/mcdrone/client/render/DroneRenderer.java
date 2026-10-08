package com.catherinepereira.mcdrone.client.render;

import com.catherinepereira.mcdrone.McDrone;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.entity.DroneTier;
import com.catherinepereira.mcdrone.tool.DroneTools;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Draws the drone with DroneModel, leaning into its motion the way a quadcopter tilts to fly */
public final class DroneRenderer extends EntityRenderer<DroneEntity, DroneRenderState> {
	public static final ModelLayerLocation LAYER = new ModelLayerLocation(McDrone.id("drone"), "main");
	private static final float SCALE = 0.9F;
	// degrees of lean per block per tick of speed, and the most it leans
	private static final float LEAN_PER_SPEED = 40.0F;
	private static final float MAX_LEAN = 20.0F;

	private final DroneModel model;

	public DroneRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.model = new DroneModel(context.bakeLayer(LAYER));
		this.shadowRadius = 0.3F;
	}

	private static Identifier texture(DroneTier tier) {
		return McDrone.id("textures/entity/" + tier.id + "_drone.png");
	}

	@Override
	public DroneRenderState createRenderState() {
		return new DroneRenderState();
	}

	@Override
	public void extractRenderState(DroneEntity entity, DroneRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.tier = entity.tier();
		state.yRot = entity.getYRot(partialTicks);
		state.cameraPitch = entity.getXRot(partialTicks);
		double dx = entity.getX() - entity.xo;
		double dz = entity.getZ() - entity.zo;
		float yaw = state.yRot * Mth.DEG_TO_RAD;
		double forward = dx * -Mth.sin(yaw) + dz * Mth.cos(yaw);
		double right = dx * -Mth.cos(yaw) + dz * -Mth.sin(yaw);
		state.leanForward = Mth.clamp((float) forward * LEAN_PER_SPEED, -MAX_LEAN, MAX_LEAN);
		state.leanRight = Mth.clamp((float) right * LEAN_PER_SPEED, -MAX_LEAN, MAX_LEAN);
		state.eyeHeight = entity.getEyeHeight();
		state.hurt = entity.hurtTime > 0;
		state.beam = null;
		if (entity.level().getEntity(entity.beamTarget()) instanceof LivingEntity target) {
			Vec3 middle = target.getPosition(partialTicks).add(0.0, target.getBbHeight() * 0.5, 0.0);
			state.beam = middle.subtract(entity.getEyePosition(partialTicks));
			state.beamScale = Math.min(1.0F, (entity.beamCharge() + partialTicks) / DroneTools.BEAM_CHARGE);
			state.beamTime = entity.tickCount + partialTicks;
		}
	}

	@Override
	public void submit(DroneRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		poseStack.pushPose();
		poseStack.rotateDegrees(Axis.YP, 180.0F - state.yRot);
		// lean about the body's middle, nose down to fly forward and toward the side it slides to
		poseStack.translate(0.0F, 0.15F, 0.0F);
		poseStack.rotateDegrees(Axis.XP, -state.leanForward);
		poseStack.rotateDegrees(Axis.ZP, -state.leanRight);
		poseStack.translate(0.0F, -0.15F, 0.0F);
		poseStack.scale(-SCALE, -SCALE, SCALE);
		poseStack.translate(0.0F, EntityModel.MODEL_Y_OFFSET, 0.0F);
		collector.submitModel(this.model, state, poseStack, texture(state.tier), state.lightCoords, OverlayTexture.pack(OverlayTexture.u(0.0F), OverlayTexture.v(state.hurt)), state.outlineColor);
		poseStack.popPose();
		if (state.beam != null) {
			poseStack.pushPose();
			poseStack.translate(0.0F, state.eyeHeight, 0.0F);
			GuardianBeam.submit(poseStack, collector, state.beam, state.beamTime, state.beamScale);
			poseStack.popPose();
		}
		super.submit(state, poseStack, collector, camera);
	}
}
