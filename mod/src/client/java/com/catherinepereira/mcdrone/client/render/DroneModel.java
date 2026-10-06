package com.catherinepereira.mcdrone.client.render;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * A quadcopter: a body with a camera pod on the front, two crossed arms with a motor and a two-blade rotor at each end,
 * and landing skids. Model space has y down with the skids at y 24 and the front toward -z, like vanilla mob models
 */
public final class DroneModel extends EntityModel<DroneRenderState> {
	// motor centers sit this far out along x and z, the end of a 13 long arm turned 45 degrees
	private static final float REACH = 4.6F;
	private static final float RPM = 1.4F;

	private final ModelPart camera;
	private final ModelPart[] rotors = new ModelPart[4];

	public DroneModel(ModelPart root) {
		super(root);
		this.camera = root.getChild("camera");
		for (int i = 0; i < this.rotors.length; i++) {
			this.rotors[i] = root.getChild("rotor" + i);
		}
	}

	public static LayerDefinition createLayer() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		root.addOrReplaceChild(
			"body",
			CubeListBuilder.create().texOffs(0, 0).addBox(-3.0F, -2.0F, -3.0F, 6.0F, 2.0F, 6.0F).texOffs(0, 8).addBox(-2.0F, -3.0F, -2.0F, 4.0F, 1.0F, 4.0F),
			PartPose.offset(0.0F, 21.5F, 0.0F)
		);
		root.addOrReplaceChild(
			"camera",
			CubeListBuilder.create().texOffs(24, 0).addBox(-1.0F, -1.0F, -2.0F, 2.0F, 2.0F, 2.0F).texOffs(24, 4).addBox(-0.5F, -0.5F, -2.3F, 1.0F, 1.0F, 0.3F),
			PartPose.offset(0.0F, 21.0F, -3.0F)
		);
		CubeListBuilder arm = CubeListBuilder.create().texOffs(32, 0).addBox(-0.5F, -0.5F, -6.5F, 1.0F, 1.0F, 13.0F);
		root.addOrReplaceChild("arm0", arm, PartPose.offsetAndRotation(0.0F, 20.0F, 0.0F, 0.0F, Mth.PI / 4, 0.0F));
		root.addOrReplaceChild("arm1", arm, PartPose.offsetAndRotation(0.0F, 20.0F, 0.0F, 0.0F, -Mth.PI / 4, 0.0F));
		CubeListBuilder motor = CubeListBuilder.create().texOffs(0, 14).addBox(-1.0F, -1.5F, -1.0F, 2.0F, 2.0F, 2.0F);
		CubeListBuilder rotor = CubeListBuilder.create()
			.texOffs(0, 18)
			.addBox(-3.0F, -0.1F, -0.5F, 6.0F, 0.2F, 1.0F)
			.texOffs(0, 20)
			.addBox(-0.5F, -0.1F, -3.0F, 1.0F, 0.2F, 6.0F);
		float[][] corners = {{REACH, REACH}, {-REACH, REACH}, {-REACH, -REACH}, {REACH, -REACH}};
		for (int i = 0; i < corners.length; i++) {
			root.addOrReplaceChild("motor" + i, motor, PartPose.offset(corners[i][0], 20.0F, corners[i][1]));
			root.addOrReplaceChild("rotor" + i, rotor, PartPose.offset(corners[i][0], 18.3F, corners[i][1]));
		}
		CubeListBuilder rail = CubeListBuilder.create().texOffs(16, 14).addBox(-0.5F, 0.0F, -3.0F, 1.0F, 1.0F, 6.0F);
		CubeListBuilder strut = CubeListBuilder.create().texOffs(30, 14).addBox(-0.5F, 0.0F, -0.5F, 1.0F, 1.5F, 1.0F);
		for (int side = -1; side <= 1; side += 2) {
			String name = side < 0 ? "left" : "right";
			root.addOrReplaceChild("rail_" + name, rail, PartPose.offset(side * 2.0F, 23.0F, 0.0F));
			root.addOrReplaceChild("strut_front_" + name, strut, PartPose.offset(side * 2.0F, 21.5F, -1.5F));
			root.addOrReplaceChild("strut_back_" + name, strut, PartPose.offset(side * 2.0F, 21.5F, 1.5F));
		}
		return LayerDefinition.create(mesh, 64, 32);
	}

	@Override
	public void setupAnim(DroneRenderState state) {
		super.setupAnim(state);
		float spin = state.ageInTicks * RPM;
		for (int i = 0; i < this.rotors.length; i++) {
			// neighboring rotors turn opposite ways, as on a quadcopter
			this.rotors[i].yRot = i % 2 == 0 ? spin : -spin;
		}
		this.camera.xRot = Mth.clamp(state.cameraPitch, -30.0F, 90.0F) * Mth.DEG_TO_RAD;
	}
}
