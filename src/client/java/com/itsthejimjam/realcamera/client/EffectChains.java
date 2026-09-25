package com.itsthejimjam.realcamera.client;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.client.mixin.PostChainAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostChain;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * The live photo-mode effect chain (prefilter → DoF gather → exposure / white balance / film
 * grade / grain / aids), run once per frame from {@code LevelPostMixin} — see that class for
 * where in the frame each variant runs and why.
 *
 * <p>Two chains that differ only in where the DoF pass gets depth: {@code realcamera_dof}
 * reads vanilla's {@code minecraft:main:depth}; {@code realcamera_dof_shaderpack} has the
 * shader pack's depth texture handed straight to its sampler.
 */
public final class EffectChains {

	private static final PostChainSlot VANILLA = new PostChainSlot("realcamera_dof");
	private static final PostChainSlot SHADER_PACK = new PostChainSlot("realcamera_dof_shaderpack");
	/** Pass index of the DoF gather (prefilterh, prefilterv, dof, blit). */
	private static final int GATHER_PASS = 2;

	private static boolean vanillaLogged;
	/** One-shot per capture: log the framebuffer vs shader-pack depth sizes. */
	private static boolean captureLogged;

	private EffectChains() {
	}

	/** No shader pack: vanilla depth, still intact at this point of the frame. */
	public static void runVanilla(float partialTick) {
		if (skipThisFrame()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		RenderTarget main = mc.getMainRenderTarget();
		PostChain chain = VANILLA.get(main.width, main.height);
		if (chain == null) {
			return;
		}
		if (!vanillaLogged) {
			vanillaLogged = true;
			PhotoMode.LOGGER.info("[Photo Mode] vanilla-depth effect chain running");
		}
		applyStackedExposure(main);
		applyParams(chain);
		process(chain, partialTick);
		// We're mid-level-render here (the hand pass is next): restore the state it expects.
		RenderSystem.enableDepthTest();
		if (main.width == PhotoCapture.overrideWidth() && main.height == PhotoCapture.overrideHeight()) {
			PhotoCapture.markChainReady();
		}
	}

	/** Shader pack: the pack's depth texture, after the pack has composited. */
	public static void runShaderPack(int depthTextureId, float partialTick) {
		if (skipThisFrame()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		RenderTarget main = mc.getMainRenderTarget();
		int mw = main.width;
		int mh = main.height;
		PostChain chain = SHADER_PACK.get(mw, mh);
		boolean capturing = PhotoCapture.wantsBigFrame();
		if (!capturing) {
			captureLogged = false;
		}
		int[] dsz = ShaderPackCompat.sceneDepthSize();
		boolean depthMatchesFrame = dsz != null && dsz[0] == mw && dsz[1] == mh;
		// getScreenWidth/Height are the REAL window size (WindowSizeMixin only spoofs
		// getWidth/getHeight), so this checks the genuine resize actually landed.
		Window win = mc.getWindow();
		boolean realWindowBig = Math.abs(win.getScreenWidth() - mw) <= 8
				&& Math.abs(win.getScreenHeight() - mh) <= 8;

		if (!PhotoModeSession.shaderPathLogged) {
			PhotoModeSession.shaderPathLogged = true;
			PhotoMode.LOGGER.info("[Photo Mode] shader-pack path: chain={} sceneDepth={}", chain != null, depthTextureId);
		}
		if (capturing && !captureLogged) {
			captureLogged = true;
			PhotoMode.LOGGER.info(
					"[Photo Mode] capture: frame={}x{} target={}x{} realWindow={}x{} sceneDepth={} depthMatch={} windowMatch={}",
					mw, mh, PhotoCapture.overrideWidth(), PhotoCapture.overrideHeight(),
					win.getScreenWidth(), win.getScreenHeight(),
					dsz == null ? "null" : dsz[0] + "x" + dsz[1], depthMatchesFrame, realWindowBig);
		}
		if (chain == null) {
			return;
		}

		applyStackedExposure(main);
		boolean atCaptureSize = mw == PhotoCapture.overrideWidth() && mh == PhotoCapture.overrideHeight();
		if ((PhotoCapture.wantsRawFile() || PhotoCapture.wantsHdrMerge()) && atCaptureSize) {
			// Must run BEFORE the live chain below overwrites minecraft:main with the graded
			// result — the last point in the frame where it's still the ungraded scene. Gated
			// on the final size so the float targets aren't reallocated at every intermediate
			// size while the window ramps up to it.
			HdrCapture.capture(depthTextureId, mw, mh, partialTick);
		}
		EffectInstance dof = ((PostChainAccessor) chain).realcamera$passes().get(GATHER_PASS).getEffect();
		dof.setSampler("MainDepthSampler", () -> depthTextureId);
		applyParams(chain);
		process(chain, partialTick);

		// Only let the capture proceed once the framebuffer, the pack's depth AND the real OS
		// window are all at the target size — otherwise DoF samples a stale depth, or a pack
		// that reads the true window size composites its sky at the wrong scale.
		if (atCaptureSize && depthMatchesFrame && realWindowBig) {
			PhotoCapture.markChainReady();
		}
	}

	/** While a long exposure stacks its raw sub-frames the chain is held off — the stack is
	 *  developed and graded once at the end. Bracket frames DO run it (each is a finished,
	 *  graded frame). */
	private static boolean skipThisFrame() {
		if (PhotoCapture.isLongExposureStacking()) {
			return true;
		}
		if (!PhotoModeSession.afterLevelLogged) {
			PhotoModeSession.afterLevelLogged = true;
			PhotoMode.LOGGER.info("[Photo Mode] effect chain hook is running");
		}
		return false;
	}

	/** Long exposure develop phase: put the stacked image in minecraft:main so this frame's
	 *  chain grades it instead of the live scene (26.2 swaps it in as the chain's input). */
	private static void applyStackedExposure(RenderTarget main) {
		RenderTarget stack = PhotoModeSession.colorViewOverride();
		if (stack == null) {
			return;
		}
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, stack.frameBufferId);
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main.frameBufferId);
		GlStateManager._glBlitFrameBuffer(0, 0, stack.width, stack.height, 0, 0, main.width, main.height,
				GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
	}

	private static void applyParams(PostChain chain) {
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
	private static void process(PostChain chain, float partialTick) {
		RenderSystem.disableBlend();
		RenderSystem.disableDepthTest();
		RenderSystem.resetTextureMatrix();
		chain.process(partialTick);
		Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
	}
}
