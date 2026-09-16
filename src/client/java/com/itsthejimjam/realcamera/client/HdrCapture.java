package com.itsthejimjam.realcamera.client;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Optional;

import com.itsthejimjam.realcamera.PhotoMode;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import org.lwjgl.system.MemoryUtil;

/**
 * The high-precision "enhanced" capture path: a single hand-built render pass — not the
 * JSON {@code PostChain} system everything else in this mod uses, which can't express an
 * 8-bit input / 16-bit-float output pair (its one external target is a single fixed
 * format for the whole chain; its internal targets have no configurable format at all).
 * This applies just the two camera-accurate steps (exposure, white balance) to the
 * pristine frame and writes into a genuinely higher-precision target, skipping every
 * destructive step the normal photo's grade applies (contrast crush, film recipe,
 * highlight clamp) — so whatever headroom/shadow detail the scene had survives instead
 * of collapsing into black or white.
 *
 * <p>Modeled directly on vanilla's own {@code RenderTarget.blitAndBlendToTexture} /
 * {@code RenderPipelines.ENTITY_OUTLINE_BLIT} — same shape (a hand-built
 * {@code RenderPipeline} over {@code RenderPipelines.POST_PROCESSING_SNIPPET}, reusing
 * the vanilla {@code core/screenquad} vertex shader's no-vertex-buffer fullscreen
 * triangle), just with our own fragment shader and an explicit RGBA16_FLOAT
 * {@link ColorTargetState} instead of the default 8-bit one.
 *
 * <p>DoF blur is reproduced by three MORE private passes (a separable pre-blur, then the
 * same disc-gather shader the live preview uses), run against our own textures — NOT by
 * reading the live JSON chain's own "swap" output. An earlier attempt did exactly that
 * (marked that chain's internal target {@code "persistent": true} so it would survive
 * past {@code chain.process()}, then read it back via a new {@code PostChainAccessor}
 * accessor) and repeatedly crashed the game — because the JSON change affected the live
 * chain on EVERY photo-mode frame, not just captures, since {@code CrossFrameResourcePool}
 * reuses internal targets across frames unless told not to, and marking one persistent
 * changes that pooling for the whole chain, live preview included. Fully reverted. This
 * version instead reuses the exact same fragment shader assets
 * ({@code post/prefilterh}, {@code post/prefilterv}, {@code post/dof_shaderpack} — same
 * shaders, same tuning as the live preview, just pointed at private textures) inside
 * hand-built {@link RenderPipeline}s exactly like the expose pass below, so its blast
 * radius is the same as the rest of this class: only the already-proven capture() call
 * path, never the live chain or any frame outside an active capture.
 *
 * <p>{@link #capture} takes its colour source as an explicit {@code GpuTextureView}
 * rather than assuming the live render target, so callers can point it at whatever the
 * live grade chain itself is actually reading that frame. For a normal shot that's the
 * pristine {@code mainRenderTarget}, sampled BEFORE the grade chain (see
 * {@code CaptureHookMixin}) overwrites {@code minecraft:main} with the graded result each
 * frame — by the time {@code PhotoCapture.grabIfReady} fires, that's already happened. For
 * a long exposure it's {@code PhotoModeSession.colorViewOverride()} — the CPU-stacked,
 * peak-recovered result {@code PhotoCapture.developStack()} uploads, which is what the
 * live chain is ALSO reading during the develop phase (via
 * {@code mixin/PostPassInputMixin.java}, not the raw render target at all). Using the
 * same override the live chain already consumes means this and the normal photo are
 * always looking at the same data, by construction — no separate long-exposure-specific
 * plumbing to keep in sync.
 */
public final class HdrCapture {
	private static final Identifier PIPELINE_ID = Identifier.fromNamespaceAndPath("realcamera", "pipeline/expose");
	private static final Identifier FRAGMENT_ID = Identifier.fromNamespaceAndPath("realcamera", "post/expose");
	private static final Identifier VERTEX_ID = Identifier.fromNamespaceAndPath("minecraft", "core/screenquad");

	private static final Identifier PREFILTER_H_PIPELINE_ID =
			Identifier.fromNamespaceAndPath("realcamera", "pipeline/dof_preh");
	private static final Identifier PREFILTER_V_PIPELINE_ID =
			Identifier.fromNamespaceAndPath("realcamera", "pipeline/dof_prev");
	private static final Identifier GATHER_PIPELINE_ID =
			Identifier.fromNamespaceAndPath("realcamera", "pipeline/dof_gather");
	/** Reuse the exact fragment shader assets the LIVE preview's DoF pass already uses —
	 *  same blur, same tuning, zero new shader code, just driven by our own textures. */
	private static final Identifier PREFILTER_H_ID = Identifier.fromNamespaceAndPath("realcamera", "post/prefilterh");
	private static final Identifier PREFILTER_V_ID = Identifier.fromNamespaceAndPath("realcamera", "post/prefilterv");
	private static final Identifier GATHER_ID = Identifier.fromNamespaceAndPath("realcamera", "post/dof_shaderpack");

	private static final String BLOCK = "ExposeConfig";
	/** Padded to 16 bytes (two unused trailing floats) even though only two floats are
	 *  actually used — every other uniform buffer in this mod (FilmConfig 16, GradeConfig
	 *  128, DofConfig 48, AidConfig 16) is a multiple of 16, and an 8-byte one turned out
	 *  to reliably crash the game the instant this pass ran, at any resolution — GPU APIs
	 *  generally expect uniform buffers sized to a 16-byte multiple. */
	private static final int UNIFORM_SIZE = 16;
	private static final ByteBuffer SCRATCH = MemoryUtil.memAlloc(UNIFORM_SIZE);

	private static final String DOF_BLOCK = "DofConfig";
	/** Same layout/size as the live DofConfig block (see DofParams) — float + float + vec2
	 *  + 5 float, rounded up to a multiple of 16. */
	private static final int DOF_SIZE = 48;
	private static final ByteBuffer DOF_SCRATCH = MemoryUtil.memAlloc(DOF_SIZE);

	/** HARD KILL SWITCH for just the DoF reproduction below, independent of
	 *  ENHANCED_FILE_DISABLED — if this specific stage ever misbehaves, this falls back to
	 *  the sharp-everywhere enhanced file (already field-tested up to 8K) instead of losing
	 *  the whole feature again. Flip this first if something looks wrong after enabling
	 *  depth here; only reach for the bigger switch if that alone doesn't fix it. */
	private static final boolean ENHANCED_DOF_DISABLED = false;

	private static RenderPipeline pipeline;
	private static CompiledRenderPipeline compiledPipeline;
	private static boolean pipelineValid;
	private static boolean pipelineLogged;
	private static GpuBuffer uniformBuffer;
	private static TextureTarget hdrTarget;

	private static RenderPipeline prefilterHPipeline;
	private static RenderPipeline prefilterVPipeline;
	private static RenderPipeline gatherPipeline;
	private static CompiledRenderPipeline compiledPrefilterH;
	private static CompiledRenderPipeline compiledPrefilterV;
	private static CompiledRenderPipeline compiledGather;
	private static boolean dofPipelinesValid;
	private static GpuBuffer dofUniformBuffer;
	private static TextureTarget preH;
	private static TextureTarget pre;
	private static TextureTarget gathered;

	/** One-shot: did the render pass itself finish? If a crash recurs and this DIDN'T
	 *  log, the render pass is the culprit; if it DID log, look at the readback instead. */
	private static boolean realcamera$drawLogged;

	private HdrCapture() {
	}

	private static void ensurePipeline() {
		if (pipeline != null) {
			return;
		}
		BindGroupLayout exposeLayout = BindGroupLayout.builder()
				.withUniform(BLOCK, UniformType.UNIFORM_BUFFER)
				.build();
		pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(PIPELINE_ID)
				.withVertexShader(VERTEX_ID)
				.withFragmentShader(FRAGMENT_ID)
				.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
				.withBindGroupLayout(exposeLayout)
				.withColorTargetState(new ColorTargetState(
						Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.build();
		try {
			compiledPipeline = RenderSystem.getCompiledPipeline(pipeline);
		} catch (Throwable t) {
			compiledPipeline = null;
			PhotoMode.LOGGER.error("[Photo Mode] HdrCapture pipeline compile failed", t);
		}
		pipelineValid = compiledPipeline != null;
		PhotoMode.LOGGER.info("[Photo Mode] HdrCapture pipeline compiled: valid={}", pipelineValid);
		uniformBuffer = RenderSystem.getDevice().createBuffer(
				() -> "realcamera ExposeConfig",
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
				(long) UNIFORM_SIZE);

		// DoF gather pipelines — private, isolated from the live JSON chain (see class doc
		// for why). Failure here is non-fatal: the enhanced file just falls back to sharp.
		try {
			BindGroupLayout dofUniformLayout = BindGroupLayout.builder()
					.withUniform(DOF_BLOCK, UniformType.UNIFORM_BUFFER)
					.build();
			BindGroupLayout gatherSamplers = BindGroupLayout.builder()
					.withUniform("MainSampler", UniformType.COMBINED_IMAGE_SAMPLER)
					.withUniform("PreSampler", UniformType.COMBINED_IMAGE_SAMPLER)
					.withUniform("MainDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
					.build();
			ColorTargetState rgba8 = new ColorTargetState(
					Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL);
			ColorTargetState rgba16f = new ColorTargetState(
					Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL);

			prefilterHPipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
					.withLocation(PREFILTER_H_PIPELINE_ID)
					.withVertexShader(VERTEX_ID)
					.withFragmentShader(PREFILTER_H_ID)
					.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
					.withBindGroupLayout(dofUniformLayout)
					.withColorTargetState(rgba8)
					.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
					.build();
			prefilterVPipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
					.withLocation(PREFILTER_V_PIPELINE_ID)
					.withVertexShader(VERTEX_ID)
					.withFragmentShader(PREFILTER_V_ID)
					.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
					.withBindGroupLayout(dofUniformLayout)
					.withColorTargetState(rgba8)
					.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
					.build();
			gatherPipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
					.withLocation(GATHER_PIPELINE_ID)
					.withVertexShader(VERTEX_ID)
					.withFragmentShader(GATHER_ID)
					.withBindGroupLayout(gatherSamplers)
					.withBindGroupLayout(dofUniformLayout)
					.withColorTargetState(rgba16f)
					.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
					.build();

			compiledPrefilterH = RenderSystem.getCompiledPipeline(prefilterHPipeline);
			compiledPrefilterV = RenderSystem.getCompiledPipeline(prefilterVPipeline);
			compiledGather = RenderSystem.getCompiledPipeline(gatherPipeline);
			boolean hValid = compiledPrefilterH != null;
			boolean vValid = compiledPrefilterV != null;
			boolean gValid = compiledGather != null;
			dofPipelinesValid = hValid && vValid && gValid;
			PhotoMode.LOGGER.info(
					"[Photo Mode] HdrCapture DoF pipelines compiled: preH={} preV={} gather={}",
					hValid, vValid, gValid);

			dofUniformBuffer = RenderSystem.getDevice().createBuffer(
					() -> "realcamera DofConfig (enhanced)",
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
					(long) DOF_SIZE);
		} catch (Throwable t) {
			dofPipelinesValid = false;
			PhotoMode.LOGGER.error("[Photo Mode] HdrCapture DoF pipeline compile failed", t);
		}
	}

	private static void ensureTarget(int width, int height) {
		if (hdrTarget == null) {
			// (label, width, height, colorFormat, depthFormat) — null depthFormat means no
			// depth attachment at all (confirmed against RenderTarget's own bytecode: it
			// only allocates a depth texture when this is non-null).
			hdrTarget = new TextureTarget("realcamera hdr expose", width, height, GpuFormat.RGBA16_FLOAT, null);
		} else if (hdrTarget.width != width || hdrTarget.height != height) {
			hdrTarget.resize(width, height);
		}
		if (ENHANCED_DOF_DISABLED || !dofPipelinesValid) {
			return;
		}
		if (preH == null) {
			preH = new TextureTarget("realcamera dof preh", width, height, GpuFormat.RGBA8_UNORM, null);
		} else if (preH.width != width || preH.height != height) {
			preH.resize(width, height);
		}
		if (pre == null) {
			pre = new TextureTarget("realcamera dof pre", width, height, GpuFormat.RGBA8_UNORM, null);
		} else if (pre.width != width || pre.height != height) {
			pre.resize(width, height);
		}
		if (gathered == null) {
			gathered = new TextureTarget("realcamera dof gathered", width, height, GpuFormat.RGBA16_FLOAT, null);
		} else if (gathered.width != width || gathered.height != height) {
			gathered.resize(width, height);
		}
	}

	/** True once the pipeline has been attempted and did compile — callers should skip
	 *  the whole feature (not just this call) if a first attempt reports false, rather
	 *  than retrying every frame. */
	public static boolean isReady() {
		return pipeline != null && pipelineValid;
	}

	/**
	 * Run the expose-only pass against the given (pristine, not-yet-graded) source
	 * target, writing into our own RGBA16F scratch target — cheap enough to call every
	 * frame while a capture is in flight (same order of cost as the existing DoF passes),
	 * so it's always current by the time a caller decides to read it back.
	 *
	 * @param colorView the frame to expose — the live pristine render target for a normal
	 *                  shot, or {@code PhotoModeSession.colorViewOverride()} (the stacked
	 *                  long-exposure result) when that's set. Callers resolve which one;
	 *                  this class just exposes whatever view it's handed.
	 * @param depthView scene depth for DoF reproduction, or {@code null} to skip it (the
	 *                   enhanced file then comes out sharp everywhere, as before).
	 */
	public static void capture(GpuTextureView colorView, int width, int height, GpuTextureView depthView,
			float exposureMult, float whiteBalance, float aperture, float focusU, float focusV) {
		ensurePipeline();
		if (!pipelineValid) {
			return;
		}
		try {
			ensureTarget(width, height);

			GpuTextureView colorSource = colorView;
			if (!ENHANCED_DOF_DISABLED && dofPipelinesValid && depthView != null) {
				colorSource = runDofGather(colorView, depthView, aperture, focusU, focusV);
			}

			SCRATCH.clear();
			Std140Builder.intoBuffer(SCRATCH)
					.putFloat(exposureMult).putFloat(whiteBalance).putFloat(0.0f).putFloat(0.0f);
			SCRATCH.rewind();
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(uniformBuffer.slice(), SCRATCH);

			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			GpuTextureView exposeOutput = hdrTarget.getColorTextureView();
			try (RenderPass pass = encoder.createRenderPass(() -> "realcamera expose", exposeOutput, Optional.empty())) {
				pass.setPipeline(compiledPipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform(BLOCK, uniformBuffer);
				pass.setUniform("InSampler", colorSource,
						RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				pass.draw(3, 1, 0, 0);
			}
			if (!realcamera$drawLogged) {
				realcamera$drawLogged = true;
				PhotoMode.LOGGER.info("[Photo Mode] HdrCapture: render pass completed");
			}
		} catch (Throwable t) {
			// The normal capture must never fail because this extra pass did — log once
			// and let PhotoCapture's readback just skip the enhanced file for this shot.
			if (!pipelineLogged) {
				pipelineLogged = true;
				PhotoMode.LOGGER.error("[Photo Mode] HdrCapture pass failed", t);
			}
		}
	}

	/** Three private passes — separable pre-blur (H then V), then the same disc-gather
	 *  shader the live preview uses — against our own textures, reusing the live DoF
	 *  settings computed by {@link DofParams#compute}. See the class doc for why this is
	 *  NOT sourced from the live JSON chain instead. Returns the gathered texture's view,
	 *  to feed into the expose pass in place of the pristine source. */
	private static GpuTextureView runDofGather(GpuTextureView colorView, GpuTextureView depthView,
			float aperture, float focusU, float focusV) {
		DofParams.DofConfigValues v = DofParams.compute(aperture, focusU, focusV);
		DOF_SCRATCH.clear();
		Std140Builder.intoBuffer(DOF_SCRATCH)
				.putFloat(v.blurStrength()).putFloat(v.maxRadius()).putVec2(v.focusU(), v.focusV())
				.putFloat(v.farBlurGain()).putFloat(v.softKnee()).putFloat(v.hlBoost())
				.putFloat(v.hlThreshold()).putFloat(v.onsetMaxPx())
				.putFloat(0.0f).putFloat(0.0f).putFloat(0.0f);
		DOF_SCRATCH.rewind();
		RenderSystem.getDevice().createCommandEncoder().writeToBuffer(dofUniformBuffer.slice(), DOF_SCRATCH);

		GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> "realcamera dof preh", preH.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(compiledPrefilterH);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform(DOF_BLOCK, dofUniformBuffer);
			pass.setUniform("InSampler", colorView, linear);
			pass.draw(3, 1, 0, 0);
		}
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> "realcamera dof prev", pre.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(compiledPrefilterV);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform(DOF_BLOCK, dofUniformBuffer);
			pass.setUniform("InSampler", preH.getColorTextureView(), linear);
			pass.draw(3, 1, 0, 0);
		}
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> "realcamera dof gather", gathered.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(compiledGather);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform(DOF_BLOCK, dofUniformBuffer);
			pass.setUniform("MainSampler", colorView, linear);
			pass.setUniform("PreSampler", pre.getColorTextureView(), linear);
			pass.setUniform("MainDepthSampler", depthView, nearest);
			pass.draw(3, 1, 0, 0);
		}
		return gathered.getColorTextureView();
	}

	/** The current scratch target — read back once the caller knows this frame is the
	 *  settled one (same timing as the normal {@code Screenshot.takeScreenshot} grab). */
	public static RenderTarget target() {
		return hdrTarget;
	}

	/** Free the RGBA16F scratch texture's GPU memory — call once a capture finishes
	 *  (successfully or not), so a large capture's VRAM doesn't stay reserved for the
	 *  rest of the session; the next capture that wants it just recreates it fresh in
	 *  {@link #ensureTarget}. The small, fixed-size pipeline/uniform buffer are left
	 *  alone — cheap enough to just keep. */
	public static void release() {
		if (hdrTarget != null) {
			hdrTarget.destroyBuffers();
			hdrTarget = null;
		}
		if (preH != null) {
			preH.destroyBuffers();
			preH = null;
		}
		if (pre != null) {
			pre.destroyBuffers();
			pre = null;
		}
		if (gathered != null) {
			gathered.destroyBuffers();
			gathered = null;
		}
	}

	/** A raw readback result, handed back on the render thread (see {@link #readRawBytes}).
	 *  Package-private (not private) so callers like {@link PhotoCapture} can name the
	 *  type if they need to, even though passing a lambda never requires it. */
	@FunctionalInterface
	interface RawCallback {
		void onRaw(int width, int height, byte[] raw);
	}

	/**
	 * Copy the current scratch target to a packed {@code byte[]} and hand it to
	 * {@code cb} — on the render thread, same timing as the normal grab's
	 * {@code Screenshot.takeScreenshot} callback (this is the same fenced-task
	 * mechanism, confirmed against the engine source: {@code RenderSystem
	 * .executePendingTasks()} runs once per frame from the main loop, on the render
	 * thread). Mirrors {@code Screenshot}'s own readback shape (a tightly-packed
	 * {@code width*height*blockSize} buffer, mapped once a fence confirms the GPU->CPU
	 * copy landed) but for RGBA16_FLOAT (8 bytes/pixel) instead of {@code NativeImage}'s
	 * fixed 8-bit format, which can't represent this at all.
	 *
	 * <p>Deliberately does NOT release the texture or decide where the result goes next
	 * — {@code cb} runs on the render thread too, so it can still safely call
	 * {@link #release()} itself if this is the last read it needs, before handing the
	 * bytes off anywhere else (e.g. {@code Util.ioPool()}). Shared by {@link #readAndSave}
	 * (RAW Mode) and {@link #readForMerge} (bracket HDR merge) so this GPU-copy mechanics
	 * — the part that already caused one real bug this session (releasing too early) —
	 * stays in exactly one tested place. */
	private static void readRawBytes(RawCallback cb) {
		if (hdrTarget == null) {
			return;
		}
		int width = hdrTarget.width;
		int height = hdrTarget.height;
		long bufSize = (long) width * (long) height * 8L; // RGBA16_FLOAT: 4 channels x 2 bytes
		GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
				() -> "realcamera hdr readback",
				GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
				bufSize);
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(
				hdrTarget.getColorTexture(), buffer, 0L,
				() -> {
					try {
						byte[] raw = new byte[(int) bufSize];
						try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
							view.data().get(raw);
						}
						cb.onRaw(width, height, raw);
					} catch (Throwable t) {
						PhotoMode.LOGGER.error("[Photo Mode] HDR readback failed", t);
					} finally {
						buffer.close();
					}
				},
				0);
	}

	/**
	 * Read back the scratch target and save it as the RAW Mode enhanced file — call once
	 * the caller knows this is the settled frame. Render-thread work is kept to the copy
	 * + a raw byte memcpy; the actual half-float decode and file write happen on the IO
	 * pool.
	 */
	public static void readAndSave(File file, PngWriter.Exif exif) {
		PhotoMode.LOGGER.info("[Photo Mode] HdrCapture: readAndSave starting");
		readRawBytes((width, height, raw) -> {
			PhotoMode.LOGGER.info("[Photo Mode] HdrCapture: mapped + copied to heap, dispatching encode");
			// Safe to release the GPU texture now — its data is already copied to the
			// CPU-side `raw` array above, and this callback only runs once the copy's
			// own fence confirms the GPU is done reading from it. Releasing any earlier
			// (e.g. unconditionally when a capture ends, regardless of whether an
			// enhanced file was even requested) risked destroying it while a copy from
			// it was still in flight, or before this method got a chance to read it at
			// all.
			release();
			Util.ioPool().execute(() -> {
				try {
					PngWriter.write16(file, width, height, raw, exif);
					PhotoMode.LOGGER.info("[Photo Mode] HdrCapture: enhanced file written: {}", file.getName());
				} catch (Exception e) {
					PhotoMode.LOGGER.error("[Photo Mode] failed to save enhanced photo", e);
				}
			});
		});
	}

	/**
	 * Read back the scratch target for one frame of a bracket HDR merge — call once per
	 * bracket frame, at the same "settled" moment {@link PhotoCapture} dispatches that
	 * frame's normal screenshot grab. {@code lastFrame} must be true exactly when this is
	 * the final bracket frame ({@code PhotoCapture} already knows this synchronously, on
	 * the render thread, before dispatch) — the texture is released right here, in the
	 * same render-thread callback {@link #readRawBytes} provides, rather than waiting on
	 * {@link HdrMerge}'s own (background-thread) completion, which would make the release
	 * racy against whatever capture comes next.
	 */
	public static void readForMerge(boolean lastFrame, RawCallback cb) {
		readRawBytes((width, height, raw) -> {
			if (lastFrame) {
				release();
			}
			cb.onRaw(width, height, raw);
		});
	}
}
