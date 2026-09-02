package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.PhotoCapture;
import com.itsthejimjam.realcamera.client.PhotoModeSession;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In photo mode, left-click is the shutter — it takes a photo instead of swinging / mining.
 */
@Mixin(Minecraft.class)
public class ShutterMixin {

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void realcamera$shutter(CallbackInfoReturnable<Boolean> cir) {
		if (PhotoModeSession.isActive()) {
			if (PhotoModeSession.isFocusPicking()) {
				PhotoModeSession.commitFocus();
			} else {
				PhotoCapture.request();
			}
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void realcamera$noContinuedMining(boolean leftClick, CallbackInfo ci) {
		if (PhotoModeSession.isActive()) {
			ci.cancel();
		}
	}
}
