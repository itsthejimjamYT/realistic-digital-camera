package com.itsthejimjam.realcamera.client;

import java.util.List;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.client.mixin.PostChainAccessor;

import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;

/**
 * Feeds the finishing ({@code blit}) pass's zebra (clip) warning and focus-peaking
 * overlays, plus an animation clock for the zebras. Both overlays are forced off during
 * a capture so they never bake into the saved photo.
 */
public final class AidParams {

	private AidParams() {
	}

	public static void apply(PostChain chain) {
		List<PostPass> passes;
		try {
			passes = ((PostChainAccessor) chain).realcamera$passes();
		} catch (Throwable t) {
			PhotoMode.LOGGER.warn("[Photo Mode] Aid pass lookup failed: {}", t.toString());
			return;
		}
		if (passes == null || passes.size() < 4) {
			return;
		}
		EffectInstance blit = passes.get(3).getEffect();
		if (blit == null) {
			return;
		}

		boolean capturing = PhotoCapture.wantsBigFrame();
		float zebras = !capturing && DisplayAids.zebrasOn() ? 1.0f : 0.0f;
		float peaking = !capturing && DisplayAids.peakingOn() ? 1.0f : 0.0f;
		float time = (float) ((System.currentTimeMillis() % 100000L) / 1000.0);

		blit.safeGetUniform("Zebras").set(zebras);
		blit.safeGetUniform("Peaking").set(peaking);
		blit.safeGetUniform("AidTime").set(time);
	}
}
