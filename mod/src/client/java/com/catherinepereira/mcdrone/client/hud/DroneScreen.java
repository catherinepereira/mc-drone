package com.catherinepereira.mcdrone.client.hud;

import com.catherinepereira.mcdrone.client.ClientRuntime;
import com.catherinepereira.mcdrone.client.ToolState;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.tool.DroneTool;
import com.catherinepereira.mcdrone.tool.ToolRequest;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Container GUI for human pilots. Left click moves a stack between the drone and the open container,
 * right click on a drone slot selects it for placing. Every click goes out as the same transfer action a policy sends
 */
public final class DroneScreen extends Screen {
	private static final int SLOT = 18;
	private static final int COLS = 9;
	private static final int PAD = 8;
	private static final int BG = 0xF2F6F8FC;
	private static final int BORDER = 0xFFDDE3EC;
	private static final int SLOT_BG = 0xFFE6EBF2;
	private static final int TEXT = 0xFF1F2933;
	private static final int MUTED = 0xFF6B7785;
	private static final int ACCENT = 0xFF3D7BD9;

	private final ClientRuntime runtime;
	private int left;
	private int top;

	public DroneScreen(ClientRuntime runtime) {
		super(Component.literal("Drone"));
		this.runtime = runtime;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private int containerRows() {
		return (this.runtime.tools().containerSize() + COLS - 1) / COLS;
	}

	private int panelHeight() {
		int rows = DroneEntity.INVENTORY_SIZE / COLS;
		int containerRows = this.runtime.tools().containerOpen() ? this.containerRows() : 0;
		return PAD * 2 + 12 + rows * SLOT + (containerRows > 0 ? 12 + containerRows * SLOT + 6 : 0) + 14;
	}

	private void layout() {
		int w = PAD * 2 + COLS * SLOT;
		this.left = (this.width - w) / 2;
		this.top = (this.height - this.panelHeight()) / 2;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		this.layout();
		ToolState tools = this.runtime.tools();
		int w = PAD * 2 + COLS * SLOT;
		int h = this.panelHeight();
		g.fill(this.left, this.top, this.left + w, this.top + h, BG);
		g.outline(this.left, this.top, w, h, BORDER);
		int y = this.top + PAD;
		String hovered = null;
		if (tools.containerOpen()) {
			g.text(this.font, "Container", this.left + PAD, y, MUTED, false);
			y += 12;
			hovered = this.grid(g, y, tools.containerSize(), tools::containerStack, -1, mouseX, mouseY, hovered);
			y += this.containerRows() * SLOT + 6;
		}
		g.text(this.font, "Drone", this.left + PAD, y, MUTED, false);
		y += 12;
		hovered = this.grid(g, y, DroneEntity.INVENTORY_SIZE, tools::inventoryStack, this.runtime.selectedSlot(), mouseX, mouseY, hovered);
		y += (DroneEntity.INVENTORY_SIZE / COLS) * SLOT + 4;
		String hint = tools.containerOpen() ? "click moves a stack, right click selects" : "right click selects the slot to place from";
		g.text(this.font, hovered != null ? hovered : hint, this.left + PAD, y, hovered != null ? TEXT : MUTED, false);
	}

	private String grid(GuiGraphicsExtractor g, int y, int size, java.util.function.IntFunction<ItemStack> stacks, int selected, int mx, int my, String hovered) {
		for (int i = 0; i < size; i++) {
			int x = this.left + PAD + (i % COLS) * SLOT;
			int sy = y + (i / COLS) * SLOT;
			g.fill(x + 1, sy + 1, x + SLOT - 1, sy + SLOT - 1, SLOT_BG);
			if (i == selected) {
				g.outline(x, sy, SLOT, SLOT, ACCENT);
			}
			ItemStack stack = stacks.apply(i);
			if (!stack.isEmpty()) {
				g.item(stack, x + 1, sy + 1);
				g.itemDecorations(this.font, stack, x + 1, sy + 1);
			}
			if (mx >= x && mx < x + SLOT && my >= sy && my < sy + SLOT && !stack.isEmpty()) {
				hovered = stack.getCount() + " x " + stack.getHoverName().getString();
			}
		}
		return hovered;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		this.layout();
		ToolState tools = this.runtime.tools();
		int y = this.top + PAD;
		if (tools.containerOpen()) {
			y += 12;
			int slot = this.slotAt(event.x(), event.y(), y, tools.containerSize());
			if (slot >= 0) {
				this.runtime.queueTool(new ToolRequest(DroneTool.NONE, this.runtime.selectedSlot(), ToolRequest.CONTAINER_TO_DRONE, slot, -1, 0));
				return true;
			}
			y += this.containerRows() * SLOT + 6;
		}
		y += 12;
		int slot = this.slotAt(event.x(), event.y(), y, DroneEntity.INVENTORY_SIZE);
		if (slot >= 0) {
			if (event.button() == 1 || !tools.containerOpen()) {
				this.runtime.selectSlot(slot);
			} else {
				this.runtime.queueTool(new ToolRequest(DroneTool.NONE, this.runtime.selectedSlot(), ToolRequest.DRONE_TO_CONTAINER, slot, -1, 0));
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private int slotAt(double mx, double my, int y, int size) {
		int col = (int) Math.floor((mx - this.left - PAD) / SLOT);
		int row = (int) Math.floor((my - y) / SLOT);
		if (mx < this.left + PAD || my < y || col >= COLS || row < 0) {
			return -1;
		}
		int i = row * COLS + col;
		return i < size ? i : -1;
	}

	@Override
	public void removed() {
		if (this.runtime.tools().containerOpen()) {
			this.runtime.queueTool(new ToolRequest(DroneTool.CLOSE, this.runtime.selectedSlot(), ToolRequest.NO_TRANSFER, 0, -1, 0));
		}
		super.removed();
	}
}
