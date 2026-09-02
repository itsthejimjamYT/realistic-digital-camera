package com.itsthejimjam.realcamera.client;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * CPU accumulator for a long exposure. Each sub-frame is read back from the GPU and
 * folded into a running per-channel sum; {@link #finish()} averages it and produces the
 * stacked image, which is written back into {@code minecraft:main} for the DoF / grade /
 * grain chain to process.
 */
public final class ExposureStack {

	private int width;
	private int height;
	private int frames;

	/** Running per-channel sum across the folded sub-frames. */
	private int[] sum;

	public void begin(int width, int height) {
		this.width = width;
		this.height = height;
		this.frames = 0;
		this.sum = new int[width * height * 3];
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
				int p = image.getPixel(x, y);
				sum[i] += (p >> 16) & 0xFF;
				sum[i + 1] += (p >> 8) & 0xFF;
				sum[i + 2] += p & 0xFF;
				i += 3;
			}
		}
		frames++;
	}

	/**
	 * Produce the stacked image and release the accumulator. Caller owns/closes it.
	 * Rows are written flipped: {@code Screenshot.takeScreenshot} hands us a top-down
	 * image, but this goes back into a bottom-up GPU texture, so it must be re-flipped.
	 */
	public NativeImage finish() {
		NativeImage out = new NativeImage(width, height, false);
		int denom = Math.max(1, frames);
		int i = 0;
		for (int y = 0; y < height; y++) {
			int outY = height - 1 - y;
			for (int x = 0; x < width; x++) {
				int r = sum[i] / denom;
				int g = sum[i + 1] / denom;
				int b = sum[i + 2] / denom;
				out.setPixel(x, outY, 0xFF000000 | (r << 16) | (g << 8) | b);
				i += 3;
			}
		}
		reset();
		return out;
	}

	public void reset() {
		sum = null;
		frames = 0;
	}
}
