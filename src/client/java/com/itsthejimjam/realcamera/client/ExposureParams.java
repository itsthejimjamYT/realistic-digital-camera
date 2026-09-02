package com.itsthejimjam.realcamera.client;

import java.nio.ByteBuffer;
import java.util.Map;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.client.mixin.PostChainAccessor;
import com.itsthejimjam.realcamera.client.mixin.PostPassAccessor;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.util.Mth;

import org.lwjgl.system.MemoryUtil;

/**
 * Live "film" settings for the finishing pass of the DoF post chain: overall
 * exposure from the ISO / shutter / aperture triangle plus a compensation dial,
 * and ISO-driven grain. Same buffer-swap trick as {@link DofParams} — the pass's
 * own uniform buffer isn't a valid copy target, so we swap in our own and rewrite
 * it each frame.
 *
 * <p>Baseline is f/2.8 · 1/1000 s · ISO 100 — a well-exposed midday frame under a
 * shader pack, referenced to modern mirrorless. Stopping the aperture down or using
 * a faster shutter darkens the frame; opening up, a slower shutter, or a higher ISO
 * brightens it — exactly like balancing a real exposure.
 */
public final class ExposureParams {
	private static final String BLOCK = "FilmConfig";
	/** std140: float × 4 = 16 bytes. */
	private static final int SIZE = 16;
	private static final ByteBuffer SCRATCH = MemoryUtil.memAlloc(SIZE);

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

	private static GpuBuffer buffer;

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
	 *  with a little moonlight lift; drops in rain / thunder via getSkyDarken. */
	private static double sceneLight() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return 1.0;
		}
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
		if (!ensureBuffer(chain)) {
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

		SCRATCH.clear();
		Std140Builder.intoBuffer(SCRATCH)
				.putFloat(exposureMult)
				.putFloat(grain)
				.putFloat(grainDensity)
				.putFloat(wb);
		SCRATCH.rewind();

		RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), SCRATCH);
	}

	/** Make sure the FilmConfig pass is using our writable buffer. */
	private static boolean ensureBuffer(PostChain chain) {
		try {
			for (PostPass pass : ((PostChainAccessor) chain).realcamera$passes()) {
				Map<String, GpuBuffer> uniforms = ((PostPassAccessor) pass).realcamera$customUniforms();
				GpuBuffer current = uniforms.get(BLOCK);
				if (current == null) {
					continue;
				}
				if (current == buffer) {
					return true;
				}
				if (buffer == null) {
					buffer = RenderSystem.getDevice().createBuffer(
							() -> "realcamera FilmConfig",
							GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
							(long) SIZE);
				}
				uniforms.put(BLOCK, buffer);
				current.close();
				return true;
			}
		} catch (Throwable t) {
			PhotoMode.LOGGER.warn("[Photo Mode] Film uniform setup failed: {}", t.toString());
		}
		return false;
	}
}
