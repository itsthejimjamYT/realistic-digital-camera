package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.client.AidParams;
import com.itsthejimjam.realcamera.client.DofParams;
import com.itsthejimjam.realcamera.client.ExposureParams;
import com.itsthejimjam.realcamera.client.FilmParams;
import com.itsthejimjam.realcamera.client.ShaderPackCompat;
import com.itsthejimjam.realcamera.client.PhotoCapture;
import com.itsthejimjam.realcamera.client.PhotoModeSession;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds photo mode's effect chain into the level render graph, right after the
 * transparency pass and before the "always on top" pass clears the depth buffer —
 * the only point where a post pass can still sample real scene depth. This is the
 * same seam the vanilla transparency and entity-outline chains use, so it also
 * composes correctly when a shader pack is driving the pipeline.
 */
@Mixin(LevelRenderer.class)
public class LevelPostMixin {

	@Shadow
	@Final
	private LevelTargetBundle targets;

	@Inject(
			method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/LevelRenderer;addAlwaysOnTopPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"))
	private void realcamera$addEffectChain(CallbackInfo ci, @Local FrameGraphBuilder frame) {
		// With a shader pack active, the pack re-composites after this point and discards
		// writes here — that case is handled by CaptureHookMixin instead.
		if (!PhotoModeSession.isActive() || ShaderPackCompat.shaderPackActive()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		PostChain chain;
		try {
			chain = mc.getShaderManager()
					.getPostChain(PhotoMode.id("dof"), LevelTargetBundle.MAIN_TARGETS);
		} catch (Throwable t) {
			PhotoMode.LOGGER.error("[Photo Mode] dof post chain failed to load", t);
			return;
		}
		if (chain != null) {
			DofParams.apply(chain, PhotoModeSession.getAperture(),
					PhotoModeSession.getFocusU(), PhotoModeSession.getFocusV());
			ExposureParams.apply(chain, PhotoModeSession.getAperture(), PhotoModeSession.getShutterSeconds(),
					PhotoModeSession.getIso(), PhotoModeSession.getExposureComp(), PhotoModeSession.getWhiteBalance(),
					PhotoModeSession.filterNd());
			FilmParams.apply(chain, PhotoModeSession.getRecipeIndex(), PhotoModeSession.getRecipeStrength(),
					PhotoModeSession.filterPolar(), PhotoModeSession.filterMist());
			AidParams.apply(chain);
			int w = mc.gameRenderer.mainRenderTarget().width;
			int h = mc.gameRenderer.mainRenderTarget().height;
			chain.addToFrame(frame, w, h, this.targets);
			if (w == PhotoCapture.overrideWidth() && h == PhotoCapture.overrideHeight()) {
				PhotoCapture.markChainReady();
			}
		}
	}
}
