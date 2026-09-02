package com.itsthejimjam.realcamera.client;

/**
 * Photo output settings shared by the on-screen overlay and the capture: aspect
 * ratio, resolution tier (by long edge), and supersampling. What the letterbox
 * shows is what the saved file contains.
 */
public final class Framing {

	/** name, long-edge pixels. Long edge so it works for any aspect. */
	public record ResTier(String label, int longEdge) {
	}

	/** name, width units, height units. */
	public record Aspect(String label, int w, int h) {
		double ratio() {
			return (double) w / (double) h;
		}
	}

	// Ordered widest → tallest.
	private static final Aspect[] ASPECTS = {
			new Aspect("2.39:1", 239, 100),
			new Aspect("21:9", 21, 9),
			new Aspect("1.85:1", 185, 100),
			new Aspect("16:9", 16, 9),
			new Aspect("3:2", 3, 2),
			new Aspect("4:3", 4, 3),
			new Aspect("5:4", 5, 4),
			new Aspect("1:1", 1, 1),
			new Aspect("4:5", 4, 5),
			new Aspect("3:4", 3, 4),
			new Aspect("2:3", 2, 3),
			new Aspect("9:16", 9, 16),
	};

	private static final ResTier[] TIERS = {
			new ResTier("FHD", 1920),
			new ResTier("2K", 2560),
			new ResTier("4K", 3840),
			new ResTier("6K", 6144),
			new ResTier("8K", 7680),
	};

	private static final int[] SUPERSAMPLE = {1, 2, 4};

	private static final int ASPECT_BASE = 4; // 3:2
	private static final int TIER_BASE = 2;   // 4K
	private static final int SS_BASE = 1;     // x2

	private static int aspectIndex = ASPECT_BASE;
	private static int tierIndex = TIER_BASE;
	private static int ssIndex = SS_BASE;

	private Framing() {
	}

	public static void cycleAspect() {
		stepAspect(1);
	}

	public static void cycleTier() {
		stepTier(1);
	}

	public static void cycleSupersample() {
		stepSupersample(1);
	}

	/** Step through the list; dir may be negative. Wraps at both ends. */
	public static void stepAspect(int dir) {
		aspectIndex = Math.floorMod(aspectIndex + dir, ASPECTS.length);
	}

	public static void stepTier(int dir) {
		tierIndex = Math.floorMod(tierIndex + dir, TIERS.length);
	}

	public static void stepSupersample(int dir) {
		ssIndex = Math.floorMod(ssIndex + dir, SUPERSAMPLE.length);
	}

	// --- option lists + index accessors for the settings panel's pick-a-value menus ---

	public static final String[] ASPECT_OPTIONS = aspectNames();
	public static final String[] TIER_OPTIONS = tierNames();
	public static final String[] SS_OPTIONS = {"×1", "×2", "×4"};

	private static String[] aspectNames() {
		String[] out = new String[ASPECTS.length];
		for (int i = 0; i < ASPECTS.length; i++) {
			out[i] = ASPECTS[i].label();
		}
		return out;
	}

	private static String[] tierNames() {
		String[] out = new String[TIERS.length];
		for (int i = 0; i < TIERS.length; i++) {
			out[i] = TIERS[i].label();
		}
		return out;
	}

	public static int getAspectIndex() {
		return aspectIndex;
	}

	public static void setAspectIndex(int i) {
		aspectIndex = Math.floorMod(i, ASPECTS.length);
	}

	public static int getTierIndex() {
		return tierIndex;
	}

	public static void setTierIndex(int i) {
		tierIndex = Math.floorMod(i, TIERS.length);
	}

	public static int getSsIndex() {
		return ssIndex;
	}

	public static void setSsIndex(int i) {
		ssIndex = Math.floorMod(i, SUPERSAMPLE.length);
	}

	/** Short resolution label for the settings panel, e.g. {@code "4K·3840×2160"}. */
	public static String tierLabel() {
		int[] d = dimensions();
		return String.format("%s·%d×%d", TIERS[tierIndex].label(), d[0], d[1]);
	}

	public static void resetAspect() {
		aspectIndex = ASPECT_BASE;
	}

	public static void resetTier() {
		tierIndex = TIER_BASE;
	}

	public static void resetSupersample() {
		ssIndex = SS_BASE;
	}

	/** Reset the output framing (aspect / resolution / supersample) to defaults. */
	public static void resetOutput() {
		aspectIndex = ASPECT_BASE;
		tierIndex = TIER_BASE;
		ssIndex = SS_BASE;
	}

	public static Aspect aspect() {
		return ASPECTS[aspectIndex];
	}

	public static double ratio() {
		return aspect().ratio();
	}

	public static int supersample() {
		return SUPERSAMPLE[ssIndex];
	}

	/** Long-edge cap for the internal capture render. A heavy shader pack renders its
	 *  whole deferred pipeline at this size, so an unbounded value exhausts VRAM —
	 *  A heavy shader pack at 4K × SS4 (15360 px) hard-crashed a 12 GB card. */
	private static final int MAX_CAPTURE_EDGE = 8192;

	/** Supersample actually used for a capture: the chosen factor, halved as needed so
	 *  the internal render stays within {@link #MAX_CAPTURE_EDGE}. */
	public static int effectiveSupersample() {
		int ss = supersample();
		int[] d = dimensions();
		int edge = Math.max(d[0], d[1]);
		while (ss > 1 && (long) edge * ss > MAX_CAPTURE_EDGE) {
			ss >>= 1;
		}
		return ss;
	}

	/** Final saved-image width in pixels (even). */
	public static int outputWidth() {
		return dimensions()[0];
	}

	/** Final saved-image height in pixels (even). */
	public static int outputHeight() {
		return dimensions()[1];
	}

	private static int[] dimensions() {
		int longEdge = TIERS[tierIndex].longEdge();
		double r = ratio();
		int w;
		int h;
		if (r >= 1.0) {
			w = longEdge;
			h = (int) Math.round(longEdge / r);
		} else {
			h = longEdge;
			w = (int) Math.round(longEdge * r);
		}
		return new int[] {w - (w & 1), h - (h & 1)};
	}

	/** e.g. {@code "16:9  ·  4K 3840×2160  ·  SS×2"} */
	public static String label() {
		int[] d = dimensions();
		return String.format("%s  ·  %s %d×%d  ·  SS×%d",
				aspect().label(), TIERS[tierIndex].label(), d[0], d[1], supersample());
	}

	/**
	 * The largest centred rectangle of the framing ratio that fits inside {@code w x h}.
	 * Returns {@code [x, y, width, height]} in top-left pixel coordinates. Used for the
	 * on-screen letterbox only.
	 */
	public static int[] cropRect(int w, int h) {
		double target = ratio();
		double screen = (double) w / (double) h;
		if (screen > target) {
			int fw = (int) Math.round(h * target);
			return new int[] {(w - fw) / 2, 0, fw, h};
		} else {
			int fh = (int) Math.round(w / target);
			return new int[] {0, (h - fh) / 2, w, fh};
		}
	}
}
