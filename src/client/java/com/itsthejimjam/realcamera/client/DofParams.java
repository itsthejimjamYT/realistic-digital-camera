package com.itsthejimjam.realcamera.client;

import java.util.List;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.client.mixin.PostChainAccessor;
import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;

/**
 * Pushes live depth-of-field settings into the {@code dof} (or {@code dof_shaderpack})
 * pass's shader uniforms each frame, so aperture / focal length / focus point can change
 * without rebuilding the chain.
 *
 * <p>The circle of confusion is dioptric (scale-tolerant); this class only supplies the
 * ramp rate ({@code BlurStrength}) and the peak blur radius ({@code MaxRadiusFrac}).
 * Longer focal length and wider aperture raise both; an ultra-wide lens collapses the
 * radius toward zero so almost everything stays in focus, like a real lens.
 *
 * <p>1.20.4 has no {@code GpuBuffer}/std140 uniform-buffer system — every uniform is set
 * directly via {@code EffectInstance.getUniform(name).set(...)} on each pass's own
 * shader, found through {@link PostChainAccessor} (no buffer-swap "ensure" step needed).
 * {@code MaxRadiusFrac} is shared by three passes (the two pre-blurs and the DoF gather
 * itself), so it's set on all three.
 */
public final class DofParams {

	/** focalFactor of the 70 deg base lens: 1 / tan(35 deg). ratio is measured against this. */
	private static final float REF_FOCAL = 1.428f;
	private static final float RADIUS_WIDE = 0.010f;   // peak blur radius frac at the base lens
	private static final float RADIUS_MAX = 0.022f;    // cap at long zoom
	private static final float BLUR_SCALE = 0.52f;     // global bokeh trim (~one stop)
	private static final double WIDE_FALLOFF_EXP = 2.3; // how fast bokeh collapses below the base lens
	private static final float TELE_SLOPE = 0.7f;       // sub-linear growth above the base lens

	private static boolean focusRemapLogged = false;

	private DofParams() {
	}

	public static void apply(PostChain chain, float aperture, float focusU, float focusV) {
		List<PostPass> passes = passes(chain);
		if (passes == null || passes.size() < 4) {
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

		// prefilterh, prefilterv — only need the shared blur radius.
		setUniform(passes.get(0), "MaxRadiusFrac", maxRadius);
		setUniform(passes.get(1), "MaxRadiusFrac", maxRadius);

		// dof / dof_shaderpack — the full config.
		EffectInstance dof = passes.get(2).getEffect();
		if (dof != null) {
			dof.safeGetUniform("BlurStrength").set(blurStrength);
			dof.safeGetUniform("MaxRadiusFrac").set(maxRadius);
			dof.safeGetUniform("FocusUV").set(fu, fv);
			dof.safeGetUniform("FarBlurGain").set(cfg.backgroundBlurGain());
			dof.safeGetUniform("SoftKnee").set(cfg.focusTransitionSoftness());
			dof.safeGetUniform("HlBoost").set(cfg.highlightBloom());
			dof.safeGetUniform("HlThreshold").set(cfg.highlightThreshold());
			dof.safeGetUniform("OnsetMaxPx").set(cfg.blurOnsetPixels());
		}
	}

	private static void setUniform(PostPass pass, String name, float value) {
		EffectInstance effect = pass.getEffect();
		if (effect != null) {
			effect.safeGetUniform(name).set(value);
		}
	}

	private static List<PostPass> passes(PostChain chain) {
		try {
			return ((PostChainAccessor) chain).realcamera$passes();
		} catch (Throwable t) {
			PhotoMode.LOGGER.warn("[Photo Mode] DoF pass lookup failed: {}", t.toString());
			return null;
		}
	}
}
