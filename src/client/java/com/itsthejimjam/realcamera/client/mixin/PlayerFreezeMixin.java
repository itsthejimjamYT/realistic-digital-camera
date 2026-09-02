package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.FreeCameraEntity;
import com.itsthejimjam.realcamera.client.PhotoCapture;
import com.itsthejimjam.realcamera.client.PhotoModeSession;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Photo mode movement rules for the local player.
 *
 * <p>DRONE mode: the character is pinned in place and mouse-look is redirected to the
 * detached free camera. CAMERA mode: the character walks and looks around normally —
 * this mixin only steps in to lock aim while a photo is being taken, and to feed the
 * mouse to the focus reticle while picking a focus point.
 */
@Mixin(Entity.class)
public class PlayerFreezeMixin {

	@Inject(method = "turn(DD)V", at = @At("HEAD"), cancellable = true)
	private void realcamera$redirectTurn(double yaw, double pitch, CallbackInfo ci) {
		if (!realcamera$isPhotoPlayer()) {
			return;
		}
		if (PhotoCapture.wantsBigFrame()) {
			// Aim is locked while a photo is being taken — an 8K capture spans several
			// frames and mouse motion during it must not change the shot.
			ci.cancel();
			return;
		}
		if (PhotoModeSession.isFocusPicking()) {
			// Mouse drives the focus reticle, not the view.
			PhotoModeSession.moveCursor(yaw, pitch);
			ci.cancel();
			return;
		}
		if (PhotoModeSession.isDrone()) {
			FreeCameraEntity camera = PhotoModeSession.getCamera();
			if (camera != null) {
				camera.turn(yaw, pitch);
			}
			ci.cancel();
		}
		// CAMERA mode: fall through — the player turns their head normally.
	}

	@Inject(method = {"setDeltaMovement(DDD)V", "setPos(DDD)V"}, at = @At("HEAD"), cancellable = true)
	private void realcamera$pinPlayer(CallbackInfo ci) {
		// Only pin the character in DRONE mode (they stand in for the detached camera).
		// In CAMERA mode the character physically walks the scene.
		if (PhotoModeSession.isDrone() && realcamera$isPhotoPlayer()) {
			ci.cancel();
		}
	}

	private boolean realcamera$isPhotoPlayer() {
		return PhotoModeSession.isActive()
				&& (Entity) (Object) this == Minecraft.getInstance().player;
	}
}
