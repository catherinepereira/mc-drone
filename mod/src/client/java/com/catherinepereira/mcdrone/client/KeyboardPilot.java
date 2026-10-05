package com.catherinepereira.mcdrone.client;

import com.catherinepereira.mcdrone.tool.DroneTool;
import com.catherinepereira.mcdrone.tool.ToolRequest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Turns WASD, space, shift, mouse movement, and mouse buttons into a DroneAction.
 * The mouse still rotates the parked player, so look comes from the player's rotation change since the last tick.
 * Left button held mines, a right click places from the selected slot, and the hotbar keys pick slots 0 to 8
 */
public final class KeyboardPilot {
	private float lastYaw;
	private float lastPitch;
	private int lastHotbar = -1;
	private boolean primed;
	private boolean useClicked;

	public void reset() {
		this.primed = false;
		this.useClicked = false;
	}

	/** Called by the input mixin when vanilla would have used the held item */
	public void onUseClick() {
		this.useClicked = true;
	}

	public DroneAction read(Minecraft mc, float maxLook, int selectedSlot) {
		LocalPlayer player = mc.player;
		if (player == null) {
			return DroneAction.ZERO;
		}
		float yaw = 0;
		float pitch = 0;
		if (this.primed) {
			yaw = Mth.clamp(Mth.wrapDegrees(player.getYRot() - this.lastYaw), -maxLook, maxLook);
			pitch = Mth.clamp(player.getXRot() - this.lastPitch, -maxLook, maxLook);
		}
		this.lastYaw = player.getYRot();
		this.lastPitch = player.getXRot();
		this.primed = true;

		int hotbar = player.getInventory().getSelectedSlot();
		int slot = selectedSlot;
		if (hotbar != this.lastHotbar) {
			if (this.lastHotbar >= 0) {
				slot = hotbar;
			}
			this.lastHotbar = hotbar;
		}

		boolean place = this.useClicked;
		this.useClicked = false;
		if (mc.gui.screen() != null) {
			return new DroneAction(0, 0, 0, yaw, pitch, new ToolRequest(DroneTool.NONE, slot, ToolRequest.NO_TRANSFER, 0, -1, 0));
		}
		var o = mc.options;
		float forward = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
		float right = (o.keyRight.isDown() ? 1 : 0) - (o.keyLeft.isDown() ? 1 : 0);
		float up = (o.keyJump.isDown() ? 1 : 0) - (o.keyShift.isDown() ? 1 : 0);
		DroneTool tool = o.keyAttack.isDown() ? DroneTool.BREAK : place ? DroneTool.PLACE : DroneTool.NONE;
		return new DroneAction(forward, right, up, yaw, pitch, new ToolRequest(tool, slot, ToolRequest.NO_TRANSFER, 0, -1, 0));
	}
}
