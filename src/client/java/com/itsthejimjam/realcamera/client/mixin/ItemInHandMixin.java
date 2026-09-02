package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.PhotoModeSession;

import net.minecraft.client.renderer.ItemInHandRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses first-person hand / held-item rendering in photo mode. This targets the
 * shared entry point, so it also stops the shader pack drawing the hand through its own pipeline
 * (which {@code HandRenderMixin} on {@code GameRenderer} can't reach).
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandMixin {

	@Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void realcamera$hideHand(CallbackInfo ci) {
		if (PhotoModeSession.isActive()) {
			ci.cancel();
		}
	}
}
