package com.itsthejimjam.realcamera.client;

import com.itsthejimjam.realcamera.PhotoMode;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * One of this mod's JSON post chains ({@code assets/minecraft/shaders/post/<name>.json}),
 * built on first use against {@code minecraft:main} and resized only when the framebuffer
 * actually changes size — {@code PostChain.resize} reallocates every intermediate target,
 * far too expensive to do every frame at a big capture resolution. 1.21.1 has no
 * {@code ShaderManager} chain cache like 26.2, so this plays that role.
 *
 * <p>The chain lives in the {@code minecraft} namespace, not our own: {@code EffectInstance}
 * builds {@code "shaders/program/" + name + ".json"} as a string before parsing it, so a
 * namespaced pass name comes out unparseable. Our programs are prefixed {@code realcamera_}
 * instead.
 *
 * <p>{@code floatTargets} names intermediate targets that must hold values outside 0..1
 * (the RAW Mode path — see {@link HdrCapture}). {@code PostChain} targets are always RGBA8,
 * so after each (re)allocation those get their colour texture re-specified as RGBA16F in
 * place; the framebuffer keeps the same texture object attached, so passes are unaffected.
 */
final class PostChainSlot {

	private final String name;
	private final String[] floatTargets;
	private PostChain chain;
	private boolean failed;
	private int width = -1;
	private int height = -1;

	PostChainSlot(String name, String... floatTargets) {
		this.name = name;
		this.floatTargets = floatTargets;
	}

	/** The chain sized to {@code width x height}, or {@code null} if it failed to load
	 *  (logged once; a broken shader must not take the game down with it). */
	PostChain get(int width, int height) {
		if (chain == null) {
			if (failed) {
				return null;
			}
			Minecraft mc = Minecraft.getInstance();
			try {
				chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), mc.getMainRenderTarget(),
						ResourceLocation.withDefaultNamespace("shaders/post/" + name + ".json"));
			} catch (Throwable t) {
				failed = true;
				PhotoMode.LOGGER.error("[Photo Mode] {} post chain failed to load", name, t);
				return null;
			}
			this.width = -1;
			this.height = -1;
		}
		if (width != this.width || height != this.height) {
			chain.resize(width, height);
			this.width = width;
			this.height = height;
			for (String target : floatTargets) {
				makeFloat(chain.getTempTarget(target));
			}
		}
		return chain;
	}

	/** The named intermediate target, or {@code null} if the chain isn't built. */
	RenderTarget target(String target) {
		return chain == null ? null : chain.getTempTarget(target);
	}

	/** Free the chain's GPU memory; the next {@link #get} rebuilds it. */
	void release() {
		if (chain != null) {
			chain.close();
			chain = null;
		}
		width = -1;
		height = -1;
	}

	private static void makeFloat(RenderTarget target) {
		if (target == null) {
			return;
		}
		GlStateManager._bindTexture(target.getColorTextureId());
		GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, target.width, target.height, 0,
				GL11.GL_RGBA, GL11.GL_FLOAT, null);
		GlStateManager._bindTexture(0);
	}
}
