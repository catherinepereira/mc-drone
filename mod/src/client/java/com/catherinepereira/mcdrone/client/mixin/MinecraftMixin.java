package com.catherinepereira.mcdrone.client.mixin;

import com.catherinepereira.mcdrone.client.McDroneClient;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * With the camera in a drone, vanilla aims the parked player's attack and use at the drone's crosshair.
 * Cancel them so the mouse buttons only drive the drone's tools
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	private static boolean mcdrone$piloting() {
		return Minecraft.getInstance().getCameraEntity() instanceof DroneEntity;
	}

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void mcdrone$blockAttack(CallbackInfoReturnable<Boolean> cir) {
		if (mcdrone$piloting()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void mcdrone$blockContinueAttack(boolean down, CallbackInfo ci) {
		if (mcdrone$piloting()) {
			ci.cancel();
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void mcdrone$blockUse(CallbackInfo ci) {
		if (mcdrone$piloting()) {
			McDroneClient.runtime().keyboard().onUseClick();
			ci.cancel();
		}
	}
}
