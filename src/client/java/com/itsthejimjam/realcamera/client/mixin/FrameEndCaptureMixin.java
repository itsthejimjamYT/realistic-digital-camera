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
 * Brackets each frame for the shutter, in {@code Minecraft.runTick} (1.21.1's per-frame
 * method — later renamed {@code renderFrame}):
 *
 * <ul>
 * <li>HEAD: make the real OS window the capture size (once per shot) before the frame
 * renders — 26.2 does this from {@code GameRenderer.render}'s head.</li>
 * <li>TAIL: grab a pending high-res capture once the frame has fully rendered, so
 * {@code minecraft:main} holds the final composited image for every shader pack (some run
 * their sky/cloud composite this late). The framing overlay isn't drawn during a capture,
 * so grabbing at the tail stays clean.</li>
 * </ul>
 *
 * <p>"Chain ready" is reported by the effect chain itself ({@code EffectChains}), once it
 * has actually run at the capture size — not here.
 */
@Mixin(Minecraft.class)
public class FrameEndCaptureMixin {

	@Inject(method = "runTick(Z)V", at = @At("HEAD"))
	private void realcamera$sizeWindow(boolean tick, CallbackInfo ci) {
		if (PhotoModeSession.isActive() && PhotoCapture.wantsBigFrame()) {
			PhotoCapture.ensureWindowSized();
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
