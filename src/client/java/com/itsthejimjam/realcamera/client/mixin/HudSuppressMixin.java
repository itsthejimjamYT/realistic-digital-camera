package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.PhotoCapture;
import com.itsthejimjam.realcamera.client.PhotoModeSession;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the vanilla HUD clutter (hotbar, crosshair, effect icons) while photo mode is
 * active, without using F1-style full-hide — action-bar text still works.
 *
 * <p>During an actual capture the WHOLE HUD render is cancelled. Third-party HUDs
 * (Jade / WTHIT "looking at…" tooltips, minimaps, light-level overlays, …) are woven
 * into {@code Gui.render} by Fabric's HUD render callback, so they'd otherwise be
 * composited into {@code minecraft:main} and baked into the saved photo. Our own
 * {@link com.itsthejimjam.realcamera.client.PhotoOverlay} already self-suppresses while
 * {@code PhotoCapture.wantsBigFrame()}, so nothing of ours is lost.
 *
 * <p>1.20.4 predates the render-state-extraction HUD rewrite (no {@code Hud.extractXxx}
 * methods) — {@code renderCrosshair}/{@code renderHotbar}/{@code renderEffects} have
 * different signatures from each other here, so each gets its own injection instead of
 * one shared handler.
 */
@Mixin(Gui.class)
public class HudSuppressMixin {

	@Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
	private void realcamera$suppressCrosshair(GuiGraphics graphics, CallbackInfo ci) {
		if (PhotoModeSession.isActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
	private void realcamera$suppressHotbar(float partialTick, GuiGraphics graphics, CallbackInfo ci) {
		if (PhotoModeSession.isActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
	private void realcamera$suppressEffects(GuiGraphics graphics, CallbackInfo ci) {
		if (PhotoModeSession.isActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void realcamera$suppressAllHudWhileCapturing(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
		if (PhotoModeSession.isActive() && PhotoCapture.wantsBigFrame()) {
			ci.cancel();
		}
	}
}
