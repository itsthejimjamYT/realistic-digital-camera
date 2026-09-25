package com.itsthejimjam.realcamera.client;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * ARGB pixel access for {@link NativeImage}. On 1.21.1 {@code getPixelRGBA}/{@code setPixelRGBA}
 * are really ABGR (red in the low byte — the raw little-endian RGBA bytes), while 26.2's
 * {@code getPixel}/{@code setPixel}, which this mod's pixel code is written against, are
 * ARGB. Reading the ABGR value as ARGB silently swaps red and blue, so everything that looks
 * at individual channels (histogram, metering, the PNG/JPEG encoders) goes through here.
 */
final class Pixels {

	private Pixels() {
	}

	static int get(NativeImage image, int x, int y) {
		return swapRedBlue(image.getPixelRGBA(x, y));
	}

	static void set(NativeImage image, int x, int y, int argb) {
		image.setPixelRGBA(x, y, swapRedBlue(argb));
	}

	/** ARGB <-> ABGR (the swap is its own inverse). */
	static int swapRedBlue(int c) {
		return (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16);
	}
}
