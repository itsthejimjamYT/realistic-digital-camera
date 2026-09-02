package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.FreeCameraEntity;
import com.itsthejimjam.realcamera.client.PhotoModeSession;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drone photo mode: the local player stays the camera entity (so the character keeps
 * rendering in third person), and here — after vanilla has aligned the camera to the
 * player — the camera's position and rotation are moved onto the free-flying drone.
 * Overriding the {@link Camera} directly, rather than {@code setCameraEntity(drone)},
 * avoids the spectator-style "don't draw the entity you're attached to" skip that was
 * hiding the character.
 */
@Mixin(Camera.class)
public abstract class DroneCameraMixin {

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Shadow
	protected abstract void setPosition(Vec3 pos);

	@Inject(method = "alignWithEntity(F)V", at = @At("TAIL"))
	private void realcamera$droneView(float partialTick, CallbackInfo ci) {
		if (!PhotoModeSession.isDrone()) {
			return;
		}
		FreeCameraEntity drone = PhotoModeSession.getCamera();
		if (drone == null) {
			return;
		}
		this.setRotation(drone.getYRot(), drone.getXRot());
		this.setPosition(drone.getEyePosition(partialTick));
	}
}
