package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.PhotoModeSession;
import com.itsthejimjam.realcamera.client.PhotoPanelScreen;

import net.minecraft.client.MouseHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While photo mode is active the scroll wheel controls zoom instead of the hotbar.
 * With the settings panel open it instead steps the setting under the pointer, and
 * zoom is locked so it can't drift while you dial values in.
 */
@Mixin(MouseHandler.class)
public class MouseScrollMixin {

	@Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
	private void realcamera$scrollToZoom(long handle, double xOffset, double yOffset, CallbackInfo ci) {
		if (!PhotoModeSession.isActive()) {
			return;
		}
		if (PhotoPanelScreen.isOpen()) {
			PhotoPanelScreen.scrollHovered(yOffset);
		} else {
			PhotoModeSession.adjustZoom(yOffset);
		}
		ci.cancel();
	}
}
