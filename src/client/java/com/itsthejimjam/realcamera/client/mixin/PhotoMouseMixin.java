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
 * accumulated delta is read here, before {@code player.turn} runs, and used to steer the
 * drone or the focus reticle directly.
 *
 * <p>Only engaged while a shot is locked, a focus point is being picked, or the drone is
 * flying — ordinary handheld-camera head-look is left entirely to vanilla.
 *
 * <p>1.20.4's {@code turnPlayer()} takes no partial-tick parameter, and {@code Options}
 * only has a combined {@code invertYMouse()} (no separate X-axis invert yet).
 *
 * <p><b>Must zero {@code accumulatedDX}/{@code accumulatedDY} itself.</b> Vanilla's own
 * {@code turnPlayer()} body does that reset near its end (confirmed via {@code javap} —
 * {@code accumulatedDX = 0; accumulatedDY = 0;} runs well after the point this injects),
 * but cancelling the callback at {@code HEAD} skips that body entirely. Without an
 * explicit reset here, raw mouse motion keeps piling into those fields forever (the GLFW
 * move callback adds to them, never drained), so a stray jitter after a few idle seconds
 * gets multiplied by an unbounded accumulated delta — the drone camera whipping around
 * uncontrollably. (Confirmed present in the 26.2 flagship's identical mixin too — not a
 * porting regression, a latent bug worth fixing there as well.)
 */
@Mixin(MouseHandler.class)
public abstract class PhotoMouseMixin {

	@Shadow
	private double accumulatedDX;

	@Shadow
	private double accumulatedDY;

	@Inject(method = "turnPlayer()V", at = @At("HEAD"), cancellable = true)
	private void realcamera$steer(CallbackInfo ci) {
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
			// Aim is frozen for the duration of a multi-frame capture — but mouse motion
			// still needs draining, or it piles up silently and whips the view once the
			// capture ends (see the class doc).
			this.accumulatedDX = 0.0;
			this.accumulatedDY = 0.0;
			return;
		}

		Minecraft mc = Minecraft.getInstance();
		double sens = mc.options.sensitivity().get() * 0.6 + 0.2;
		double scale = sens * sens * sens * 8.0;
		double yaw = this.accumulatedDX * scale;
		double pitch = this.accumulatedDY * scale;
		this.accumulatedDX = 0.0;
		this.accumulatedDY = 0.0;
		if (mc.options.invertYMouse().get()) {
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
