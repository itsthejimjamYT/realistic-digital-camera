package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.PhotoModeSession;

import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the first-person hand / held item out of the frame while photo mode is active.
 */
@Mixin(GameRenderer.class)
public class HandRenderMixin {

	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void realcamera$hideHand(CallbackInfo ci) {
		if (PhotoModeSession.isActive()) {
			ci.cancel();
		}
	}
}
