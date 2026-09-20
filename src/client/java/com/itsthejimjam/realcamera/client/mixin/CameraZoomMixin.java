package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.PhotoModeSession;

import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the photo-mode zoom by shrinking the FOV each frame.
 *
 * <p>1.20.4 computes FOV in {@code GameRenderer.getFov(Camera, float, boolean)}, not on
 * {@code Camera} itself (that split is a later refactor) — and it returns {@code double},
 * not {@code float}.
 */
@Mixin(GameRenderer.class)
public class CameraZoomMixin {

	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void realcamera$zoomFov(net.minecraft.client.Camera camera, float partialTicks,
			boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
		if (PhotoModeSession.isActive()) {
			cir.setReturnValue((double) PhotoModeSession.applyZoomToFov(cir.getReturnValue().floatValue()));
		}
	}
}
