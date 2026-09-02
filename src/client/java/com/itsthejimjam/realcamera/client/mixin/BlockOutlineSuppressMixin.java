package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.PhotoModeSession;

import net.minecraft.client.renderer.LevelRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The black block-targeting outline is a game HUD cue, not part of the shot — keep it
 * out of the frame while photo mode is active.
 */
@Mixin(LevelRenderer.class)
public class BlockOutlineSuppressMixin {

	@Inject(method = "submitBlockOutline", at = @At("HEAD"), cancellable = true)
	private void realcamera$hideBlockOutline(CallbackInfo ci) {
		if (PhotoModeSession.isActive()) {
			ci.cancel();
		}
	}
}
