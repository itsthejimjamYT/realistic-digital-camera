package com.itsthejimjam.realcamera.client;

import net.minecraft.util.Mth;

/**
 * Preview-only viewfinder aids: a composition grid, a live histogram, a blown-highlight
 * (zebra) warning, and focus peaking. All are forced off while a photo is being
 * captured, so they never end up in the saved file.
 */
public final class DisplayAids {

	public static final String[] HISTOGRAM_MODES = {"Off", "RGB", "Luma"};
	public static final String[] TOGGLE = {"Off", "On"};
	/** Composition overlays. Index 0 = none. */
	public static final String[] GRID_TYPES = {"Off", "Thirds", "Phi", "Center", "Diagonal", "Grid 4x4"};

	/** Which corner of the frame the (compact) histogram is pinned to. */
	public static final String[] HUD_ANCHORS = {"Top Right", "Top Left", "Btm Right", "Btm Left"};
	public static final String[] HUD_OPACITY = {"100%", "75%", "50%", "25%"};
	private static final float[] HUD_ALPHA = {1.0f, 0.75f, 0.5f, 0.25f};

	private static int hudAnchor = 0;
	private static int hudOpacity = 0;

	private static final int GRID_BASE = 1; // Thirds

	/** 0 = off, 1 = RGB, 2 = luma. */
	private static int histogram = 0;
	private static boolean zebras = false;
	private static boolean peaking = false;
	/** The light meter is on by default — it is a core exposure aid. */
	private static boolean meter = true;
	/** Index into {@link #GRID_TYPES}. */
	private static int grid = GRID_BASE;

	private DisplayAids() {
	}

	// --- composition grid ---

	public static int gridType() {
		return grid;
	}

	public static int gridIndex() {
		return grid;
	}

	public static void setGridIndex(int i) {
		grid = Mth.clamp(i, 0, GRID_TYPES.length - 1);
	}

	public static void stepGrid(int dir) {
		grid = Math.floorMod(grid + dir, GRID_TYPES.length);
	}

	public static void resetGrid() {
		grid = GRID_BASE;
	}

	// --- histogram ---

	public static int histogramMode() {
		return histogram;
	}

	public static boolean histogramOn() {
		return histogram != 0;
	}

	public static int histogramIndex() {
		return histogram;
	}

	public static void setHistogramIndex(int i) {
		histogram = Mth.clamp(i, 0, HISTOGRAM_MODES.length - 1);
	}

	public static void stepHistogram(int dir) {
		histogram = Math.floorMod(histogram + dir, HISTOGRAM_MODES.length);
	}

	public static void resetHistogram() {
		histogram = 0;
	}

	// --- clip warning (zebras) ---

	public static boolean zebrasOn() {
		return zebras;
	}

	public static int zebrasIndex() {
		return zebras ? 1 : 0;
	}

	public static void setZebrasIndex(int i) {
		zebras = i == 1;
	}

	public static void stepZebras(int dir) {
		zebras = !zebras;
	}

	public static void resetZebras() {
		zebras = false;
	}

	// --- focus peaking ---

	public static boolean peakingOn() {
		return peaking;
	}

	public static int peakingIndex() {
		return peaking ? 1 : 0;
	}

	public static void setPeakingIndex(int i) {
		peaking = i == 1;
	}

	public static void stepPeaking(int dir) {
		peaking = !peaking;
	}

	public static void resetPeaking() {
		peaking = false;
	}

	// --- exposure light meter ---

	public static boolean meterOn() {
		return meter;
	}

	public static int meterIndex() {
		return meter ? 1 : 0;
	}

	public static void setMeterIndex(int i) {
		meter = i == 1;
	}

	public static void stepMeter(int dir) {
		meter = !meter;
	}

	public static void resetMeter() {
		meter = true;
	}

	// --- overlay placement + opacity ---

	/** 0 Auto, 1 bottom-right, 2 bottom-left, 3 top-right, 4 top-left. */
	public static int hudAnchor() {
		return hudAnchor;
	}

	public static int hudAnchorIndex() {
		return hudAnchor;
	}

	public static void setHudAnchorIndex(int i) {
		hudAnchor = Mth.clamp(i, 0, HUD_ANCHORS.length - 1);
	}

	public static void stepHudAnchor(int dir) {
		hudAnchor = Math.floorMod(hudAnchor + dir, HUD_ANCHORS.length);
	}

	public static void resetHudAnchor() {
		hudAnchor = 0;
	}

	public static int hudOpacityIndex() {
		return hudOpacity;
	}

	public static void setHudOpacityIndex(int i) {
		hudOpacity = Mth.clamp(i, 0, HUD_OPACITY.length - 1);
	}

	public static void stepHudOpacity(int dir) {
		hudOpacity = Math.floorMod(hudOpacity + dir, HUD_OPACITY.length);
	}

	public static void resetHudOpacity() {
		hudOpacity = 0;
	}

	public static float hudAlpha() {
		return HUD_ALPHA[hudOpacity];
	}

	/** Scale an ARGB colour's alpha by the current overlay opacity. */
	public static int fade(int argb) {
		float a = hudAlpha();
		if (a >= 0.999f) {
			return argb;
		}
		int alpha = Math.round(((argb >>> 24) & 0xFF) * a);
		return (alpha << 24) | (argb & 0x00FFFFFF);
	}

	public static void resetAll() {
		histogram = 0;
		zebras = false;
		peaking = false;
		meter = true;
		grid = GRID_BASE;
		hudAnchor = 0;
		hudOpacity = 0;
	}
}
