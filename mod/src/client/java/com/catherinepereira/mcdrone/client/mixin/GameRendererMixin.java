package com.catherinepereira.mcdrone.client.mixin;

import com.catherinepereira.mcdrone.entity.DroneEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	// hides the parked player's arm, it floats in front of a drone's camera, piloted or captured for the bridge
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void mcdrone$hideHandInDrone(CallbackInfo ci) {
		if (Minecraft.getInstance().getCameraEntity() instanceof DroneEntity) {
			ci.cancel();
		}
	}
}
