package com.itsthejimjam.realcamera.client.mixin;

import com.itsthejimjam.realcamera.client.EffectChains;
import com.itsthejimjam.realcamera.client.PhotoModeSession;
import com.itsthejimjam.realcamera.client.ShaderPackCompat;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs photo mode's DoF / exposure / film-grade effect chain (see {@link EffectChains}) at
 * the right point of the frame for whichever depth source is live — the 1.21.1 analogue of
 * 26.2's {@code LevelPostMixin} + {@code CaptureHookMixin} pair.
 *
 * <p><b>No shader pack:</b> right after {@code LevelRenderer.renderLevel} returns, inside
 * {@code GameRenderer.renderLevel} — before it clears the depth buffer to draw the
 * first-person hand ({@code RenderSystem.clear(GL_DEPTH_BUFFER_BIT)} under the "hand"
 * profiler section). That is the last moment {@code minecraft:main:depth} still holds the
 * scene; the 1.20.4 port ran its chain later, from {@code doEntityOutline}, and so only ever
 * saw the cleared depth — which is why its depth of field needed a shader pack. Same seam
 * 26.2 uses ("before the always-on-top pass clears the depth buffer").
 *
 * <p><b>Shader pack (Iris):</b> after {@code doEntityOutline}, once the pack has
 * composited — anything written earlier would be overwritten by the pack's own final pass.
 * Depth comes from the pack's depth texture instead (see {@link ShaderPackCompat}).
 *
 * <p>Exactly one of the two runs per frame: the pick is whether the pack hands back a usable
 * depth texture this frame.
 */
@Mixin(GameRenderer.class)
public class LevelPostMixin {

	@Inject(
			method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(
					value = "INVOKE_STRING",
					target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
					args = "ldc=hand"))
	private void realcamera$vanillaDepthChain(DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!PhotoModeSession.isActive() || ShaderPackCompat.sceneDepthTextureId() >= 0) {
			return;
		}
		EffectChains.runVanilla(deltaTracker.getGameTimeDeltaTicks());
	}

	@Inject(
			method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/LevelRenderer;doEntityOutline()V",
					shift = At.Shift.AFTER))
	private void realcamera$shaderPackChain(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		if (!PhotoModeSession.isActive()) {
			return;
		}
		int depthTex = ShaderPackCompat.sceneDepthTextureId();
		if (depthTex < 0) {
			return;
		}
		EffectChains.runShaderPack(depthTex, deltaTracker.getGameTimeDeltaTicks());
	}
}
