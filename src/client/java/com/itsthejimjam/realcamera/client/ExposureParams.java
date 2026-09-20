package com.itsthejimjam.realcamera.client;

import java.util.List;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.client.mixin.PostChainAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.util.Mth;

/**
 * Live "film" settings for the finishing pass: overall exposure from the ISO / shutter /
 * aperture triangle plus a compensation dial, and ISO-driven grain.
 *
 * <p>Baseline is f/2.8 · 1/1000 s · ISO 100 — a well-exposed midday frame under a
 * shader pack, referenced to modern mirrorless. Stopping the aperture down or using
 * a faster shutter darkens the frame; opening up, a slower shutter, or a higher ISO
 * brightens it — exactly like balancing a real exposure.
 *
 * <p>1.20.4: no std140 buffer — uniforms are set directly on the {@code blit} pass's
 * {@code EffectInstance} (index 3 of the chain).
 */
public final class ExposureParams {

	/** Overall level at the baseline settings (f/2.8 · 1/1000 · ISO 100). 0 = neutral. */
	private static final double CALIBRATION_EV = 0.0;
	/** Baseline shutter, in seconds. */
	private static final double SHUTTER_BASE_S = 1.0 / 1000.0;
	/** Keep the exposure multiplier sane even at extremes. */
	private static final double EV_MIN = -8.0;
	private static final double EV_MAX = 10.0;

	// Per-control weights on the (already shader-tonemapped) image. Below a real camera's
	// 1:1 so the whole thing is gentler; shutter, aperture and ISO all push brightness in
	// the physical direction (longer shutter / wider aperture / higher ISO = brighter).
	private static final double EV_PER_SHUTTER_STOP = 0.30;
	private static final double EV_PER_ISO_STOP = 0.50;
	private static final double EV_PER_APERTURE_STOP = 0.40;
	// Soft shoulder on the bright side: above the knee, EV keeps rising (a slower shutter
	// is always brighter) but with diminishing returns, so a long exposure doesn't
	// explode to pure white. The dark side and the normal range are untouched.
	private static final double SHOULDER_KNEE = 1.5;
	private static final double SHOULDER_WIDTH = 2.0;

	// Camera vs eyes: the shader pack auto-exposes the scene so a player can always see.
	// Before the photographic exposure we darken the frame back down by the real scene
	// light (time of day, moon, weather), so a night shot is genuinely dark at base
	// settings and needs a long shutter + high ISO, like a real sensor.
	private static final double NIGHT_FLOOR = 0.03;
	private static final double MOON_GAIN = 0.10;
	/** Warm/cool strength of the white-balance shift at the dial extremes. */
	private static final float WB_STRENGTH = 0.22f;

	/** Grain "resolution": cells across the frame height at the default grain size.
	 *  Screen-relative so it survives high-res capture + supersample downscale instead of
	 *  averaging away. The config's grain-size dial divides into this. */
	private static final float GRAIN_DENSITY = 800.0f;
	/** Stops past the (configurable) onset ISO over which grain climbs to full. */
	private static final double GRAIN_RANGE_STOPS = 4.0;

	private ExposureParams() {
	}

	/** Identity up to {@link #SHOULDER_KNEE}, then a gentle logarithmic rise — always
	 *  increasing, never flat, so brighter settings stay brighter without blowing out. */
	private static double softShoulder(double ev) {
		if (ev <= SHOULDER_KNEE) {
			return ev;
		}
		return SHOULDER_KNEE + SHOULDER_WIDTH * Math.log1p((ev - SHOULDER_KNEE) / SHOULDER_WIDTH);
	}

	/** 0..~1 real scene light: 1 in full daylight, {@link #NIGHT_FLOOR} at deep night,
	 *  with a little moonlight lift; drops in rain / thunder via the vanilla sky-darken. */
	private static double sceneLight() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return 1.0;
		}
		// level.getSkyDarken() — the plain no-arg getter 26.2 uses — returns the raw 0..11
		// vanilla field (0 = day, 11 = night). ClientLevel ALSO has a same-named
		// getSkyDarken(float partialTick) overload with a completely different, unrelated
		// range (~0.2 at night .. ~1.0 at day, vanilla's per-frame sky/fog blend factor,
		// confirmed via javap on ClientLevel's actual bytecode) — the 1.20.4 port picked
		// that overload by mistake. Dividing THAT by 11 collapses day and night to nearly
		// the same tiny fraction, so this always evaluated to ~0.95-1.0 regardless of the
		// actual time of day and the night-darkening feature never engaged.
		double daylight = 1.0 - Mth.clamp(level.getSkyDarken() / 11.0, 0.0, 1.0);
		double night = 1.0 - daylight;
		return Mth.clamp(NIGHT_FLOOR + (1.0 - NIGHT_FLOOR) * daylight + night * MOON_GAIN, NIGHT_FLOOR, 1.1);
	}

	public static void apply(PostChain chain, float aperture, double shutterSeconds, int iso,
			float expComp, float whiteBalance) {
		apply(chain, aperture, shutterSeconds, iso, expComp, whiteBalance, 0.0f);
	}

	/** {@code ndStops} — a physical ND filter, a clean minus-N stops on the final exposure. */
	public static void apply(PostChain chain, float aperture, double shutterSeconds, int iso,
			float expComp, float whiteBalance, float ndStops) {
		List<PostPass> passes = passes(chain);
		if (passes == null || passes.size() < 4) {
			return;
		}
		EffectInstance blit = passes.get(3).getEffect();
		if (blit == null) {
			return;
		}

		double log2 = Math.log(2.0);
		double apStops = -2.0 * Math.log(Math.max(aperture, 0.5f) / 2.8) / log2;
		double shStops = Math.log(Math.max(shutterSeconds, 1e-6) / SHUTTER_BASE_S) / log2;
		double isoStops = Math.log(Math.max(iso, 1) / 100.0) / log2;

		double rawEV = CALIBRATION_EV + expComp
				+ apStops * EV_PER_APERTURE_STOP
				+ shStops * EV_PER_SHUTTER_STOP
				+ isoStops * EV_PER_ISO_STOP;
		double totalEV = softShoulder(rawEV);
		totalEV = Math.max(EV_MIN, Math.min(EV_MAX, totalEV));
		// The bracket offset is a clean ± stops applied after the artistic curve + clamp,
		// so a +2 EV frame is genuinely 4x the base exposure across the whole tonal range,
		// not squashed by the soft shoulder.
		double biasedEV = totalEV + PhotoCapture.bracketBiasEv() - ndStops;
		float exposureMult = (float) (Math.pow(2.0, biasedEV) * sceneLight());

		// Grain onset is keyed to the true ISO stops, not the weighted exposure. The ISO
		// at which grain starts, its strength and its cell size are all user-tunable.
		com.itsthejimjam.realcamera.client.config.PhotoConfig cfg =
				com.itsthejimjam.realcamera.client.config.PhotoConfig.get();
		double grainThresholdStops = Math.log(cfg.grainOnsetIso() / 100.0) / log2;
		double gOver = (isoStops - grainThresholdStops) / GRAIN_RANGE_STOPS;
		float grain = (float) Math.pow(Math.max(0.0, Math.min(1.0, gOver)), 1.1);
		grain = Math.max(0.0f, Math.min(3.0f, grain * cfg.grainAmount()));
		float grainDensity = GRAIN_DENSITY / cfg.grainSize();

		// -1..+1 packed as an R/B channel scale for the shader.
		float wb = Math.max(-1.0f, Math.min(1.0f, whiteBalance)) * WB_STRENGTH;

		blit.safeGetUniform("ExposureMult").set(exposureMult);
		blit.safeGetUniform("GrainAmount").set(grain);
		blit.safeGetUniform("GrainDensity").set(grainDensity);
		blit.safeGetUniform("WhiteBalance").set(wb);
	}

	private static List<PostPass> passes(PostChain chain) {
		try {
			return ((PostChainAccessor) chain).realcamera$passes();
		} catch (Throwable t) {
			PhotoMode.LOGGER.warn("[Photo Mode] Exposure pass lookup failed: {}", t.toString());
			return null;
		}
	}
}
