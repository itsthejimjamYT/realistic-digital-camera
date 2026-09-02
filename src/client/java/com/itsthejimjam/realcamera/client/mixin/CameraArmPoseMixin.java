package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.PhotoMode;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Any player holding a camera raises it to their face (the spyglass arm pose). Injected
 * at the tail of the player render-state extraction — after AvatarRenderer has computed
 * its own arm pose — so it wins, and because it is render-state driven it shows for
 * other players and in third-party replay recordings, not only for the photographer.
 */
@Mixin(AvatarRenderer.class)
public class CameraArmPoseMixin {

	@Inject(
			method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"))
	private void realcamera$cameraToFace(Avatar entity, AvatarRenderState state, float partialTick,
			CallbackInfo ci) {
		if (entity instanceof Player player && PhotoMode.isCamera(player.getMainHandItem())) {
			// Both arms up to the face — two hands on the camera. The item only renders
			// in the main hand; the other hand cradles it.
			state.rightArmPose = HumanoidModel.ArmPose.SPYGLASS;
			state.leftArmPose = HumanoidModel.ArmPose.SPYGLASS;
		}
	}
}
