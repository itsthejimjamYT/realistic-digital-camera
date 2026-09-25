package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.PhotoMode;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Any player holding a camera raises it to their face (the spyglass arm pose). Injected at
 * the tail of {@code PlayerRenderer.setModelProperties} — after vanilla has picked its own
 * arm poses — so it wins, and it shows for every player you see, not only the photographer.
 * (26.2 does the same at the tail of its player render-state extraction.)
 */
@Mixin(PlayerRenderer.class)
public class CameraArmPoseMixin {

	@Inject(method = "setModelProperties", at = @At("TAIL"))
	private void realcamera$cameraToFace(AbstractClientPlayer player, CallbackInfo ci) {
		if (PhotoMode.isCamera(player.getMainHandItem())) {
			// Both arms up to the face — two hands on the camera. The item only renders in
			// the main hand; the other hand cradles it.
			PlayerModel<AbstractClientPlayer> model = ((PlayerRenderer) (Object) this).getModel();
			model.rightArmPose = HumanoidModel.ArmPose.SPYGLASS;
			model.leftArmPose = HumanoidModel.ArmPose.SPYGLASS;
		}
	}
}
