package com.itsthejimjam.realcamera.client;

/**
 * Long exposure. It is automatic: whenever the shutter is slow enough that the world
 * would visibly move during it, a capture steps the (still-frozen) world forward by a
 * fixed number of ticks between each of a fixed number of sub-frames — so the total
 * game-time covered is exactly the shutter's worth, regardless of how long each
 * sub-frame's render/readback actually takes in real time — stacks them for natural
 * motion blur, then unfreezes wherever it lands (no rollback) and runs the stacked
 * image through the normal DoF / grade / grain. Fast shutters take a single frame.
 */
public final class LongExposure {

	public static final int OFF = 0;
	public static final int BLUR = 1;

	/** Only referenced for the capture-done note / HUD tag. */
	public static final String[] OPTIONS = {"Off", "Blur"};

	/** Below this shutter time the world barely moves, so skip the multi-frame path. */
	private static final double MIN_SHUTTER_SECONDS = 1.0 / 40.0;
	/** Long edge cap for a long-exposure render (CPU accumulation is memory + readback heavy). */
	public static final int MAX_EDGE = 4096;

	private LongExposure() {
	}

	/** BLUR for a slow enough shutter, OFF otherwise. No manual arming. */
	public static int effectiveMode(double shutterSeconds) {
		return shutterSeconds >= MIN_SHUTTER_SECONDS ? BLUR : OFF;
	}

	/** BLUR once the shutter reaches a caller-supplied threshold (the handheld path
	 *  uses the lens's 1/focal reciprocal rule instead of the fixed world-motion one). */
	public static int modeFor(double shutterSeconds, double thresholdSeconds) {
		return shutterSeconds >= thresholdSeconds ? BLUR : OFF;
	}

	public static boolean armed(double shutterSeconds) {
		return effectiveMode(shutterSeconds) != OFF;
	}

	/** Sub-frames to stack for the given shutter. Low end is kept low so a moving subject
	 *  reads as a solid blur along its path rather than a faint ghost; the cap is set to
	 *  cover the full shutter range (30s) uncapped, since a low cap left star trails badly
	 *  undersampled — the sky rotates the same amount regardless of sample count, so fewer
	 *  samples just means bigger angular (pixel) gaps between them, which the trail-gap
	 *  bridging in ExposureStack can only spatially close up to a point. */
	public static int subFrames(double shutterSeconds) {
		int n = (int) Math.round(shutterSeconds * 5.0) + 4;
		return Math.max(6, Math.min(160, n));
	}

	/** Total game ticks the exposure should cover — exactly the shutter's worth (20
	 *  ticks/sec at normal speed), split evenly across the sub-frames. This is the whole
	 *  point of stepping ticks explicitly instead of running a boosted rate for a wall-
	 *  clock duration: the total is fixed by shutter speed alone, never by how long the
	 *  capture actually takes to render. */
	public static int totalTicks(double shutterSeconds) {
		return Math.max(1, (int) Math.round(shutterSeconds * 20.0));
	}
}
