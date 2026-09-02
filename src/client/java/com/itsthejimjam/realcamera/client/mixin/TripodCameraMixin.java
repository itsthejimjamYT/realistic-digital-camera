package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.PhotoModeSession;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When photo mode was entered from a placed tripod block, pin the camera to the stand's
 * head. Rotation stays player-driven (turn in place to aim); movement is already locked
 * by {@code PhotoModeSession}.
 */
@Mixin(Camera.class)
public abstract class TripodCameraMixin {

	@Shadow
	protected abstract void setPosition(Vec3 pos);

	@Inject(method = "alignWithEntity(F)V", at = @At("TAIL"))
	private void realcamera$tripodAnchor(float partialTick, CallbackInfo ci) {
		Vec3 anchor = PhotoModeSession.tripodAnchor();
		if (anchor != null) {
			this.setPosition(anchor);
		}
	}
}
