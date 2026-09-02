package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.PhotoCapture;
import com.itsthejimjam.realcamera.client.PhotoModeSession;

import net.minecraft.client.gui.Hud;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the vanilla HUD clutter (hotbar, crosshair, effect icons) while photo mode is
 * active, without using F1-style full-hide — the overlay message layer stays alive so
 * action-bar text still works.
 *
 * <p>During an actual capture the WHOLE HUD extraction is cancelled. Third-party HUDs
 * (Jade / WTHIT "looking at…" tooltips, minimaps, light-level overlays, …) are woven
 * into {@code Hud.extractRenderState} by Fabric's HUD-element system, so they'd
 * otherwise be composited into {@code minecraft:main} and baked into the saved photo.
 * Our own {@link com.itsthejimjam.realcamera.client.PhotoOverlay} already self-suppresses
 * while {@code PhotoCapture.wantsBigFrame()}, so nothing of ours is lost.
 */
@Mixin(Hud.class)
public class HudSuppressMixin {

	@Inject(
			method = {"extractCrosshair", "extractHotbarAndDecorations", "extractEffects"},
			at = @At("HEAD"),
			cancellable = true)
	private void realcamera$suppressVanillaHud(CallbackInfo ci) {
		if (PhotoModeSession.isActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void realcamera$suppressAllHudWhileCapturing(CallbackInfo ci) {
		if (PhotoModeSession.isActive() && PhotoCapture.wantsBigFrame()) {
			ci.cancel();
		}
	}
}
