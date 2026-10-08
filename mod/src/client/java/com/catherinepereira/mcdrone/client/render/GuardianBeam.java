package com.catherinepereira.mcdrone.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The guardian's attack beam, drawn the way vanilla's GuardianRenderer draws it (its method is private): a twisting
 * square tube with the guardian beam texture, purple while it charges and yellow once it's about to fire
 */
final class GuardianBeam {
	private static final RenderType TYPE = RenderTypes.entityCutout(Identifier.withDefaultNamespace("textures/entity/guardian/guardian_beam.png"));
	private static final float INNER = 0.2F;
	private static final float OUTER = 0.282F;

	private GuardianBeam() {
	}

	/** beam runs from the pose's origin, time is in ticks, scale is the charge from 0 to 1 */
	static void submit(PoseStack poseStack, SubmitNodeCollector collector, Vec3 beam, float time, float scale) {
		float length = (float) (beam.length() + 1.0);
		Vec3 dir = beam.normalize();
		poseStack.rotateDegrees(Axis.YP, ((float) (Math.PI / 2) - (float) Math.atan2(dir.z, dir.x)) * Mth.RAD_TO_DEG);
		poseStack.rotateDegrees(Axis.XP, (float) Math.acos(dir.y) * Mth.RAD_TO_DEG);
		float rot = time * 0.05F * -1.5F;
		float colorScale = scale * scale;
		int red = 64 + (int) (colorScale * 191.0F);
		int green = 32 + (int) (colorScale * 191.0F);
		int blue = 128 - (int) (colorScale * 64.0F);
		float minV = -1.0F + time * 0.5F % 1.0F;
		float maxV = minV + length * 2.5F;
		float capV = Mth.floor(time) % 2 == 0 ? 0.5F : 0.0F;
		collector.submitCustomGeometry(poseStack, TYPE, (pose, buffer) -> {
			// two crossed quads along the beam
			for (float a : new float[] {(float) Math.PI, (float) (Math.PI / 2)}) {
				float x0 = Mth.cos(rot + a) * INNER;
				float z0 = Mth.sin(rot + a) * INNER;
				float x1 = Mth.cos(rot + a - (float) Math.PI) * INNER;
				float z1 = Mth.sin(rot + a - (float) Math.PI) * INNER;
				vertex(buffer, pose, x0, length, z0, red, green, blue, 0.4999F, maxV);
				vertex(buffer, pose, x0, 0.0F, z0, red, green, blue, 0.4999F, minV);
				vertex(buffer, pose, x1, 0.0F, z1, red, green, blue, 0.0F, minV);
				vertex(buffer, pose, x1, length, z1, red, green, blue, 0.0F, maxV);
			}
			// the glowing cap at the far end
			vertex(buffer, pose, Mth.cos(rot + (float) (Math.PI * 3.0 / 4.0)) * OUTER, length, Mth.sin(rot + (float) (Math.PI * 3.0 / 4.0)) * OUTER, red, green, blue, 0.5F, capV + 0.5F);
			vertex(buffer, pose, Mth.cos(rot + (float) (Math.PI / 4)) * OUTER, length, Mth.sin(rot + (float) (Math.PI / 4)) * OUTER, red, green, blue, 1.0F, capV + 0.5F);
			vertex(buffer, pose, Mth.cos(rot + (float) (Math.PI * 7.0 / 4.0)) * OUTER, length, Mth.sin(rot + (float) (Math.PI * 7.0 / 4.0)) * OUTER, red, green, blue, 1.0F, capV);
			vertex(buffer, pose, Mth.cos(rot + (float) (Math.PI * 5.0 / 4.0)) * OUTER, length, Mth.sin(rot + (float) (Math.PI * 5.0 / 4.0)) * OUTER, red, green, blue, 0.5F, capV);
		});
	}

	private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, int red, int green, int blue, float u, float v) {
		buffer.addVertex(pose, x, y, z)
			.setColor(red, green, blue, 255)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(15728880)
			.setNormal(pose, 0.0F, 1.0F, 0.0F);
	}
}
