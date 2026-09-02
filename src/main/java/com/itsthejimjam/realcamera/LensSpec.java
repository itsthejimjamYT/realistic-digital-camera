package com.itsthejimjam.realcamera;

/**
 * Focal-length range of a physical lens. A prime has {@code focalMin == focalMax}; a zoom
 * spans the two. The photo-mode zoom is clamped to whatever the equipped lens allows.
 */
public record LensSpec(int focalMin, int focalMax, String apertureLabel) {

	public boolean isPrime() {
		return focalMin == focalMax;
	}

	public String rangeLabel() {
		return isPrime() ? focalMin + "mm" : focalMin + "-" + focalMax + "mm";
	}

	/** Widest aperture the lens allows at its SHORT end (smallest f-number), parsed from
	 *  {@link #apertureLabel} e.g. {@code "f/2.8"} or {@code "f/4.5-5.6"}. f/1.4 fallback. */
	public float widestAperture() {
		float lo = Float.MAX_VALUE;
		for (String tok : apertureLabel.replace("f/", "").split("[-/ ]")) {
			try {
				lo = Math.min(lo, Float.parseFloat(tok.trim()));
			} catch (NumberFormatException ignored) {
				// skip
			}
		}
		return lo == Float.MAX_VALUE ? 1.4f : lo;
	}

	/** Widest aperture at the LONG end. Equals {@link #widestAperture()} for a constant-
	 *  aperture lens; the larger number for a variable-aperture zoom ("f/5.6-6.3"). */
	public float widestApertureLong() {
		float hi = -1.0f;
		for (String tok : apertureLabel.replace("f/", "").split("[-/ ]")) {
			try {
				hi = Math.max(hi, Float.parseFloat(tok.trim()));
			} catch (NumberFormatException ignored) {
				// skip
			}
		}
		return hi < 0.0f ? widestAperture() : hi;
	}

	/** The widest aperture actually available at {@code focalMm} — interpolated (in stops)
	 *  between the short- and long-end values for a variable-aperture zoom, so e.g. a
	 *  200-600 f/5.6-6.3 opens to f/5.6 at 200mm and only f/6.3 by 600mm. */
	public float widestApertureAt(int focalMm) {
		float lo = widestAperture();
		float hi = widestApertureLong();
		if (isPrime() || hi <= lo + 0.01f || focalMax == focalMin) {
			return lo;
		}
		float t = (focalMm - focalMin) / (float) (focalMax - focalMin);
		t = t < 0.0f ? 0.0f : t > 1.0f ? 1.0f : t;
		double stops = Math.log(lo) / Math.log(2.0);
		stops += (Math.log(hi) / Math.log(2.0) - stops) * t;
		return (float) Math.pow(2.0, stops);
	}

	/**
	 * The photo-mode zoom factor that produces the given full-frame focal length, given
	 * the base ("normal lens") vertical FOV. Mirrors {@code PhotoModeSession.focalLengthMm}:
	 * {@code focal = 12 / tan(vfov/2)}, and {@code vfov = baseFov / zoom}.
	 */
	public static float zoomForFocal(float focalMm, float baseFovDeg) {
		double vfovDeg = Math.toDegrees(2.0 * Math.atan(12.0 / Math.max(focalMm, 1.0)));
		return (float) (baseFovDeg / vfovDeg);
	}
}
