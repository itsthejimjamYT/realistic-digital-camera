package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.PhotoModeSession;

import net.minecraft.client.Camera;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drone photo mode keeps a first-person camera type so third-person camera mods (Shoulder
 * Surfing and friends) stay dormant, but the local player model still has to render from
 * the drone's viewpoint. {@code LevelExtractor} only draws the camera entity when the
 * camera reports itself detached, so force that while the drone is active. The camera's
 * own pull-back logic reads the private field directly, not this getter, so nothing else
 * changes.
 */
@Mixin(Camera.class)
public class CameraDetachedMixin {

	@Inject(method = "isDetached()Z", at = @At("HEAD"), cancellable = true)
	private void realcamera$droneDetached(CallbackInfoReturnable<Boolean> cir) {
		if (PhotoModeSession.isDrone()) {
			cir.setReturnValue(true);
		}
	}
}
