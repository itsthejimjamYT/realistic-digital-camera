package com.itsthejimjam.realcamera.client.mixin;

import java.util.List;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.client.AidParams;
import com.itsthejimjam.realcamera.client.DofParams;
import com.itsthejimjam.realcamera.client.ExposureParams;
import com.itsthejimjam.realcamera.client.FilmParams;
import com.itsthejimjam.realcamera.client.PhotoCapture;
import com.itsthejimjam.realcamera.client.PhotoModeSession;
import com.itsthejimjam.realcamera.client.ShaderPackCompat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs photo mode's DoF/film-grade effect chain, injected right after
 * {@code LevelRenderer.doEntityOutline()} (after the level has rendered, before the GUI) —
 * the 1.20.4 analogue of the seam 26.2 uses.
 *
 * <p>1.20.4 has no {@code ShaderManager} chain cache — two {@link PostChain}s (a
 * vanilla-depth one and a shader-pack-depth one) are constructed once here, lazily, and
 * kept for the life of the game, resized on demand.
 *
 * <p>Which one runs each frame follows whether {@link ShaderPackCompat#sceneDepthTextureId()}
 * hands back a usable depth texture — NOT {@link ShaderPackCompat#shaderPackActive()} ("a
 * pack is loaded"). Iris's reflected depth is the only depth source that actually works on
 * this version: <b>depth-of-field on MC &lt; 26.2 requires a shader pack (Iris).</b> With
 * no shader pack the vanilla {@code minecraft:main:depth} auxtarget reads a flat,
 * never-populated 1.0 for reasons that resisted a very long investigation (GL depth
 * state, framebuffer binding, the projection matrix inputs, and vanilla's own terrain
 * shader all check out as correct, yet the depth texture stays uniformly at the clear
 * value once photo mode is active — while colour renders perfectly). When that happens the
 * vanilla chain still runs, but {@code realcamera_dof.fsh} sees every pixel as "sky"
 * (raw depth 1.0), computes a zero circle-of-confusion, and passes the sharp frame
 * straight through — so exposure / white balance / film grade / grain (all in the blit
 * pass) still work, there's just no blur.
 */
@Mixin(GameRenderer.class)
public class LevelPostMixin {

	private static PostChain realcamera$chainVanilla;
	private static boolean realcamera$vanillaLoadFailed;
	private static PostChain realcamera$chainShaderpack;
	private static boolean realcamera$shaderpackLoadFailed;
	private static int realcamera$lastW = -1;
	private static int realcamera$lastH = -1;
	private static boolean realcamera$lastWasShaderpack;
	private static boolean realcamera$noDepthLogged;

	@Inject(
			method = "render(FJZ)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/LevelRenderer;doEntityOutline()V",
					shift = At.Shift.AFTER))
	private void realcamera$addEffectChain(float partialTick, long finishTimeNano, boolean tick, CallbackInfo ci) {
		if (!PhotoModeSession.isActive()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		// Iris's real depth first, regardless of whether a custom pack is loaded — its
		// reflected depth texture is the only one that carries usable data on this version.
		int irisDepthTexId = ShaderPackCompat.sceneDepthTextureId();
		boolean useShaderpack = irisDepthTexId >= 0;
		PostChain chain = useShaderpack ? realcamera$getShaderpackChain(mc) : realcamera$getVanillaChain(mc);
		if (chain == null) {
			return;
		}

		if (!useShaderpack && !realcamera$noDepthLogged) {
			realcamera$noDepthLogged = true;
			PhotoMode.LOGGER.info("[Photo Mode] no shader-pack depth available — depth-of-field "
					+ "is inactive (exposure / white balance / film grade still apply). "
					+ "DoF on MC < 26.2 needs a shader pack.");
		}

		int w = mc.getMainRenderTarget().width;
		int h = mc.getMainRenderTarget().height;
		// resize() reallocates every intermediate render target — skip it when the
		// framebuffer size hasn't actually changed (it's called every frame otherwise,
		// which is needlessly expensive at a large capture resolution). A switch between
		// the vanilla/shader-pack chain also needs a resize — each chain owns its own set
		// of intermediate targets, sized independently.
		if (w != realcamera$lastW || h != realcamera$lastH || useShaderpack != realcamera$lastWasShaderpack) {
			chain.resize(w, h);
			realcamera$lastW = w;
			realcamera$lastH = h;
			realcamera$lastWasShaderpack = useShaderpack;
		}

		DofParams.apply(chain, PhotoModeSession.getAperture(),
				PhotoModeSession.getFocusU(), PhotoModeSession.getFocusV());
		ExposureParams.apply(chain, PhotoModeSession.getAperture(), PhotoModeSession.getShutterSeconds(),
				PhotoModeSession.getIso(), PhotoModeSession.getExposureComp(), PhotoModeSession.getWhiteBalance(),
				PhotoModeSession.filterNd());
		FilmParams.apply(chain, PhotoModeSession.getRecipeIndex(), PhotoModeSession.getRecipeStrength(),
				PhotoModeSession.filterPolar(), PhotoModeSession.filterMist());
		AidParams.apply(chain);

		int[] depthSize = null;
		if (useShaderpack) {
			// Iris manages its own depth textures separately from vanilla's main depth
			// buffer — feed the DoF pass the pack's real depth directly, bypassing the
			// JSON auxtarget wiring.
			depthSize = ShaderPackCompat.sceneDepthSize();
			List<PostPass> passes = ((PostChainAccessor) chain).realcamera$passes();
			if (passes != null && passes.size() >= 3) {
				EffectInstance dof = passes.get(2).getEffect();
				if (dof != null) {
					dof.setSampler("MainDepthSampler", () -> irisDepthTexId);
				}
			}
		}

		chain.process(partialTick);

		boolean atCaptureSize = w == PhotoCapture.overrideWidth() && h == PhotoCapture.overrideHeight();
		// For the shader-pack path, also wait for Iris's own render targets to have caught
		// up to the capture resolution — otherwise DoF briefly samples a stale-sized depth
		// texture (mismatched aspect against the new frame) right after a capture resize.
		boolean depthMatches = !useShaderpack || (depthSize != null && depthSize[0] == w && depthSize[1] == h);
		if (atCaptureSize && depthMatches) {
			PhotoCapture.markChainReady();
		}
	}

	private static PostChain realcamera$getVanillaChain(Minecraft mc) {
		if (realcamera$chainVanilla != null || realcamera$vanillaLoadFailed) {
			return realcamera$chainVanilla;
		}
		try {
			// "minecraft" namespace, not our own — EffectInstance's constructor hardcodes
			// a "shaders/program/<name>.json" string concat BEFORE parsing it as a
			// ResourceLocation, so a namespaced pass "name" (e.g. "realcamera:dof") comes
			// out as the unparseable "shaders/program/realcamera:dof.json" and throws.
			// Our own shader/post_effect files live under assets/minecraft/shaders/...,
			// prefixed "realcamera_" to avoid colliding with vanilla's own filenames.
			realcamera$chainVanilla = new PostChain(mc.getTextureManager(), mc.getResourceManager(),
					mc.getMainRenderTarget(), new ResourceLocation("minecraft", "shaders/post/realcamera_dof.json"));
		} catch (Throwable t) {
			realcamera$vanillaLoadFailed = true;
			PhotoMode.LOGGER.error("[Photo Mode] dof post chain failed to load", t);
		}
		return realcamera$chainVanilla;
	}

	private static PostChain realcamera$getShaderpackChain(Minecraft mc) {
		if (realcamera$chainShaderpack != null || realcamera$shaderpackLoadFailed) {
			return realcamera$chainShaderpack;
		}
		try {
			realcamera$chainShaderpack = new PostChain(mc.getTextureManager(), mc.getResourceManager(),
					mc.getMainRenderTarget(),
					new ResourceLocation("minecraft", "shaders/post/realcamera_dof_shaderpack.json"));
		} catch (Throwable t) {
			realcamera$shaderpackLoadFailed = true;
			PhotoMode.LOGGER.error("[Photo Mode] dof_shaderpack post chain failed to load", t);
		}
		return realcamera$chainShaderpack;
	}
}
