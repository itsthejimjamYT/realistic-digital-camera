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
 * Adds photo mode's effect chain into the level render graph, right before the frame
 * graph actually executes — the same seam vanilla uses to add its own entity-outline
 * post chain (confirmed by tracing {@code LevelRenderer.render()}'s bytecode: vanilla
 * calls {@code chain.addToFrame(frame, ...)} immediately before
 * {@code frame.execute(...)}, after every other pass — including transparency — has
 * already been registered into the graph, so depth is still real and available here).
 *
 * <p>26.3 note: the OLD seam this mixin used —
 * {@code LevelRenderer.addAlwaysOnTopPass(FrameGraphBuilder, ...)}, injected into right
 * before that call — no longer exists as a standalone step; that logic moved inside a
 * lambda nested in {@code addMainPass}, which isn't a stable thing to target directly.
 * Anchoring on {@code FrameGraphBuilder.execute(...)} instead sidesteps that entirely:
 * it's the one point every version of this render graph necessarily has, right before
 * the graph runs, so any pass registered here executes with the same depth visibility
 * vanilla's own post-chain passes get.
 */
@Mixin(LevelRenderer.class)
public class LevelPostMixin {

	@Shadow
	@Final
	private LevelTargetBundle targets;

	@Inject(
			method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
			at = @At(
					value = "INVOKE",
					target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"))
	private void realcamera$addEffectChain(CallbackInfo ci, @Local FrameGraphBuilder frame) {
		// With a shader pack active, the pack re-composites after this point and discards
		// writes here — that case is handled by CaptureHookMixin instead.
		// While a long exposure stacks its raw sub-frames the chain is held off (as on the
		// shader-pack path in CaptureHookMixin) — the stack is developed and graded once at
		// the end. Grading each sub-frame here too graded the finished shot twice.
		if (!PhotoModeSession.isActive() || ShaderPackCompat.shaderPackActive()
				|| PhotoCapture.isLongExposureStacking()) {
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
			try {
				chain.addToFrame(frame, w, h, this.targets);
			} catch (Throwable t) {
				// This only registers the pass into the graph — if a pipeline inside it is
				// still broken/uncompiled, the actual failure surfaces later during vanilla's
				// own frame.execute(), outside anything this mixin can catch. Catching here
				// still covers a synchronous failure in addToFrame itself.
				PhotoMode.LOGGER.error("[Photo Mode] dof post chain failed to register", t);
				return;
			}
			if (w == PhotoCapture.overrideWidth() && h == PhotoCapture.overrideHeight()) {
				PhotoCapture.markChainReady();
			}
		}
	}
}
