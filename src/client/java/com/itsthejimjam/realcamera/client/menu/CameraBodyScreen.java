package com.itsthejimjam.realcamera.client.menu;

import com.itsthejimjam.realcamera.client.PhotoModeSession;
import com.itsthejimjam.realcamera.menu.CameraBodyMenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The camera-body loadout screen: lens / filter slots over the player inventory,
 * drawn with flat fills (no texture) to match the mod's panel look.
 */
public class CameraBodyScreen extends AbstractContainerScreen<CameraBodyMenu> {

	private static final int BORDER = 0xFF000000;
	private static final int PANEL = 0xF01C1C1C;
	private static final int SLOT_EDGE = 0xFF555555;
	private static final int SLOT_BG = 0xFF2A2A2A;
	private static final int LABEL = 0xFF9A9A9A;

	public CameraBodyScreen(CameraBodyMenu menu, Inventory inv, Component title) {
		super(menu, inv, title, 176, 166);
		this.inventoryLabelY = this.imageHeight - 94;
	}

	@Override
	public void removed() {
		super.removed();
		// If this was opened from the in-finder "ATTACH A LENS" prompt, re-read the
		// loadout so a lens slotted just now takes effect without leaving photo mode.
		PhotoModeSession.queueReloadLoadout();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		int x = this.leftPos;
		int y = this.topPos;
		g.fill(x - 1, y - 1, x + this.imageWidth + 1, y + this.imageHeight + 1, BORDER);
		g.fill(x, y, x + this.imageWidth, y + this.imageHeight, PANEL);

		int[] sx = CameraBodyMenu.SLOT_X;
		int sy = CameraBodyMenu.SLOT_Y;
		String[] labels = {"Lens", "Filter"};
		for (int i = 0; i < labels.length; i++) {
			slotBox(g, x + sx[i], y + sy);
			String l = labels[i];
			g.text(this.font, Component.literal(l),
					x + sx[i] + 8 - this.font.width(l) / 2, y + sy - 11, LABEL, false);
		}

		for (int r = 0; r < 3; r++) {
			for (int c = 0; c < 9; c++) {
				slotBox(g, x + 8 + c * 18, y + 84 + r * 18);
			}
		}
		for (int c = 0; c < 9; c++) {
			slotBox(g, x + 8 + c * 18, y + 142);
		}
	}

	private static void slotBox(GuiGraphicsExtractor g, int sx, int sy) {
		g.fill(sx - 1, sy - 1, sx + 17, sy + 17, SLOT_EDGE);
		g.fill(sx, sy, sx + 16, sy + 16, SLOT_BG);
	}
}
