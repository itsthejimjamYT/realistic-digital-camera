package com.itsthejimjam.realcamera.client;

import net.minecraft.util.Mth;

/**
 * Exposure bracketing for blend / HDR workflows. Pick a frame count and an EV step;
 * one shutter press then fires that many frames, spread symmetrically around the metered
 * exposure, and saves each as a separate PNG tagged {@code BRACKET} so they can be merged
 * in an editor afterwards. The spread is drawn as tick marks on the exposure meter.
 */
public final class Bracket {

	public static final String[] FRAMES = {"Off", "2", "3", "5", "7", "9"};
	private static final int[] FRAME_COUNTS = {0, 2, 3, 5, 7, 9};

	public static final String[] EV_STEPS = {"0.3 EV", "0.5 EV", "0.7 EV", "1.0 EV", "2.0 EV", "3.0 EV"};
	private static final float[] EV_STEP_VALUES = {0.3f, 0.5f, 0.7f, 1.0f, 2.0f, 3.0f};

	private static final int STEP_BASE = 3; // 1.0 EV

	private static int framesIdx = 0;
	private static int stepIdx = STEP_BASE;

	private Bracket() {
	}

	public static boolean on() {
		return framesIdx != 0;
	}

	public static int frames() {
		return FRAME_COUNTS[framesIdx];
	}

	public static float evStep() {
		return EV_STEP_VALUES[stepIdx];
	}

	/**
	 * Bracket EV offsets from the metered exposure, ascending (most negative first).
	 * Odd counts are symmetric around 0; an even count (2) is {@code 0} and one step under.
	 */
	public static float[] offsets() {
		int n = frames();
		if (n == 0) {
			return new float[] {0.0f};
		}
		float step = evStep();
		float[] out = new float[n];
		if (n % 2 == 1) {
			int half = n / 2;
			for (int i = 0; i < n; i++) {
				out[i] = (i - half) * step;
			}
		} else {
			for (int i = 0; i < n; i++) {
				out[i] = (i - (n - 1)) * step;
			}
		}
		return out;
	}

	// --- settings-panel accessors ---

	public static int framesIndex() {
		return framesIdx;
	}

	public static void setFramesIndex(int i) {
		framesIdx = Mth.clamp(i, 0, FRAMES.length - 1);
	}

	public static void stepFrames(int dir) {
		framesIdx = Math.floorMod(framesIdx + dir, FRAMES.length);
	}

	public static void resetFrames() {
		framesIdx = 0;
	}

	public static int stepIndex() {
		return stepIdx;
	}

	public static void setStepIndex(int i) {
		stepIdx = Mth.clamp(i, 0, EV_STEPS.length - 1);
	}

	public static void stepStep(int dir) {
		stepIdx = Math.floorMod(stepIdx + dir, EV_STEPS.length);
	}

	public static void resetStep() {
		stepIdx = STEP_BASE;
	}
}
