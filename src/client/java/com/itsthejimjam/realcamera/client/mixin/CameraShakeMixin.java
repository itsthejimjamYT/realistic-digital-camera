package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.PhotoModeSession;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;

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
 *
 * <p>See {@link DroneCameraMixin} for why 1.20.4 targets {@code setup(...)} rather than
 * {@code alignWithEntity(float)}; and {@code getYRot()}/{@code getXRot()} rather than the
 * shortened {@code yRot()}/{@code xRot()} accessor names.
 */
@Mixin(Camera.class)
public abstract class CameraShakeMixin {

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Shadow
	public abstract float getYRot();

	@Shadow
	public abstract float getXRot();

	@Inject(method = "setup", at = @At("TAIL"))
	private void realcamera$shake(BlockGetter level, Entity entity, boolean detached,
			boolean thirdPersonMirror, float partialTick, CallbackInfo ci) {
		if (!PhotoModeSession.shakeActive()) {
			return;
		}
		PhotoModeSession.tickShake();
		float dy = PhotoModeSession.shakeYawDeg();
		float dp = PhotoModeSession.shakePitchDeg();
		if (dy != 0.0f || dp != 0.0f) {
			this.setRotation(this.getYRot() + dy, this.getXRot() + dp);
		}
	}
}
