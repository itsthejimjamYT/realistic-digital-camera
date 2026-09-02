package com.itsthejimjam.realcamera.client;

import java.nio.ByteBuffer;
import java.util.Map;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.client.mixin.PostChainAccessor;
import com.itsthejimjam.realcamera.client.mixin.PostPassAccessor;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;

import org.lwjgl.system.MemoryUtil;

/**
 * Feeds the finishing pass's {@code AidConfig} block: the zebra (clip) warning and
 * focus-peaking overlays, plus an animation clock for the zebras. Both overlays are
 * forced off during a capture so they never bake into the saved photo.
 */
public final class AidParams {
	private static final String BLOCK = "AidConfig";
	private static final int SIZE = 16;
	private static final ByteBuffer SCRATCH = MemoryUtil.memAlloc(SIZE);

	private static GpuBuffer buffer;

	private AidParams() {
	}

	public static void apply(PostChain chain) {
		if (!ensureBuffer(chain)) {
			return;
		}
		boolean capturing = PhotoCapture.wantsBigFrame();
		float zebras = !capturing && DisplayAids.zebrasOn() ? 1.0f : 0.0f;
		float peaking = !capturing && DisplayAids.peakingOn() ? 1.0f : 0.0f;
		float time = (float) ((System.currentTimeMillis() % 100000L) / 1000.0);

		SCRATCH.clear();
		Std140Builder.intoBuffer(SCRATCH)
				.putFloat(zebras)
				.putFloat(peaking)
				.putFloat(time)
				.putFloat(0.0f);
		SCRATCH.rewind();

		RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), SCRATCH);
	}

	private static boolean ensureBuffer(PostChain chain) {
		try {
			for (PostPass pass : ((PostChainAccessor) chain).realcamera$passes()) {
				Map<String, GpuBuffer> uniforms = ((PostPassAccessor) pass).realcamera$customUniforms();
				GpuBuffer current = uniforms.get(BLOCK);
				if (current == null) {
					continue;
				}
				if (current == buffer) {
					return true;
				}
				if (buffer == null) {
					buffer = RenderSystem.getDevice().createBuffer(
							() -> "realcamera AidConfig",
							GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
							(long) SIZE);
				}
				uniforms.put(BLOCK, buffer);
				current.close();
				return true;
			}
		} catch (Throwable t) {
			PhotoMode.LOGGER.warn("[Photo Mode] Aid uniform setup failed: {}", t.toString());
		}
		return false;
	}
}
