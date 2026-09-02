package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.FreeCameraEntity;
import com.itsthejimjam.realcamera.client.PhotoCapture;
import com.itsthejimjam.realcamera.client.PhotoModeSession;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Photo mode's mouse-look, taken over at the source.
 *
 * <p>Vanilla routes mouse motion through {@code MouseHandler.turnPlayer} then
 * {@code player.turn(..)}. Some third-party camera / animation mods override or swallow
 * {@code Entity#turn}, so hooking that is unreliable in a modpack. Instead the
 * accumulated delta is read here, before
 * {@code player.turn} runs, and used to steer the drone or the focus reticle directly.
 * The caller zeroes the accumulators right after this returns, so a plain read suffices.
 *
 * <p>Only engaged while a shot is locked, a focus point is being picked, or the drone is
 * flying — ordinary handheld-camera head-look is left entirely to vanilla.
 */
@Mixin(MouseHandler.class)
public abstract class PhotoMouseMixin {

	@Shadow
	private double accumulatedDX;

	@Shadow
	private double accumulatedDY;

	@Inject(method = "turnPlayer(D)V", at = @At("HEAD"), cancellable = true)
	private void realcamera$steer(double partialTick, CallbackInfo ci) {
		if (!PhotoModeSession.isActive()) {
			return;
		}
		boolean capture = PhotoCapture.wantsBigFrame();
		boolean focus = PhotoModeSession.isFocusPicking();
		boolean drone = PhotoModeSession.isDrone();
		if (!capture && !focus && !drone) {
			return; // handheld idle look — let vanilla turn the head
		}
		ci.cancel();
		if (capture) {
			return; // aim is frozen for the duration of a multi-frame capture
		}

		Minecraft mc = Minecraft.getInstance();
		double sens = mc.options.sensitivity().get() * 0.6 + 0.2;
		double scale = sens * sens * sens * 8.0;
		double yaw = this.accumulatedDX * scale;
		double pitch = this.accumulatedDY * scale;
		if (mc.options.invertMouseX().get()) {
			yaw = -yaw;
		}
		if (mc.options.invertMouseY().get()) {
			pitch = -pitch;
		}

		if (focus) {
			PhotoModeSession.moveCursor(yaw, pitch);
			return;
		}
		FreeCameraEntity cam = PhotoModeSession.getCamera();
		if (cam != null) {
			cam.turn(yaw, pitch);
		}
	}
}
