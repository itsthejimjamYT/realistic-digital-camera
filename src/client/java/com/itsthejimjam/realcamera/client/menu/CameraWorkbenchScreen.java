package com.itsthejimjam.realcamera.client.menu;

import com.itsthejimjam.realcamera.menu.WorkbenchMenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** The Camera Workbench: a wide 7×5 grid + result over the player inventory, flat-drawn. */
public class CameraWorkbenchScreen extends AbstractContainerScreen<WorkbenchMenu> {

	private static final int BORDER = 0xFF000000;
	private static final int PANEL = 0xF01C1C1C;
	private static final int SLOT_EDGE = 0xFF555555;
	private static final int SLOT_BG = 0xFF2A2A2A;
	private static final int ARROW = 0xFF888888;

	private static final int W = 184;
	private static final int H = WorkbenchMenu.INV_Y + 3 * 18 + 4 + 18 + 8;

	public CameraWorkbenchScreen(WorkbenchMenu menu, Inventory inv, Component title) {
		super(menu, inv, title, W, H);
		this.inventoryLabelY = WorkbenchMenu.INV_Y - 12;
		this.titleLabelX = WorkbenchMenu.GRID_X;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		int x = this.leftPos;
		int y = this.topPos;
		g.fill(x - 1, y - 1, x + this.imageWidth + 1, y + this.imageHeight + 1, BORDER);
		g.fill(x, y, x + this.imageWidth, y + this.imageHeight, PANEL);

		for (int r = 0; r < WorkbenchMenu.GRID_H; r++) {
			for (int c = 0; c < WorkbenchMenu.GRID_W; c++) {
				slotBox(g, x + WorkbenchMenu.GRID_X + c * 18, y + WorkbenchMenu.GRID_Y + r * 18);
			}
		}

		int rx = x + WorkbenchMenu.RESULT_X;
		int ry = y + WorkbenchMenu.RESULT_Y;
		slotBox(g, rx, ry);
		g.fill(rx - 20, ry + 7, rx - 4, ry + 11, ARROW);
		g.fill(rx - 10, ry + 2, rx - 4, ry + 16, ARROW);

		for (int r = 0; r < 3; r++) {
			for (int c = 0; c < 9; c++) {
				slotBox(g, x + WorkbenchMenu.INV_X + c * 18, y + WorkbenchMenu.INV_Y + r * 18);
			}
		}
		for (int c = 0; c < 9; c++) {
			slotBox(g, x + WorkbenchMenu.INV_X + c * 18, y + WorkbenchMenu.INV_Y + 58);
		}
	}

	private static void slotBox(GuiGraphicsExtractor g, int sx, int sy) {
		g.fill(sx - 1, sy - 1, sx + 17, sy + 17, SLOT_EDGE);
		g.fill(sx, sy, sx + 16, sy + 16, SLOT_BG);
	}
}
