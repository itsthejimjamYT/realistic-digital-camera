package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.PhotoModeSession;

import net.minecraft.client.Camera;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the photo-mode zoom by shrinking the FOV the camera computes each frame.
 * {@code calculateFov} feeds both the projection matrix and {@code Camera.getFov()},
 * so hooking its return keeps everything consistent.
 */
@Mixin(Camera.class)
public class CameraZoomMixin {

	@Inject(method = "calculateFov(F)F", at = @At("RETURN"), cancellable = true)
	private void realcamera$zoomFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		if (PhotoModeSession.isActive()) {
			cir.setReturnValue(PhotoModeSession.applyZoomToFov(cir.getReturnValueF()));
		}
	}
}
