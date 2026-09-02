package com.itsthejimjam.realcamera.client;

import java.nio.ByteBuffer;
import java.util.Map;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.client.mixin.PostChainAccessor;
import com.itsthejimjam.realcamera.client.mixin.PostPassAccessor;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;

import org.lwjgl.system.MemoryUtil;

/**
 * Pushes live depth-of-field settings into the {@code DofConfig} uniform block of a
 * loaded post chain, so aperture / focal length / focus point can change without
 * rebuilding the chain.
 *
 * <p>The pass's own uniform buffer isn't a valid copy target, so on first use we swap
 * in our own writable buffer and rewrite it each frame.
 *
 * <p>The circle of confusion is dioptric (scale-tolerant); this class only supplies the
 * ramp rate ({@code BlurStrength}) and the peak blur radius ({@code MaxRadiusFrac}).
 * Longer focal length and wider aperture raise both; an ultra-wide lens collapses the
 * radius toward zero so almost everything stays in focus, like a real lens.
 */
public final class DofParams {
	private static final String BLOCK = "DofConfig";
	/** std140: float + float + vec2 + 5 float, rounded up to a multiple of 16 = 48 bytes. */
	private static final int SIZE = 48;
	private static final ByteBuffer SCRATCH = MemoryUtil.memAlloc(SIZE);

	/** focalFactor of the 70 deg base lens: 1 / tan(35 deg). ratio is measured against this. */
	private static final float REF_FOCAL = 1.428f;
	private static final float RADIUS_WIDE = 0.010f;   // peak blur radius frac at the base lens
	private static final float RADIUS_MAX = 0.022f;    // cap at long zoom
	private static final float BLUR_SCALE = 0.52f;     // global bokeh trim (~one stop)
	private static final double WIDE_FALLOFF_EXP = 2.3; // how fast bokeh collapses below the base lens
	private static final float TELE_SLOPE = 0.7f;       // sub-linear growth above the base lens

	private static GpuBuffer buffer;
	private static boolean focusRemapLogged = false;

	private DofParams() {
	}

	public static void apply(PostChain chain, float aperture, float focusU, float focusV) {
		if (!ensureBuffer(chain)) {
			return;
		}

		com.itsthejimjam.realcamera.client.config.PhotoConfig cfg =
				com.itsthejimjam.realcamera.client.config.PhotoConfig.get();

		float effectiveFov = PhotoModeSession.effectiveFov();
		double focalFactor = 1.0 / Math.tan(Math.toRadians(effectiveFov * 0.5));
		float clampAperture = Math.max(aperture, 0.5f);

		// Dioptric CoC ramp: gentle, a touch steeper at telephoto. "How blurry" is
		// carried mostly by the bokeh radius below, not this.
		float blurStrength = (float) (6.0 + 1.5 * focalFactor / clampAperture);
		blurStrength = Math.max(6.0f, Math.min(40.0f, blurStrength));
		blurStrength *= cfg.blurIntensity();

		// Peak bokeh radius: grows with focal length, opens with aperture. Below the
		// base lens it collapses steeply (ultra-wide ~ everything sharp); above it,
		// sub-linear so a hard zoom doesn't just peg the cap.
		double ratio = focalFactor / REF_FOCAL;
		double focalTerm = ratio <= 1.0
				? Math.pow(ratio, WIDE_FALLOFF_EXP)
				: 1.0 + (ratio - 1.0) * TELE_SLOPE;
		float maxRadius = (float) (RADIUS_WIDE * focalTerm * (2.8f / clampAperture) * BLUR_SCALE);
		maxRadius = Math.min(RADIUS_MAX, maxRadius) * cfg.blurRadius();
		maxRadius = Math.min(maxRadius, 0.060f); // safety cap so a big multiplier can't tank perf

		// The focus point is a screen UV. In the live preview that UV is relative to the
		// whole window; in a capture the framebuffer is the letterboxed crop, so the
		// same reticle position must be re-expressed relative to that crop or the shader
		// samples depth at the wrong world point and the subject goes soft.
		float fu = focusU;
		float fv = focusV;
		if (PhotoCapture.wantsBigFrame()) {
			int sw = PhotoCapture.savedWindowWidth();
			int sh = PhotoCapture.savedWindowHeight();
			if (sw <= 0 || sh <= 0) {
				Window win = Minecraft.getInstance().getWindow();
				sw = win.getScreenWidth();
				sh = win.getScreenHeight();
			}
			if (sw > 0 && sh > 0) {
				int[] crop = Framing.cropRect(sw, sh);       // [x, y, w, h], top-left origin
				float cu = (focusU * sw - crop[0]) / (float) crop[2];
				float cropBottom = sh - crop[1] - crop[3];   // to bottom-left origin
				float cv = (focusV * sh - cropBottom) / (float) crop[3];
				fu = Math.max(0.0f, Math.min(1.0f, cu));
				fv = Math.max(0.0f, Math.min(1.0f, cv));
				if (!focusRemapLogged) {
					focusRemapLogged = true;
					PhotoMode.LOGGER.info(
							"[Photo Mode] focus remap: win={}x{} crop=[{},{},{},{}] in=({},{}) out=({},{})",
							sw, sh, crop[0], crop[1], crop[2], crop[3],
							String.format("%.3f", focusU), String.format("%.3f", focusV),
							String.format("%.3f", fu), String.format("%.3f", fv));
				}
			}
		} else {
			focusRemapLogged = false;
		}

		SCRATCH.clear();
		Std140Builder.intoBuffer(SCRATCH)
				.putFloat(blurStrength)
				.putFloat(maxRadius)
				.putVec2(fu, fv)
				.putFloat(cfg.backgroundBlurGain())
				.putFloat(cfg.focusTransitionSoftness())
				.putFloat(cfg.highlightBloom())
				.putFloat(cfg.highlightThreshold())
				.putFloat(cfg.blurOnsetPixels())
				.putFloat(0.0f)   // pad to 48 (std140 rounds the block up to 16)
				.putFloat(0.0f)
				.putFloat(0.0f);
		SCRATCH.rewind();

		RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), SCRATCH);
	}

	/** Make sure the DofConfig passes are using our writable buffer. */
	private static boolean ensureBuffer(PostChain chain) {
		boolean any = false;
		try {
			for (PostPass pass : ((PostChainAccessor) chain).realcamera$passes()) {
				Map<String, GpuBuffer> uniforms = ((PostPassAccessor) pass).realcamera$customUniforms();
				GpuBuffer current = uniforms.get(BLOCK);
				if (current == null) {
					continue;
				}
				if (current == buffer) {
					any = true;
					continue;
				}
				if (buffer == null) {
					buffer = RenderSystem.getDevice().createBuffer(
							() -> "realcamera DofConfig",
							GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
							(long) SIZE);
				}
				uniforms.put(BLOCK, buffer);
				current.close();
				any = true;
			}
		} catch (Throwable t) {
			PhotoMode.LOGGER.warn("[Photo Mode] DoF uniform setup failed: {}", t.toString());
		}
		return any;
	}
}
