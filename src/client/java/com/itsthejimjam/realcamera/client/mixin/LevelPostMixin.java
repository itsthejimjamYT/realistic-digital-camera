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
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs photo mode's DoF / film-grade effect chain at the right point of the frame for
 * whichever depth source is live.
 *
 * <p><b>No shader pack:</b> right after {@code LevelRenderer.renderLevel} returns, inside
 * {@code GameRenderer.renderLevel} — before it clears the depth buffer to draw the
 * first-person hand ({@code RenderSystem.clear(GL_DEPTH_BUFFER_BIT)} under the "hand"
 * profiler section). That's the last moment {@code minecraft:main:depth} still holds the
 * scene. This chain used to run later, from {@code doEntityOutline}, and so only ever saw
 * the cleared depth (a flat 1.0 — every pixel "sky", zero blur), which is why depth of
 * field used to need a shader pack on this version. Same seam 26.2 uses ("before the
 * always-on-top pass clears the depth buffer").
 *
 * <p><b>Shader pack (Iris):</b> after {@code doEntityOutline}, once the pack has
 * composited — anything written earlier would be overwritten by the pack's final pass.
 * Depth comes from the pack's own depth texture (see {@link ShaderPackCompat}).
 *
 * <p>Exactly one of the two runs per frame: the pick is whether the pack hands back a
 * usable depth texture this frame. 1.20.4 has no {@code ShaderManager} chain cache — the two
 * {@link PostChain}s are constructed here lazily, kept for the life of the game, and
 * resized on demand.
 */
@Mixin(GameRenderer.class)
public class LevelPostMixin {

	private static PostChain realcamera$chainVanilla;
	private static boolean realcamera$vanillaLoadFailed;
	private static PostChain realcamera$chainShaderpack;
	private static boolean realcamera$shaderpackLoadFailed;
	private static int realcamera$vanillaW = -1;
	private static int realcamera$vanillaH = -1;
	private static int realcamera$shaderpackW = -1;
	private static int realcamera$shaderpackH = -1;
	private static boolean realcamera$vanillaLogged;

	@Inject(
			method = "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V",
			at = @At(
					value = "INVOKE_STRING",
					target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
					args = "ldc=hand"))
	private void realcamera$vanillaDepthChain(float partialTick, long finishTimeNano, PoseStack pose, CallbackInfo ci) {
		if (!PhotoModeSession.isActive() || ShaderPackCompat.sceneDepthTextureId() >= 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		PostChain chain = realcamera$getVanillaChain(mc);
		if (chain == null) {
			return;
		}
		if (!realcamera$vanillaLogged) {
			realcamera$vanillaLogged = true;
			PhotoMode.LOGGER.info("[Photo Mode] vanilla-depth effect chain running");
		}
		int w = mc.getMainRenderTarget().width;
		int h = mc.getMainRenderTarget().height;
		// resize() reallocates every intermediate render target — only when the framebuffer
		// size actually changed (it's expensive at a large capture resolution).
		if (w != realcamera$vanillaW || h != realcamera$vanillaH) {
			chain.resize(w, h);
			realcamera$vanillaW = w;
			realcamera$vanillaH = h;
		}
		realcamera$applyParams(chain);
		realcamera$process(chain, partialTick);
		// We're mid-level-render here (the hand pass is next): restore the state it expects.
		RenderSystem.enableDepthTest();
		if (w == PhotoCapture.overrideWidth() && h == PhotoCapture.overrideHeight()) {
			PhotoCapture.markChainReady();
		}
	}

	@Inject(
			method = "render(FJZ)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/LevelRenderer;doEntityOutline()V",
					shift = At.Shift.AFTER))
	private void realcamera$shaderPackChain(float partialTick, long finishTimeNano, boolean tick, CallbackInfo ci) {
		if (!PhotoModeSession.isActive()) {
			return;
		}
		int irisDepthTexId = ShaderPackCompat.sceneDepthTextureId();
		if (irisDepthTexId < 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		PostChain chain = realcamera$getShaderpackChain(mc);
		if (chain == null) {
			return;
		}
		int w = mc.getMainRenderTarget().width;
		int h = mc.getMainRenderTarget().height;
		if (w != realcamera$shaderpackW || h != realcamera$shaderpackH) {
			chain.resize(w, h);
			realcamera$shaderpackW = w;
			realcamera$shaderpackH = h;
		}
		realcamera$applyParams(chain);

		// Iris manages its own depth textures separately from vanilla's main depth buffer —
		// feed the DoF pass the pack's real depth directly, bypassing the JSON auxtarget wiring.
		int[] depthSize = ShaderPackCompat.sceneDepthSize();
		List<PostPass> passes = ((PostChainAccessor) chain).realcamera$passes();
		if (passes != null && passes.size() >= 3) {
			EffectInstance dof = passes.get(2).getEffect();
			if (dof != null) {
				dof.setSampler("MainDepthSampler", () -> irisDepthTexId);
			}
		}
		realcamera$process(chain, partialTick);

		boolean atCaptureSize = w == PhotoCapture.overrideWidth() && h == PhotoCapture.overrideHeight();
		// Also wait for Iris's own render targets to have caught up to the capture resolution —
		// otherwise DoF briefly samples a stale-sized depth texture right after a capture resize.
		boolean depthMatches = depthSize != null && depthSize[0] == w && depthSize[1] == h;
		if (atCaptureSize && depthMatches) {
			PhotoCapture.markChainReady();
		}
	}

	private static void realcamera$applyParams(PostChain chain) {
		DofParams.apply(chain, PhotoModeSession.getAperture(),
				PhotoModeSession.getFocusU(), PhotoModeSession.getFocusV());
		ExposureParams.apply(chain, PhotoModeSession.getAperture(), PhotoModeSession.getShutterSeconds(),
				PhotoModeSession.getIso(), PhotoModeSession.getExposureComp(), PhotoModeSession.getWhiteBalance(),
				PhotoModeSession.filterNd());
		FilmParams.apply(chain, PhotoModeSession.getRecipeIndex(), PhotoModeSession.getRecipeStrength(),
				PhotoModeSession.filterPolar(), PhotoModeSession.filterMist());
		AidParams.apply(chain);
	}

	/** Same state vanilla sets up around its own post effect, and main re-bound afterwards. */
	private static void realcamera$process(PostChain chain, float partialTick) {
		RenderSystem.disableBlend();
		RenderSystem.disableDepthTest();
		RenderSystem.resetTextureMatrix();
		chain.process(partialTick);
		Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
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
