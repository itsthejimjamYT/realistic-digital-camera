package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.Histogram;
import com.itsthejimjam.realcamera.client.PhotoCapture;
import com.itsthejimjam.realcamera.client.PhotoModeSession;

import com.mojang.blaze3d.pipeline.RenderTarget;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Grabs a pending high-res capture in {@code Minecraft.runTick} (1.20.4's per-frame
 * method — later renamed {@code renderFrame}), right after the frame has fully rendered
 * — so {@code minecraft:main} holds the final composited image for every shader pack
 * (some shader packs run their sky/cloud composite this late). The framing overlay isn't
 * drawn during a capture, so grabbing at the tail stays clean.
 *
 * <p>Also does the window-resize + "chain ready" bookkeeping 26.2 does from inside its
 * DoF post-chain injection (see {@code LevelPostMixin}) — that injection point doesn't
 * exist here yet (1.20.4 predates the frame-graph rendering rewrite; see the class doc
 * on the NOT-YET-PORTED {@code LevelPostMixin}/{@code DofParams}), so for now a capture
 * completes without depth-of-field/film grading, but the resize → wait → grab state
 * machine still runs correctly: "chain ready" is reported as soon as the render target
 * reaches the capture size, since there's no real chain to wait on yet.
 */
@Mixin(Minecraft.class)
public class FrameEndCaptureMixin {

	@Inject(method = "runTick(Z)V", at = @At("HEAD"))
	private void realcamera$prepareCaptureFrame(boolean tick, CallbackInfo ci) {
		if (!PhotoModeSession.isActive() || !PhotoCapture.wantsBigFrame()) {
			return;
		}
		PhotoCapture.ensureWindowSized();
		RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
		if (main != null && main.width == PhotoCapture.overrideWidth() && main.height == PhotoCapture.overrideHeight()) {
			PhotoCapture.markChainReady();
		}
	}

	@Inject(method = "runTick(Z)V", at = @At("TAIL"))
	private void realcamera$grabAtFrameEnd(boolean tick, CallbackInfo ci) {
		if (!PhotoModeSession.isActive()) {
			return;
		}
		RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
		if (PhotoCapture.wantsBigFrame()) {
			PhotoCapture.tick(main);
		} else {
			Histogram.maybeSample(main);
		}
	}
}
