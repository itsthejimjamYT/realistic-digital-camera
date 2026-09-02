package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.PhotoCapture;

import com.mojang.blaze3d.platform.Window;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * During a capture frame, report the chosen output resolution as the window size.
 * The game then resizes its main framebuffer and sets up the projection to match,
 * so the frozen scene is rendered at full resolution regardless of the real window.
 */
@Mixin(Window.class)
public class WindowSizeMixin {

	@Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
	private void realcamera$captureWidth(CallbackInfoReturnable<Integer> cir) {
		if (PhotoCapture.wantsBigFrame()) {
			cir.setReturnValue(PhotoCapture.overrideWidth());
		}
	}

	@Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
	private void realcamera$captureHeight(CallbackInfoReturnable<Integer> cir) {
		if (PhotoCapture.wantsBigFrame()) {
			cir.setReturnValue(PhotoCapture.overrideHeight());
		}
	}
}
