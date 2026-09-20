package com.itsthejimjam.realcamera.client;

/**
 * Long exposure. It is automatic: whenever the shutter is slow enough that the world
 * would visibly move during it, a capture unfreezes the world, fast-forwards it through
 * the shutter's worth of game time while stacking sub-frames, averages them for natural
 * motion blur, then re-freezes wherever it lands (no rollback) and runs the stacked
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

	/** Sub-frames to stack for the given shutter. Kept fairly low so a moving subject
	 *  reads as a solid blur along its path rather than a faint ghost. */
	public static int subFrames(double shutterSeconds) {
		int n = (int) Math.round(shutterSeconds * 5.0) + 4;
		return Math.max(6, Math.min(40, n));
	}

	/**
	 * Server tick rate to run during the exposure so the shutter's worth of game time
	 * elapses over roughly one second of real recording.
	 */
	public static float boostTickRate(double shutterSeconds, int subFrames) {
		double rate = 1200.0 * shutterSeconds / subFrames;
		return (float) Math.max(10.0, Math.min(600.0, rate));
	}
}
