package com.itsthejimjam.realcamera.client;

import com.itsthejimjam.realcamera.client.config.PhotoConfig;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.util.Mth;

/**
 * CPU accumulator for a long exposure. Each sub-frame is read back from the GPU and
 * folded into a running per-channel sum (plus a running per-channel peak, used only to
 * recover star trails — see {@link #finish()}); {@link #finish()} produces the stacked
 * image, which is written back into {@code minecraft:main} for the DoF / grade / grain
 * chain to process.
 */
public final class ExposureStack {

	/** Below this many levels of peak-over-average, treat it as ordinary dither/TAA
	 *  jitter, not a real transient highlight. A genuine bright star sweeping through a
	 *  night-sky pixel spikes to near-max brightness against a near-black background —
	 *  a couple hundred levels of margin — so this can sit well above typical per-pixel
	 *  shader noise while still leaving real stars a comfortable amount of room. This
	 *  cost a first, lower attempt (70) that still wasn't enough on a busy dim foreground
	 *  like grass or foliage, hence the bigger margin here now. 0..255 scale. */
	private static final int STAR_THRESHOLD = 110;
	/** Levels of peak-over-average (above the threshold) over which the recovery blend
	 *  ramps from 0 to fully saturated. */
	private static final float STAR_RAMP = 150.0f;

	private int width;
	private int height;
	private int frames;

	/** Running per-channel sum across the folded sub-frames. */
	private int[] sum;
	/** Running per-channel peak across the folded sub-frames — a star trail (or any
	 *  small bright thing sweeping through a pixel in only a few sub-frames) shows up
	 *  here at close to full brightness even though the plain average dims it by
	 *  roughly the sub-frame count. */
	private int[] peak;

	public void begin(int width, int height) {
		this.width = width;
		this.height = height;
		this.frames = 0;
		this.sum = new int[width * height * 3];
		this.peak = new int[width * height * 3];
	}

	public boolean active() {
		return sum != null;
	}

	public int frames() {
		return frames;
	}

	/** Fold one sub-frame in. The image must match the stack's dimensions. */
	public void add(NativeImage image) {
		if (!active() || image.getWidth() != width || image.getHeight() != height) {
			return;
		}
		int i = 0;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int p = Pixels.get(image, x, y);
				int r = (p >> 16) & 0xFF;
				int g = (p >> 8) & 0xFF;
				int b = p & 0xFF;
				sum[i] += r;
				sum[i + 1] += g;
				sum[i + 2] += b;
				if (r > peak[i]) peak[i] = r;
				if (g > peak[i + 1]) peak[i + 1] = g;
				if (b > peak[i + 2]) peak[i + 2] = b;
				i += 3;
			}
		}
		frames++;
	}

	/**
	 * Produce the stacked image and release the accumulator. Caller owns/closes it.
	 * Rows are written flipped: {@code Screenshot.takeScreenshot} hands us a top-down
	 * image, but this goes back into a bottom-up GPU texture, so it must be re-flipped.
	 *
	 * <p>Each channel blends from the plain average toward its recorded peak once the
	 * peak clearly exceeds the average — the signature of something bright (a star)
	 * sweeping through this exact pixel in only a few of the folded sub-frames, which a
	 * plain average dims by roughly the sub-frame count. Static content — even bright
	 * static content like a torch or lava — has peak ≈ average since it doesn't flicker
	 * through the frame, so it's excluded by that same test and comes out as a plain
	 * average, unchanged.
	 */
	public NativeImage finish() {
		NativeImage out = new NativeImage(width, height, false);
		int denom = Math.max(1, frames);
		float strength = PhotoConfig.get().starTrailIntensity();

		// Sub-frames are now sampled densely enough (LongExposure.subFrames, up to 160
		// across the shutter range) that a star's path is mostly a fine dotted line
		// already — only a small nudge is needed to read as continuous, not a big spread.
		// Over-spreading doesn't just risk smearing onto the ground; it also thickens and
		// softens what should be thin, sharp streaks into a bright hazy band, so the
		// radius below is deliberately modest.
		//
		// That bridging has to work on the TRANSIENT signal only, not raw peak: a static
		// bright ground object (a torch, lava, a sunlit specular glint) has peak ≈
		// average at its own pixel, so it's never "recovered" there — but a naive
		// spatial spread of raw peak values would still smear that object's brightness
		// into its darker neighbours, which never saw anything bright at all. Building a
		// separate "transient-only" buffer — zero everywhere except pixels that actually
		// exceeded their own average, at their actual peak value — and spreading THAT
		// instead means only real streaking highlights can ever propagate; a static
		// highlight contributes nothing to the spread no matter how bright it is, so
		// nothing blooms into the ground.
		int[] starSignal = null;
		if (strength > 0.0f) {
			starSignal = new int[sum.length];
			for (int i = 0; i < sum.length; i++) {
				int avg = sum[i] / denom;
				if (peak[i] - avg > STAR_THRESHOLD) {
					starSignal[i] = peak[i];
				}
			}
			int radius = Mth.clamp(Math.round(strength * 2.0f * Math.max(width, height) / 2048.0f), 0, 4);
			if (radius > 0) {
				starSignal = dilate(starSignal, radius);
			}
		}

		int i = 0;
		for (int y = 0; y < height; y++) {
			int outY = height - 1 - y;
			for (int x = 0; x < width; x++) {
				int r = starRecover(sum[i], starSignal, i, denom, strength);
				int g = starRecover(sum[i + 1], starSignal, i + 1, denom, strength);
				int b = starRecover(sum[i + 2], starSignal, i + 2, denom, strength);
				Pixels.set(out, x, outY, 0xFF000000 | (r << 16) | (g << 8) | b);
				i += 3;
			}
		}
		reset();
		return out;
	}

	/** Separable max-filter (horizontal pass then vertical) over a 3-channel-interleaved
	 *  buffer — spreads each channel's value out to the brightest value within
	 *  {@code radius} pixels, cheaply (two 1D passes instead of one 2D window). */
	private int[] dilate(int[] src, int radius) {
		int[] tmp = new int[src.length];
		int[] dst = new int[src.length];
		for (int y = 0; y < height; y++) {
			int rowBase = y * width * 3;
			for (int x = 0; x < width; x++) {
				int x0 = Math.max(0, x - radius);
				int x1 = Math.min(width - 1, x + radius);
				int mr = 0, mg = 0, mb = 0;
				for (int xx = x0; xx <= x1; xx++) {
					int j = rowBase + xx * 3;
					if (src[j] > mr) mr = src[j];
					if (src[j + 1] > mg) mg = src[j + 1];
					if (src[j + 2] > mb) mb = src[j + 2];
				}
				int o = rowBase + x * 3;
				tmp[o] = mr;
				tmp[o + 1] = mg;
				tmp[o + 2] = mb;
			}
		}
		for (int x = 0; x < width; x++) {
			int y0col = x * 3;
			for (int y = 0; y < height; y++) {
				int yy0 = Math.max(0, y - radius);
				int yy1 = Math.min(height - 1, y + radius);
				int mr = 0, mg = 0, mb = 0;
				for (int yy = yy0; yy <= yy1; yy++) {
					int j = yy * width * 3 + y0col;
					if (tmp[j] > mr) mr = tmp[j];
					if (tmp[j + 1] > mg) mg = tmp[j + 1];
					if (tmp[j + 2] > mb) mb = tmp[j + 2];
				}
				int o = y * width * 3 + y0col;
				dst[o] = mr;
				dst[o + 1] = mg;
				dst[o + 2] = mb;
			}
		}
		return dst;
	}

	/** {@code starSignal} is null (feature off) or a buffer that's zero everywhere
	 *  except near a genuine transient highlight, where it holds the (possibly
	 *  neighbour-sourced, post-dilate) peak brightness to recover toward. */
	private static int starRecover(int channelSum, int[] starSignal, int idx, int denom, float strength) {
		int avg = channelSum / denom;
		if (starSignal == null) {
			return avg;
		}
		int signal = starSignal[idx];
		if (signal <= 0) {
			return avg;
		}
		float excess = (signal - avg) - STAR_THRESHOLD;
		if (excess <= 0.0f) {
			return avg;
		}
		float blend = Math.min(1.0f, excess / STAR_RAMP) * strength;
		return Mth.clamp(Math.round(avg + (signal - avg) * blend), 0, 255);
	}

	public void reset() {
		sum = null;
		peak = null;
		frames = 0;
	}
}
