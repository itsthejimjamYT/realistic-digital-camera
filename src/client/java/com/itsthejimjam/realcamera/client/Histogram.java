package com.itsthejimjam.realcamera.client;

import com.itsthejimjam.realcamera.PhotoMode;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Live histogram for the viewfinder. Every so often (the scene is frozen, so a few
 * updates a second is plenty) it reads the finished frame back from the GPU, bins it
 * on the CPU, and caches the result for {@link PhotoOverlay} to draw.
 */
public final class Histogram {
	private static final int BINS = 128;
	/** Readback cadence. Low enough that the light meter tracks a pan without feeling
	 *  laggy; the scene's game ticks are frozen so this is cheap. */
	private static final int SAMPLE_EVERY_FRAMES = 10;
	private static final int PIXEL_STRIDE = 4;

	private static final int[] R = new int[BINS];
	private static final int[] G = new int[BINS];
	private static final int[] B = new int[BINS];
	private static final int[] L = new int[BINS];
	private static int maxRgb = 1;
	private static int maxLum = 1;
	private static boolean hasData = false;

	/** Center-weighted mean display luma (0..1) of the last sampled frame, for the light
	 *  meter. Reflected-light metering: it swings with whatever the lens is pointed at. */
	private static volatile float meanLuma = 0.45f;
	private static volatile boolean meterHasData = false;
	/** Display luma we call a "correct" exposure — the meter reads 0 here. */
	private static final float METER_TARGET = 0.45f;

	private static int frame = 0;
	private static volatile boolean busy = false;

	private Histogram() {
	}

	private static int clamp(int v, int lo, int hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	public static void clear() {
		hasData = false;
		meterHasData = false;
	}

	/** Exposure error in stops from the active metering pattern: negative = the framed
	 *  scene will render dark with the current settings, positive = bright. 0 until the
	 *  first sample lands. */
	public static float meterStops() {
		return meterHasData
				? (float) (Math.log(Math.max(meanLuma, 1.0e-3f) / METER_TARGET) / Math.log(2.0))
				: 0.0f;
	}

	/** True once a frame has been metered — the P/A/S auto-exposure driver waits for this. */
	public static boolean meterReady() {
		return meterHasData;
	}

	/** Called at the end of the frame while photo mode is active and not capturing. */
	public static void maybeSample(RenderTarget target) {
		// Sample when the histogram or light meter is shown, and always when the
		// exposure driver is live (an auto shooting mode, or Auto ISO).
		boolean needed = DisplayAids.histogramOn() || DisplayAids.meterOn()
				|| PhotoModeSession.shootModeIndex() != 3 || PhotoModeSession.isoAuto();
		if (target == null || busy || PhotoCapture.wantsBigFrame() || !needed) {
			return;
		}
		if (frame++ % SAMPLE_EVERY_FRAMES != 0) {
			return;
		}
		busy = true;
		try {
			Screenshot.takeScreenshot(target, 1, image -> {
				try {
					compute(image);
				} catch (Throwable t) {
					PhotoMode.LOGGER.warn("[Photo Mode] histogram compute failed: {}", t.toString());
				} finally {
					image.close();
					busy = false;
				}
			});
		} catch (Throwable t) {
			busy = false;
		}
	}

	private static void compute(NativeImage image) {
		int[] nr = new int[BINS];
		int[] ng = new int[BINS];
		int[] nb = new int[BINS];
		int[] nl = new int[BINS];
		int w = image.getWidth();
		int h = image.getHeight();
		// Meter only inside the framed crop — the letterbox bars aren't part of the shot.
		int[] crop = Framing.cropRect(w, h);
		int mx0 = crop[0];
		int my0 = crop[1];
		int mx1 = crop[0] + crop[2];
		int my1 = crop[1] + crop[3];
		int mcx = (mx0 + mx1) / 2;
		int mcy = (my0 + my1) / 2;
		// Metering pattern: 0 matrix (flat), 1 centre-weighted, 2 spot at the focus point.
		int meter = PhotoModeSession.meteringMode();
		int spotCx = clamp(Math.round(PhotoModeSession.getFocusU() * w), mx0, mx1 - 1);
		int spotCy = clamp(Math.round((1.0f - PhotoModeSession.getFocusV()) * h), my0, my1 - 1);
		double spotR2 = Math.pow(Math.max(crop[2], crop[3]) * 0.06, 2.0);
		double lumaWeighted = 0.0;
		double weightTotal = 0.0;
		for (int y = 0; y < h; y += PIXEL_STRIDE) {
			for (int x = 0; x < w; x += PIXEL_STRIDE) {
				int p = image.getPixel(x, y);
				int r = (p >> 16) & 0xFF;
				int g = (p >> 8) & 0xFF;
				int b = p & 0xFF;
				nr[r * BINS / 256]++;
				ng[g * BINS / 256]++;
				nb[b * BINS / 256]++;
				int lum = Math.min((r * 77 + g * 150 + b * 29) >> 8, 255);
				nl[lum * BINS / 256]++;
				if (x < mx0 || x >= mx1 || y < my0 || y >= my1) {
					continue;
				}
				double wgt;
				if (meter == 2) {
					double dx = x - spotCx;
					double dy = y - spotCy;
					if (dx * dx + dy * dy > spotR2) {
						continue;
					}
					wgt = 1.0;
				} else if (meter == 0) {
					wgt = 1.0;
				} else {
					// Centre-weighted: the middle ~40% of the frame counts ~4x the edges.
					boolean inner = Math.abs(x - mcx) * 5 < crop[2] && Math.abs(y - mcy) * 5 < crop[3];
					wgt = inner ? 4.0 : 1.0;
				}
				lumaWeighted += (lum / 255.0) * wgt;
				weightTotal += wgt;
			}
		}
		meanLuma = weightTotal > 0.0 ? (float) (lumaWeighted / weightTotal) : 0.45f;
		meterHasData = true;
		int mRgb = 1;
		int mLum = 1;
		for (int i = 0; i < BINS; i++) {
			mRgb = Math.max(mRgb, Math.max(nr[i], Math.max(ng[i], nb[i])));
			mLum = Math.max(mLum, nl[i]);
		}
		synchronized (Histogram.class) {
			System.arraycopy(nr, 0, R, 0, BINS);
			System.arraycopy(ng, 0, G, 0, BINS);
			System.arraycopy(nb, 0, B, 0, BINS);
			System.arraycopy(nl, 0, L, 0, BINS);
			maxRgb = mRgb;
			maxLum = mLum;
			hasData = true;
		}
	}

	/** width of the graph = one pixel per bin, plus a small border. */
	public static int width() {
		return BINS + 10;
	}

	public static int height() {
		return 100;
	}

	private static final int COMPACT_COLS = 64;

	public static int compactWidth() {
		return COMPACT_COLS + 10;
	}

	public static int compactHeight() {
		return 52;
	}

	/** Bin height as a fraction of the plot span. Square-rooted so small populations
	 *  (mid-tones) stay visible instead of being crushed by one tall spike. */
	private static int barHeight(int count, int max, int span) {
		if (count <= 0) {
			return 0;
		}
		return (int) Math.round(Math.sqrt((double) count / max) * span);
	}

	public static void draw(GuiGraphicsExtractor graphics, int x, int y) {
		drawScaled(graphics, x, y, BINS, height());
	}

	/** Smaller build for a pinned overlay corner (tight aspect ratios). */
	public static void drawCompact(GuiGraphicsExtractor graphics, int x, int y) {
		drawScaled(graphics, x, y, COMPACT_COLS, compactHeight());
	}

	private static synchronized void drawScaled(GuiGraphicsExtractor graphics, int x, int y, int cols, int h) {
		int w = cols + 10;
		graphics.fill(x, y, x + w, y + h, DisplayAids.fade(0xC8000000));
		graphics.fill(x, y, x + w, y + 1, DisplayAids.fade(0x40FFFFFF));
		graphics.fill(x, y + h - 1, x + w, y + h, DisplayAids.fade(0x40FFFFFF));

		int plotTop = y + 13;
		int bottom = y + h - 4;
		int span = bottom - plotTop;

		graphics.text(Minecraft.getInstance().font,
				DisplayAids.histogramMode() == 2 ? "LUMA" : "RGB", x + 5, y + 3, DisplayAids.fade(0xFF909090), false);

		graphics.fill(x + 5, bottom, x + 5 + cols, bottom + 1, DisplayAids.fade(0x33FFFFFF));
		for (int q = 1; q < 4; q++) {
			graphics.verticalLine(x + 5 + q * cols / 4, plotTop, bottom, DisplayAids.fade(0x18FFFFFF));
		}
		if (!hasData) {
			return;
		}

		boolean luma = DisplayAids.histogramMode() == 2;
		for (int c = 0; c < cols; c++) {
			int b0 = c * BINS / cols;
			int b1 = Math.max(b0 + 1, (c + 1) * BINS / cols);
			int r = 0;
			int g = 0;
			int b = 0;
			int l = 0;
			for (int i = b0; i < b1; i++) {
				r = Math.max(r, R[i]);
				g = Math.max(g, G[i]);
				b = Math.max(b, B[i]);
				l = Math.max(l, L[i]);
			}
			int bx = x + 5 + c;
			if (luma) {
				graphics.verticalLine(bx, bottom, bottom - barHeight(l, maxLum, span), DisplayAids.fade(0xE0FFFFFF));
			} else {
				graphics.verticalLine(bx, bottom, bottom - barHeight(b, maxRgb, span), DisplayAids.fade(0x907090FF));
				graphics.verticalLine(bx, bottom, bottom - barHeight(g, maxRgb, span), DisplayAids.fade(0x9070FF70));
				graphics.verticalLine(bx, bottom, bottom - barHeight(r, maxRgb, span), DisplayAids.fade(0x90FF7070));
			}
		}
	}

	/** Reset the sample cadence when re-entering. */
	public static void resetCadence() {
		frame = 0;
	}
}
