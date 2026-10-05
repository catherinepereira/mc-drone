package com.catherinepereira.mcdrone.client.mixin;

import com.catherinepereira.mcdrone.client.ClientRuntime;
import com.catherinepereira.mcdrone.client.McDroneClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	// hides the parked player's arm, it floats in front of the drone camera
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void mcdrone$hideHandWhilePiloting(CallbackInfo ci) {
		ClientRuntime runtime = McDroneClient.runtime();
		if (runtime != null && runtime.controller().piloting()) {
			ci.cancel();
		}
	}
}
