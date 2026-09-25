package com.itsthejimjam.realcamera.client;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.client.mixin.PostChainAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.Util;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * The high-precision RAW capture path behind RAW Mode and the bracket HDR merge —
 * the 1.21.1 port of 26.2's class of the same name. Applies just the two camera-accurate
 * steps (exposure, white balance) to the pristine frame and writes into a genuinely
 * higher-precision target, skipping every destructive step the normal photo's grade applies
 * (contrast crush, film recipe, highlight clamp) — so whatever headroom/shadow detail the
 * scene had survives instead of collapsing into black or white.
 *
 * <p>26.2 builds this from hand-made Blaze3D render pipelines. 1.21.1 has none of that, so it's
 * a fourth JSON post chain instead ({@code realcamera_hdr}): the live preview's own prefilter
 * + DoF gather programs (same shaders, same tuning — driven by {@link DofParams}, so the RAW
 * file keeps the photo's blur), then {@code realcamera_expose}. Its {@code gathered} and
 * {@code hdr} targets are re-specified as RGBA16F by {@link PostChainSlot}, which the JSON
 * system alone can't express. It's a separate chain from the live one on purpose: nothing
 * here touches the live preview or any frame outside an active capture.
 *
 * <p>{@link #capture} reads {@code minecraft:main} before the live grade chain overwrites it
 * with the graded result — for a long exposure's develop phase that's the CPU-stacked image,
 * already copied over main by {@link EffectChains} for the live chain, so this and the
 * normal photo always look at the same data.
 *
 * <p>Only driven from the shader-pack path, exactly like 26.2 (see
 * {@code PhotoCapture.wantsRawFile}): its DoF gather reads the pack's depth texture.
 */
public final class HdrCapture {

	private static final PostChainSlot SLOT = new PostChainSlot("realcamera_hdr", "gathered", "hdr");
	/** Pass index of the DoF gather in realcamera_hdr.json (prefilterh, prefilterv, dof, expose). */
	private static final int GATHER_PASS = 2;

	/** One-shot logs: did the pass itself finish, and did it ever fail? */
	private static boolean drawLogged;
	private static boolean failLogged;

	private HdrCapture() {
	}

	/**
	 * Run the RAW chain against {@code minecraft:main} (still pristine — call it before the
	 * live grade chain), writing into the RGBA16F {@code hdr} target. Cheap enough to call every
	 * frame while a capture is in flight, so it's always current by the time a caller decides to
	 * read it back.
	 *
	 * @param depthTextureId the shader pack's scene-depth GL texture, for the DoF gather
	 */
	public static void capture(int depthTextureId, int width, int height, float partialTick) {
		try {
			PostChain chain = SLOT.get(width, height);
			if (chain == null) {
				return;
			}
			List<PostPass> passes = ((PostChainAccessor) chain).realcamera$passes();
			EffectInstance gather = passes.get(GATHER_PASS).getEffect();
			gather.setSampler("MainDepthSampler", () -> depthTextureId);
			// Same helpers as the live chain: DofParams drives passes 0-2, ExposureParams finds
			// ExposureMult / WhiteBalance on pass 3 (the expose program has no grain/grade).
			DofParams.apply(chain, PhotoModeSession.getAperture(),
					PhotoModeSession.getFocusU(), PhotoModeSession.getFocusV());
			ExposureParams.apply(chain, PhotoModeSession.getAperture(), PhotoModeSession.getShutterSeconds(),
					PhotoModeSession.getIso(), PhotoModeSession.getExposureComp(), PhotoModeSession.getWhiteBalance(),
					PhotoModeSession.filterNd());
			chain.process(partialTick);
			if (!drawLogged) {
				drawLogged = true;
				PhotoMode.LOGGER.info("[Photo Mode] HdrCapture: RAW pass completed");
			}
		} catch (Throwable t) {
			// The normal capture must never fail because this extra pass did — log once and
			// let the readback below just skip the RAW file for this shot.
			if (!failLogged) {
				failLogged = true;
				PhotoMode.LOGGER.error("[Photo Mode] HdrCapture pass failed", t);
			}
		}
	}

	/** Free the chain's GPU memory (four full-size targets, two of them 16-bit float) once a
	 *  capture is done with it, so a large capture's VRAM doesn't stay reserved for the rest of
	 *  the session; the next capture that wants it rebuilds it. */
	public static void release() {
		SLOT.release();
	}

	/** A raw readback result: tightly packed RGBA16F, bottom-up rows, little-endian — the
	 *  layout {@link PngWriter#write16} and {@link HdrMerge#addFrame} expect. */
	@FunctionalInterface
	interface RawCallback {
		void onRaw(int width, int height, byte[] raw);
	}

	/** Copy the {@code hdr} target to a {@code byte[]} and hand it to {@code cb}. Synchronous
	 *  on 1.21.1 (a plain {@code glGetTexImage}), unlike 26.2's fenced async copy — so it's
	 *  always done before anything can release the target. */
	private static void readRawBytes(RawCallback cb) {
		RenderTarget target = SLOT.target("hdr");
		if (target == null) {
			return;
		}
		int width = target.width;
		int height = target.height;
		int size = width * height * 8; // RGBA16F: 4 channels x 2 bytes
		ByteBuffer buffer = MemoryUtil.memAlloc(size);
		try {
			GlStateManager._bindTexture(target.getColorTextureId());
			GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
			GlStateManager._getTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL30.GL_HALF_FLOAT,
					MemoryUtil.memAddress(buffer));
			GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
			GlStateManager._bindTexture(0);
			byte[] raw = new byte[size];
			buffer.get(raw);
			cb.onRaw(width, height, raw);
		} catch (Throwable t) {
			PhotoMode.LOGGER.error("[Photo Mode] HDR readback failed", t);
		} finally {
			MemoryUtil.memFree(buffer);
		}
	}

	/** Read back the RAW target and save it as the RAW Mode file — call once the caller
	 *  knows this is the settled frame. The half-float decode and file write run on the IO pool. */
	public static void readAndSave(File file, PngWriter.Exif exif) {
		readRawBytes((width, height, raw) -> {
			release();
			Util.ioPool().execute(() -> {
				try {
					PngWriter.write16(file, width, height, raw, exif);
					PhotoMode.LOGGER.info("[Photo Mode] HdrCapture: RAW file written: {}", file.getName());
				} catch (Exception e) {
					PhotoMode.LOGGER.error("[Photo Mode] failed to save RAW photo", e);
				}
			});
		});
	}

	/** Read back one frame of a bracket HDR merge. {@code lastFrame} releases the chain once
	 *  its data is copied out. */
	public static void readForMerge(boolean lastFrame, RawCallback cb) {
		readRawBytes((width, height, raw) -> {
			if (lastFrame) {
				release();
			}
			cb.onRaw(width, height, raw);
		});
	}
}
