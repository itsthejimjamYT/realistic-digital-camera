package com.itsthejimjam.realcamera.client;

import com.itsthejimjam.realcamera.PhotoMode;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * The photo-mode on-screen overlay: aspect-ratio framing bars, a rule-of-thirds grid,
 * and a small settings readout. Registered as a Fabric HUD element so it draws on top
 * of the (suppressed) vanilla HUD while a session is active.
 */
public final class PhotoOverlay implements HudElement {
	public static final Identifier ID = PhotoMode.id("overlay");

	private static final int NOLENS_BG = 0xFFF3F3F3;
	private static final int NOLENS_FG = 0xFF1A1A1A;
	private static final int NOLENS_SUB = 0xFF808080;

	private static final int BAR_COLOR = 0xFF000000;
	private static final int GRID_COLOR = 0x40FFFFFF;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int HINT_COLOR = 0xFFB0B0B0;
	private static final int FOCUS_COLOR = 0x99FFFFFF;
	private static final int PICK_COLOR = 0xFFFFE24D;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (!PhotoModeSession.isActive() || PhotoCapture.wantsBigFrame()) {
			return;
		}

		Minecraft mc = Minecraft.getInstance();
		int w = graphics.guiWidth();
		int h = graphics.guiHeight();
		graphics.nextStratum();

		// --- no lens on the mount: a plain prompt, no blackout / whiteout ---
		if (PhotoModeSession.noLensAttached()) {
			String key = mc.options.keyInventory.getTranslatedKeyMessage().getString();
			String msg = "ATTACH A LENS";
			String sub = "press " + key + " to open the camera menu";
			int tw = Math.max(mc.font.width(msg), mc.font.width(sub));
			int cx = w / 2;
			int cy = h / 2 - 6;
			graphics.fill(cx - tw / 2 - 8, cy - 6, cx + tw / 2 + 8, cy + 24, 0xB0000000);
			graphics.centeredText(mc.font, msg, cx, cy, NOLENS_BG);
			graphics.centeredText(mc.font, sub, cx, cy + 12, NOLENS_SUB);
			return;
		}

		// --- letterbox / pillarbox to the framing aspect ---
		int[] box = Framing.cropRect(w, h);
		int fx0 = box[0];
		int fy0 = box[1];
		int fx1 = box[0] + box[2];
		int fy1 = box[1] + box[3];

		if (fx0 > 0) {
			graphics.fill(0, 0, fx0, h, BAR_COLOR);
			graphics.fill(fx1, 0, w, h, BAR_COLOR);
		}
		if (fy0 > 0) {
			graphics.fill(0, 0, w, fy0, BAR_COLOR);
			graphics.fill(0, fy1, w, h, BAR_COLOR);
		}

		// --- composition grid inside the frame ---
		drawGrid(graphics, DisplayAids.gridType(), fx0, fy0, fx1, fy1);

		// --- focus point marker. The UV indexes the whole framebuffer (that's what the
		// DoF shader samples), so map it across the full screen, not the framed crop. ---
		int focusX = Math.round(PhotoModeSession.getFocusU() * w);
		int focusY = Math.round((1.0f - PhotoModeSession.getFocusV()) * h);
		marker(graphics, focusX, focusY, 4, FOCUS_COLOR);

		if (PhotoModeSession.isFocusPicking()) {
			int cx = Math.round(PhotoModeSession.getCursorU() * w);
			int cy = Math.round((1.0f - PhotoModeSession.getCursorV()) * h);
			marker(graphics, cx, cy, 8, PICK_COLOR);
			graphics.fill(cx - 9, cy - 9, cx + 9, cy - 8, PICK_COLOR);
			graphics.fill(cx - 9, cy + 8, cx + 9, cy + 9, PICK_COLOR);
			graphics.fill(cx - 9, cy - 9, cx - 8, cy + 9, PICK_COLOR);
			graphics.fill(cx + 8, cy - 9, cx + 9, cy + 9, PICK_COLOR);
		}

		// --- live histogram: a compact build pinned to the chosen frame corner, with the
		// chosen opacity. Only the histogram honours these two settings. ---
		if (DisplayAids.histogramOn()) {
			int hw = Histogram.compactWidth();
			int hh = Histogram.compactHeight();
			int pad = 8;
			int anchor = DisplayAids.hudAnchor();   // 0 TR, 1 TL, 2 BR, 3 BL
			boolean right = anchor == 0 || anchor == 2;
			boolean bottom = anchor == 2 || anchor == 3;
			int hx = right ? fx1 - pad - hw : fx0 + pad;
			int hy = bottom ? fy1 - pad - hh : fy0 + pad;
			Histogram.drawCompact(graphics, hx, hy);
		}

		// --- exposure light meter, top-centre of the frame (unaffected by the overlay setting) ---
		if (DisplayAids.meterOn()) {
			int mcx = (fx0 + fx1) / 2;
			drawMeter(graphics, mcx, fy0 + 16, Histogram.meterStops());
			String modeTag = PhotoModeSession.shootModeIndex() == 3 ? "" : PhotoModeSession.shootModeLabel() + " · ";
			String ml = modeTag + PhotoModeSession.METERING_OPTIONS[PhotoModeSession.meteringIndex()]
					.toUpperCase(java.util.Locale.ROOT);
			graphics.text(mc.font, ml, mcx - mc.font.width(ml) / 2, fy0 + 24, HINT_COLOR, true);
		}

		// --- settings readout + controls: always centred on the screen bottom, independent
		// of the framing crop. The panel owns that strip while it's open. Scaled down to
		// fit the window on tight aspect ratios / large GUI scales so it never runs off
		// either edge. ---
		if (PhotoPanelScreen.isOpen()) {
			return;
		}
		String readout = readoutLine();
		String hint = hintLine();
		int longest = Math.max(mc.font.width(readout), mc.font.width(hint));
		int avail = w - 12;
		float s = longest > avail ? (float) avail / longest : 1.0f;
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(w / 2.0f, h - 4.0f);
		pose.scale(s, s);
		graphics.centeredText(mc.font, readout, 0, -20, TEXT_COLOR);
		graphics.centeredText(mc.font, hint, 0, -9,
				PhotoModeSession.isFocusPicking() ? PICK_COLOR : HINT_COLOR);
		pose.popMatrix();
	}

	private static String hintLine() {
		return PhotoModeSession.isFocusPicking()
				? "move mouse to aim  ·  L-click: set focus  ·  F: cancel"
				: "Tab settings  ·  scroll zoom  ·  F focus  ·  L-click shoot  ·  R-click exit";
	}

	private static String readoutLine() {
		float ev = PhotoModeSession.getExposureComp();
		String evStr = Math.abs(ev) < 0.05f ? "" : String.format(java.util.Locale.ROOT, "   ·   %+.1f EV", ev);
		String recipeStr = PhotoModeSession.getRecipeIndex() == 0 ? "" : "   ·   " + PhotoModeSession.getRecipeName();
		int wbK = PhotoModeSession.getWhiteBalanceKelvin();
		String wbStr = wbK == 5500 ? "" : "   ·   " + wbK + "K";
		String leStr = Bracket.on()
				? "   ·   BRKT " + Bracket.frames() + "x" + Bracket.evStep() + "EV"
				: PhotoModeSession.willLongExpose() ? "   ·   LONG" : "";
		String rigStr = "";
		if (!PhotoModeSession.lensLabel().isEmpty()) {
			rigStr += "   ·   " + PhotoModeSession.lensLabel();
		}
		if (!PhotoModeSession.filterLabel().isEmpty()) {
			rigStr += " + " + PhotoModeSession.filterLabel();
		}
		String shake = PhotoModeSession.shakeState();
		if (!shake.isEmpty()) {
			rigStr += "   ·   " + shake;
		}
		String modeStr = PhotoModeSession.shootModeIndex() == 3 ? "" : PhotoModeSession.shootModeLabel() + "  ·  ";
		return String.format("%s%s   ·   f/%s · %s · ISO %s%s%s%s%s%s   ·   ~%dmm",
				modeStr, Framing.label(), PhotoKeys.fstop(), PhotoModeSession.getShutterLabel(),
				PhotoModeSession.isoDisplay(), evStr, wbStr, leStr, recipeStr, rigStr,
				PhotoModeSession.focalLengthMm());
	}

	private static void marker(GuiGraphicsExtractor g, int x, int y, int r, int col) {
		g.horizontalLine(x - r, x + r, y, col);
		g.verticalLine(x, y - r, y + r, col);
	}

	/** Draws the selected composition overlay inside the framed crop. Index matches
	 *  {@link DisplayAids#GRID_TYPES}: 0 Off, 1 Thirds, 2 Phi, 3 Center, 4 Diagonal, 5 4x4. */
	private static void drawGrid(GuiGraphicsExtractor g, int type, int x0, int y0, int x1, int y1) {
		int w = x1 - x0;
		int h = y1 - y0;
		switch (type) {
			case 1 -> lines(g, x0, y0, x1, y1, w, h, new float[] {1f / 3f, 2f / 3f});
			case 2 -> lines(g, x0, y0, x1, y1, w, h, new float[] {0.382f, 0.618f});
			case 3 -> lines(g, x0, y0, x1, y1, w, h, new float[] {0.5f});
			case 4 -> {
				diagonal(g, x0, y0, x1, y1);
				diagonal(g, x0, y1, x1, y0);
			}
			case 5 -> lines(g, x0, y0, x1, y1, w, h, new float[] {0.25f, 0.5f, 0.75f});
			default -> {
				// Off
			}
		}
	}

	private static void lines(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1,
			int w, int h, float[] fracs) {
		for (float f : fracs) {
			g.verticalLine(x0 + Math.round(w * f), y0, y1, GRID_COLOR);
			g.horizontalLine(x0, x1, y0 + Math.round(h * f), GRID_COLOR);
		}
	}

	private static final int METER_TRACK = 0x88FFFFFF;
	private static final int METER_OK = 0xFF6BE06B;
	private static final int METER_WARN = 0xFFF2C14E;
	private static final int METER_CLIP = 0xFFF25C5C;

	/** A ±3-stop exposure scale centred on {@code cx}, baseline at {@code y}, with a
	 *  pointer at {@code stops} above (+) / below (-) a neutral exposure. */
	private static final int METER_BRACKET = 0xFFFFE24D;

	private static void drawMeter(GuiGraphicsExtractor g, int cx, int y, float stops) {
		final int per = 34;                 // px per stop
		final int half = per * 3;           // ±3 stops
		g.horizontalLine(cx - half, cx + half, y, METER_TRACK);
		for (int s = -3; s <= 3; s++) {
			int tx = cx + s * per;
			int th = s == 0 ? 6 : 3;
			g.verticalLine(tx, y - th, y + th, METER_TRACK);
		}

		float c = Math.max(-3.15f, Math.min(3.15f, stops));
		int px = cx + Math.round(c * per);

		// Bracket spread: a tick under the scale at each frame's exposure.
		if (Bracket.on()) {
			for (float off : Bracket.offsets()) {
				float m = Math.max(-3.15f, Math.min(3.15f, stops + off));
				int bx = cx + Math.round(m * per);
				g.fill(bx - 1, y + 4, bx + 1, y + 10, METER_BRACKET);
			}
		}

		int col = Math.abs(stops) <= 0.33f ? METER_OK
				: Math.abs(stops) <= 1.0f ? METER_WARN : METER_CLIP;
		// filled triangle above the track, pointing down at the pointer position
		for (int i = 0; i <= 4; i++) {
			int wRow = 4 - i;
			g.fill(px - wRow, y - 9 + i, px + wRow + 1, y - 8 + i, col);
		}
	}

	/** No arbitrary-line primitive on the HUD graphics, so step a thin dotted trail. */
	private static void diagonal(GuiGraphicsExtractor g, int ax, int ay, int bx, int by) {
		int steps = Math.max(Math.abs(bx - ax), Math.abs(by - ay)) / 4;
		if (steps <= 0) {
			return;
		}
		for (int i = 0; i <= steps; i++) {
			int x = ax + (bx - ax) * i / steps;
			int y = ay + (by - ay) * i / steps;
			g.fill(x, y, x + 2, y + 2, GRID_COLOR);
		}
	}
}
