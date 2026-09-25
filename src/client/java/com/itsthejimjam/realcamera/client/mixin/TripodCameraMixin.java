package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.PhotoModeSession;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
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
 *
 * <p>See {@link DroneCameraMixin} for why 1.21.1 targets {@code setup(...)} rather than
 * {@code alignWithEntity(float)}.
 */
@Mixin(Camera.class)
public abstract class TripodCameraMixin {

	@Shadow
	protected abstract void setPosition(Vec3 pos);

	@Inject(method = "setup", at = @At("TAIL"))
	private void realcamera$tripodAnchor(BlockGetter level, Entity entity, boolean detached,
			boolean thirdPersonMirror, float partialTick, CallbackInfo ci) {
		Vec3 anchor = PhotoModeSession.tripodAnchor();
		if (anchor != null) {
			this.setPosition(anchor);
		}
	}
}
