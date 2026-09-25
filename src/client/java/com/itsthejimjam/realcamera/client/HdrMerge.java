package com.itsthejimjam.realcamera.client;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.itsthejimjam.realcamera.PhotoMode;

import net.minecraft.util.Mth;

/**
 * Fuses N bracket-exposure frames — each already run through {@link HdrCapture}'s
 * non-destructive expose pass — into one 16-bit HDR file, via a per-pixel weighted
 * average ("well-exposedness" exposure fusion: a frame contributes more to a pixel the
 * closer that pixel's luminance sits to mid-grey in THAT frame's own exposure). Brackets
 * are captured with the world frozen and no camera movement, so there's no alignment or
 * ghosting to fight — a plain weighted blend is enough for v1.
 *
 * <p>All mutable state ({@code colorSum}/{@code weightSum}) lives only on a private
 * single-thread executor this class owns — every public method just submits a task to
 * it, so {@link #begin}/{@link #addFrame}/{@link #finishAsync} are strictly ordered
 * (FIFO) with no locks, atomics, or session bookkeeping needed. Correctness depends on
 * the caller ({@link PhotoCapture}) only ever running one bracket sequence at a time and
 * calling these in order — true today, since the bracket state machine already
 * serializes captures one frame at a time.
 */
public final class HdrMerge {
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "realcamera-hdr-merge");
		t.setDaemon(true);
		return t;
	});

	// Only ever touched from EXECUTOR's single thread.
	private static float[] colorSum;
	private static float[] weightSum;
	private static int width;
	private static int height;
	private static int framesExpected;
	private static int framesAdded;

	private HdrMerge() {
	}

	/** Start a new merge session — call once, on the render thread, right before the
	 *  first bracket frame's readback is dispatched. */
	public static void begin(int mergeWidth, int mergeHeight, int frameCount) {
		EXECUTOR.execute(() -> {
			width = mergeWidth;
			height = mergeHeight;
			framesExpected = frameCount;
			framesAdded = 0;
			colorSum = new float[mergeWidth * mergeHeight * 3];
			weightSum = new float[mergeWidth * mergeHeight];
		});
	}

	/** Accumulate one bracket frame's exposed output — raw, tightly-packed, bottom-up
	 *  RGBA16_FLOAT bytes, exactly what {@link HdrCapture#readForMerge} hands back. Must
	 *  be called in bracket-frame order — guaranteed by the caller, since bracket frames
	 *  are captured strictly serially and their readback callbacks fire in that same
	 *  order. */
	public static void addFrame(int frameWidth, int frameHeight, byte[] rgba16f) {
		EXECUTOR.execute(() -> {
			if (colorSum == null || frameWidth != width || frameHeight != height) {
				// A missing begin(), or a size mismatch (shouldn't happen — bracket frames
				// all render at the same resolution) — drop rather than write out of bounds.
				PhotoMode.LOGGER.warn("[Photo Mode] HdrMerge: dropped a frame ({}x{}, expected {}x{})",
						frameWidth, frameHeight, width, height);
				return;
			}
			int pixels = width * height;
			float[] frameRgb = new float[pixels * 3];
			float[] frameWeight = new float[pixels];
			float[] frameLuma = new float[pixels];
			int rowBytes = width * 8; // RGBA16_FLOAT: 4 channels x 2 bytes
			for (int outY = 0; outY < height; outY++) {
				int srcY = height - 1 - outY; // GPU texture is bottom-up; accumulator is top-down
				int rowBase = srcY * rowBytes;
				int outRowBase = outY * width;
				for (int x = 0; x < width; x++) {
					int px = rowBase + x * 8;
					float r = PngWriter.halfToFloat(rgba16f, px);
					float g = PngWriter.halfToFloat(rgba16f, px + 2);
					float b = PngWriter.halfToFloat(rgba16f, px + 4);

					int op = outRowBase + x;
					int op3 = op * 3;
					frameRgb[op3] = r;
					frameRgb[op3 + 1] = g;
					frameRgb[op3 + 2] = b;

					// Well-exposedness: a Gaussian favouring mid-grey (0.5), so a frame
					// contributes most wherever IT rendered that pixel best-exposed —
					// under-/over-exposed pixels in this frame fade toward zero weight,
					// letting a differently-exposed bracket frame take over there. The
					// small epsilon floor means a pixel poorly exposed in EVERY frame
					// still falls back to an unweighted average instead of dividing by
					// ~zero.
					float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
					frameLuma[op] = luma;
					float d = luma - 0.5f;
					frameWeight[op] = (float) Math.exp(-(d * d) / (2.0 * 0.25 * 0.25)) + 1e-4f;
				}
			}

			// Smooth the weight map before blending, but EDGE-AWARE: a plain blur (the
			// first version of this) bled colour across strong scene edges — a cloud
			// silhouette, a tree line — because it smooths the weight the same amount
			// everywhere, producing a visible glow/ghost ring wherever two very
			// differently-exposed frames met at a hard boundary. A guided filter (He et
			// al.), using this frame's own luminance as the guide, only smooths WITHIN
			// regions the actual scene treats as roughly uniform — a hard edge in frameLuma
			// stops the blend from crossing it, while flat/low-contrast areas (like
			// dappled leaf light, the original mottling problem) still get smoothed.
			int radius = Mth.clamp(Math.round(height * 0.01f), 4, 32);
			float[] smoothedWeight = guidedFilter(frameWeight, frameLuma, width, height, radius, 0.02f);

			for (int p = 0; p < pixels; p++) {
				float weight = Math.max(smoothedWeight[p], 1e-4f);
				int p3 = p * 3;
				colorSum[p3] += weight * frameRgb[p3];
				colorSum[p3 + 1] += weight * frameRgb[p3 + 1];
				colorSum[p3 + 2] += weight * frameRgb[p3 + 2];
				weightSum[p] += weight;
			}
			framesAdded++;
		});
	}

	/** Edge-aware smoothing of {@code p} (the weight map), guided by {@code guide} (this
	 *  frame's own luminance) — the standard box-filter formulation of a guided filter,
	 *  built entirely from {@link #boxBlur} calls. {@code eps} controls how "flat" the
	 *  guide has to look locally before smoothing kicks in: near a real scene edge (high
	 *  local variance in {@code guide}) the filter passes {@code p} through close to
	 *  unchanged; well inside a flat region it smooths freely. */
	private static float[] guidedFilter(float[] p, float[] guide, int w, int h, int radius, float eps) {
		int n = w * h;
		float[] meanI = boxBlur(guide, w, h, radius);
		float[] meanP = boxBlur(p, w, h, radius);
		float[] corrI = new float[n];
		float[] corrIP = new float[n];
		for (int i = 0; i < n; i++) {
			corrI[i] = guide[i] * guide[i];
			corrIP[i] = guide[i] * p[i];
		}
		corrI = boxBlur(corrI, w, h, radius);
		corrIP = boxBlur(corrIP, w, h, radius);

		float[] a = new float[n];
		float[] b = new float[n];
		for (int i = 0; i < n; i++) {
			float varI = corrI[i] - meanI[i] * meanI[i];
			float covIP = corrIP[i] - meanI[i] * meanP[i];
			float ai = covIP / (varI + eps);
			a[i] = ai;
			b[i] = meanP[i] - ai * meanI[i];
		}
		float[] meanA = boxBlur(a, w, h, radius);
		float[] meanB = boxBlur(b, w, h, radius);

		float[] out = new float[n];
		for (int i = 0; i < n; i++) {
			out[i] = meanA[i] * guide[i] + meanB[i];
		}
		return out;
	}

	/** A gentle default look for the merged file, applied in place — the raw weighted
	 *  average on its own reads as flat/log-like (expected: nothing here has any contrast
	 *  applied yet, unlike the normal photo's destructive camera-response curve). A mild
	 *  S-curve and a touch of saturation make it look more finished without editing
	 *  first, while deliberately staying far gentler than the normal photo's grade — no
	 *  shadow toe-crush, and only the low end is clamped (at 0, which is physically
	 *  required), never the top, so real highlight headroom above "white" survives into
	 *  the 16-bit encode exactly as before. */
	private static void applyGentleLook(float[] rgb) {
		final float pivot = 0.45f;
		final float contrast = 1.15f;
		final float sat = 1.05f;
		for (int p = 0; p < rgb.length; p += 3) {
			float r = (rgb[p] - pivot) * contrast + pivot;
			float g = (rgb[p + 1] - pivot) * contrast + pivot;
			float b = (rgb[p + 2] - pivot) * contrast + pivot;
			float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
			r = luma + (r - luma) * sat;
			g = luma + (g - luma) * sat;
			b = luma + (b - luma) * sat;
			rgb[p] = Math.max(0.0f, r);
			rgb[p + 1] = Math.max(0.0f, g);
			rgb[p + 2] = Math.max(0.0f, b);
		}
	}

	/** Separable box blur, clamped at the edges, via a running sum — O(w*h) regardless of
	 *  radius, which matters here since a frame can be up to ~33M pixels (8K). */
	private static float[] boxBlur(float[] src, int w, int h, int radius) {
		float[] tmp = new float[w * h];
		float[] out = new float[w * h];
		int win = radius * 2 + 1;

		for (int y = 0; y < h; y++) {
			int row = y * w;
			float sum = 0.0f;
			for (int x = -radius; x <= radius; x++) {
				sum += src[row + Mth.clamp(x, 0, w - 1)];
			}
			tmp[row] = sum / win;
			for (int x = 1; x < w; x++) {
				sum += src[row + Mth.clamp(x + radius, 0, w - 1)];
				sum -= src[row + Mth.clamp(x - radius - 1, 0, w - 1)];
				tmp[row + x] = sum / win;
			}
		}

		for (int x = 0; x < w; x++) {
			float sum = 0.0f;
			for (int y = -radius; y <= radius; y++) {
				sum += tmp[Mth.clamp(y, 0, h - 1) * w + x];
			}
			out[x] = sum / win;
			for (int y = 1; y < h; y++) {
				sum += tmp[Mth.clamp(y + radius, 0, h - 1) * w + x];
				sum -= tmp[Mth.clamp(y - radius - 1, 0, h - 1) * w + x];
				out[y * w + x] = sum / win;
			}
		}
		return out;
	}

	/** Normalize the accumulated blend and write the merged file, then run
	 *  {@code onDone} (on this same background thread — hop back to the render/main
	 *  thread yourself if you touch client state). Call once, right after the last
	 *  frame's {@link #addFrame}; FIFO ordering on the single executor thread guarantees
	 *  this runs after every {@code addFrame} queued before it. */
	public static void finishAsync(File file, PngWriter.Exif exif, Runnable onDone) {
		EXECUTOR.execute(() -> {
			if (colorSum == null) {
				return;
			}
			if (framesAdded != framesExpected) {
				PhotoMode.LOGGER.warn("[Photo Mode] HdrMerge: finishing with {} of {} frames",
						framesAdded, framesExpected);
			}
			int pixels = width * height;
			float[] rgb = new float[pixels * 3];
			for (int p = 0; p < pixels; p++) {
				float w = Math.max(weightSum[p], 1e-4f);
				rgb[p * 3] = colorSum[p * 3] / w;
				rgb[p * 3 + 1] = colorSum[p * 3 + 1] / w;
				rgb[p * 3 + 2] = colorSum[p * 3 + 2] / w;
			}
			colorSum = null;
			weightSum = null;
			applyGentleLook(rgb);
			try {
				File dir = file.getParentFile();
				if (dir != null) {
					dir.mkdirs();
				}
				PngWriter.write16FromFloatRgb(file, width, height, rgb, exif);
				PhotoMode.LOGGER.info("[Photo Mode] HdrMerge: HDR file written: {}", file.getName());
			} catch (Exception e) {
				PhotoMode.LOGGER.error("[Photo Mode] failed to save HDR merge", e);
			} finally {
				if (onDone != null) {
					onDone.run();
				}
			}
		});
	}
}
