package com.itsthejimjam.realcamera.client.mixin;

import java.util.Map;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.client.PhotoModeSession;
import com.mojang.blaze3d.textures.GpuTextureView;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Substitutes a photo-mode texture for a post pass's input at the point the sampler is
 * resolved (never for an output attachment): the shader pack's scene depth for a
 * {@code use_depth_buffer} input, and the stacked long-exposure image for the
 * {@code minecraft:main} colour input.
 */
@Mixin(PostPass.TargetInput.class)
public class PostPassInputMixin {

	@Shadow
	@Final
	private boolean depthBuffer;

	@Shadow
	@Final
	private Identifier targetId;

	@Inject(method = "texture", at = @At("RETURN"), cancellable = true)
	private void realcamera$overrideInput(Map<?, ?> targets, CallbackInfoReturnable<GpuTextureView> cir) {
		if (this.depthBuffer) {
			GpuTextureView depth = PhotoModeSession.depthViewOverride();
			if (depth != null) {
				if (!PhotoModeSession.depthSwapLogged) {
					PhotoModeSession.depthSwapLogged = true;
					PhotoMode.LOGGER.info("[Photo Mode] depth input -> shader-pack texture");
				}
				cir.setReturnValue(depth);
			}
			return;
		}
		GpuTextureView color = PhotoModeSession.colorViewOverride();
		if (color != null && PostChain.MAIN_TARGET_ID.equals(this.targetId)) {
			cir.setReturnValue(color);
		}
	}
}
