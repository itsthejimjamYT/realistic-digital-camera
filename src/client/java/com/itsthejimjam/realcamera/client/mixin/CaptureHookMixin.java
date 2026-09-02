package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.client.AidParams;
import com.itsthejimjam.realcamera.client.DofParams;
import com.itsthejimjam.realcamera.client.ExposureParams;
import com.itsthejimjam.realcamera.client.FilmParams;
import com.itsthejimjam.realcamera.client.ShaderPackCompat;
import com.itsthejimjam.realcamera.client.PhotoCapture;
import com.itsthejimjam.realcamera.client.PhotoModeSession;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.textures.GpuTextureView;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the photo-mode effect chain for the shader-pack path, injected at the end of
 * {@code GameRenderer.render} just before the GUI is drawn — so the DoF / grade / grain
 * land on the composited level but never on the HUD. The pending high-res capture is
 * grabbed later still, from {@link FrameEndCaptureMixin} once the whole frame (including
 * a pack's late sky composite) is finished.
 */
@Mixin(GameRenderer.class)
public class CaptureHookMixin {

	@Shadow
	@Final
	private CrossFrameResourcePool resourcePool;

	/** One-shot per capture: log the framebuffer vs shader-pack depth sizes. */
	private static boolean realcamera$captureLogged;

	/** Before the frame renders, make the real OS window the capture size (once per shot). */
	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
	private void realcamera$sizeWindow(CallbackInfo ci) {
		if (PhotoModeSession.isActive() && PhotoCapture.wantsBigFrame()) {
			PhotoCapture.ensureWindowSized();
		}
	}

	@Inject(
			method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
	private void realcamera$afterLevel(CallbackInfo ci) {
		if (!PhotoModeSession.isActive() || PhotoCapture.isLongExposureStacking()) {
			// While a long exposure is stacking sub-frames we don't run the effect
			// chain — the stacked image is developed and graded once at the end.
			// Bracket frames DO need the chain (each is a finished graded frame).
			return;
		}
		if (!PhotoModeSession.afterLevelLogged) {
			PhotoModeSession.afterLevelLogged = true;
			PhotoMode.LOGGER.info("[Photo Mode] CaptureHookMixin.afterLevel is running");
		}
		Minecraft mc = Minecraft.getInstance();
		boolean capturing = PhotoCapture.wantsBigFrame();
		if (!capturing) {
			realcamera$captureLogged = false;
		}

		if (ShaderPackCompat.shaderPackActive()) {
			PostChain chain;
			try {
				chain = mc.getShaderManager()
						.getPostChain(PhotoMode.id("dof_shaderpack"), LevelTargetBundle.MAIN_TARGETS);
			} catch (Throwable t) {
				// A bad shader must not crash the game to the menu — just skip the effect.
				if (!PhotoModeSession.shaderPathLogged) {
					PhotoModeSession.shaderPathLogged = true;
					PhotoMode.LOGGER.error("[Photo Mode] dof_shaderpack post chain failed to load", t);
				}
				return;
			}
			GpuTextureView sceneDepth = ShaderPackCompat.sceneDepthView();
			int mw = mc.gameRenderer.mainRenderTarget().width;
			int mh = mc.gameRenderer.mainRenderTarget().height;
			int[] dsz = ShaderPackCompat.sceneDepthSize();
			boolean depthMatchesFrame = dsz != null && dsz[0] == mw && dsz[1] == mh;
			// getScreenWidth/Height are the REAL window size (WindowSizeMixin only spoofs
			// getWidth/getHeight), so this checks the genuine resize actually landed.
			Window win = mc.getWindow();
			boolean realWindowBig = Math.abs(win.getScreenWidth() - mw) <= 8
					&& Math.abs(win.getScreenHeight() - mh) <= 8;

			if (!PhotoModeSession.shaderPathLogged) {
				PhotoModeSession.shaderPathLogged = true;
				PhotoMode.LOGGER.info("[Photo Mode] shader-pack path: chain={} sceneDepth={}",
						chain != null, sceneDepth != null);
			}
			if (capturing && !realcamera$captureLogged) {
				realcamera$captureLogged = true;
				PhotoMode.LOGGER.info(
						"[Photo Mode] capture: frame={}x{} target={}x{} realWindow={}x{} sceneDepth={} depthMatch={} windowMatch={}",
						mw, mh, PhotoCapture.overrideWidth(), PhotoCapture.overrideHeight(),
						win.getScreenWidth(), win.getScreenHeight(),
						dsz == null ? "null" : dsz[0] + "x" + dsz[1], depthMatchesFrame, realWindowBig);
			}

			if (chain != null && sceneDepth != null) {
				DofParams.apply(chain, PhotoModeSession.getAperture(),
						PhotoModeSession.getFocusU(), PhotoModeSession.getFocusV());
				ExposureParams.apply(chain, PhotoModeSession.getAperture(), PhotoModeSession.getShutterSeconds(),
						PhotoModeSession.getIso(), PhotoModeSession.getExposureComp(), PhotoModeSession.getWhiteBalance(),
						PhotoModeSession.filterNd());
				FilmParams.apply(chain, PhotoModeSession.getRecipeIndex(), PhotoModeSession.getRecipeStrength(),
						PhotoModeSession.filterPolar(), PhotoModeSession.filterMist());
				AidParams.apply(chain);
				PhotoModeSession.setDepthViewOverride(sceneDepth);
				try {
					chain.process(mc.gameRenderer.mainRenderTarget(), this.resourcePool);
				} finally {
					PhotoModeSession.setDepthViewOverride(null);
				}
				// Only let the capture proceed once the framebuffer, the shader pack's depth, AND the
				// real OS window are all at the target size — otherwise DoF samples a
				// stale depth, or a pack that reads the true window size
				// composites its sky at the wrong scale and it drops out of the grab.
				if (mw == PhotoCapture.overrideWidth() && mh == PhotoCapture.overrideHeight()
						&& depthMatchesFrame && realWindowBig) {
					PhotoCapture.markChainReady();
				}
			}
		}
	}
}
