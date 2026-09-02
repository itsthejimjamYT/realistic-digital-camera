package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.Histogram;
import com.itsthejimjam.realcamera.client.PhotoCapture;
import com.itsthejimjam.realcamera.client.PhotoModeSession;

import com.mojang.blaze3d.pipeline.RenderTarget;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Grabs a pending high-res capture in {@code Minecraft.renderFrame}, right after
 * {@code GameRenderer.render} returns and before the frame is blitted to the window —
 * so {@code minecraft:main} holds the final composited image for every shader pack
 * (some shader packs run their sky/cloud composite this late). The framing overlay isn't drawn
 * during a capture, so grabbing after the GUI pass stays clean.
 */
@Mixin(Minecraft.class)
public class FrameEndCaptureMixin {

	@Inject(method = "renderFrame(Z)V", at = @At("TAIL"))
	private void realcamera$grabAtFrameEnd(boolean advanceGameTime, CallbackInfo ci) {
		if (!PhotoModeSession.isActive()) {
			return;
		}
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if (PhotoCapture.wantsBigFrame()) {
			PhotoCapture.tick(main);
		} else {
			Histogram.maybeSample(main);
		}
	}
}
