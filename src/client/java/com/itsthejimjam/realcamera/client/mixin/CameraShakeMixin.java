package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.PhotoModeSession;

import net.minecraft.client.Camera;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Handheld camera shake — survival {@code camera_body} only. After vanilla has aligned
 * the camera to the player, nudge the yaw/pitch by a slow multi-sine wobble whose
 * amplitude tracks shutter speed × focal length (the "1/focal" reciprocal rule). A
 * tripod nearby kills it; holding sneak (brace) roughly halves it.
 */
@Mixin(Camera.class)
public abstract class CameraShakeMixin {

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Shadow
	public abstract float yRot();

	@Shadow
	public abstract float xRot();

	@Inject(method = "alignWithEntity(F)V", at = @At("TAIL"))
	private void realcamera$shake(float partialTick, CallbackInfo ci) {
		if (!PhotoModeSession.shakeActive()) {
			return;
		}
		PhotoModeSession.tickShake();
		float dy = PhotoModeSession.shakeYawDeg();
		float dp = PhotoModeSession.shakePitchDeg();
		if (dy != 0.0f || dp != 0.0f) {
			this.setRotation(this.yRot() + dy, this.xRot() + dp);
		}
	}
}
