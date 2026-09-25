package com.itsthejimjam.realcamera.client;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * CPU accumulator for a long exposure. Each sub-frame is read back from the GPU and
 * folded into a running per-channel sum; {@link #finish()} averages it into the stacked
 * image.
 *
 * <p>1.20.4 status: {@link PhotoCapture} doesn't yet feed the stacked image back through
 * a GPU texture for the DoF/grade chain (that chain isn't ported yet either) — it saves
 * the averaged image straight to disk, so unlike 26.2 this does NOT flip rows for a
 * bottom-up GPU texture upload. Revisit if/when long exposure is wired back through the
 * post-processing pipeline.
 */
public final class ExposureStack {

	private int width;
	private int height;
	private int frames;

	/** Most sub-frames a {@code char} sum can hold without overflowing (257 × 255 = 65535). */
	public static final int MAX_FRAMES = 257;
	/** Java-heap bytes per pixel: 3 channels × a 2-byte sum. */
	public static final int BYTES_PER_PIXEL = 6;

	/** Running per-channel sum across the folded sub-frames — unsigned 16-bit, so a stack
	 *  fits at the photo's full resolution (8K included) instead of forcing long exposures
	 *  down to a small cap. */
	private char[] sum;

	public void begin(int width, int height) {
		this.width = width;
		this.height = height;
		this.frames = 0;
		this.sum = new char[width * height * 3];
	}

	public boolean active() {
		return sum != null;
	}

	public int frames() {
		return frames;
	}

	/** Fold one sub-frame in. The image must match the stack's dimensions. */
	public void add(NativeImage image) {
		if (!active() || image.getWidth() != width || image.getHeight() != height || frames >= MAX_FRAMES) {
			return;
		}
		int i = 0;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int p = image.getPixelRGBA(x, y);
				sum[i] += (char) ((p >> 16) & 0xFF);
				sum[i + 1] += (char) ((p >> 8) & 0xFF);
				sum[i + 2] += (char) (p & 0xFF);
				i += 3;
			}
		}
		frames++;
	}

	/** Produce the stacked image and release the accumulator. Caller owns/closes it. */
	public NativeImage finish() {
		NativeImage out = new NativeImage(width, height, false);
		int denom = Math.max(1, frames);
		int i = 0;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int r = sum[i] / denom;
				int g = sum[i + 1] / denom;
				int b = sum[i + 2] / denom;
				out.setPixelRGBA(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
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
