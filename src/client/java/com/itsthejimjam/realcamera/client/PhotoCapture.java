package com.itsthejimjam.realcamera.client;

import java.io.File;

import com.itsthejimjam.realcamera.PhotoMode;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import org.lwjgl.glfw.GLFW;

/**
 * The shutter. On request the OS window is genuinely resized to the chosen output
 * resolution, {@code WindowSizeMixin} keeps the reported size in step, and the frame
 * is grabbed at the end of {@code renderFrame}, supersample-downscaled, and written to
 * {@code <gameDir>/photos/}.
 *
 * <p>When {@link LongExposure} is armed for a slow shutter the capture runs a small
 * state machine: step the still-frozen world forward by a fixed tick count between each
 * of a fixed number of sub-frames (so the shutter's worth of game-time elapses no matter
 * how long rendering each sub-frame actually takes), stack them on the CPU, unfreeze,
 * write the stacked image back into {@code minecraft:main} (via a colour-input override
 * on the post chain) so DoF / grade / grain still apply, then grab.
 */
public final class PhotoCapture {

	private static final int IDLE = 0;
	private static final int RESIZING = 1;
	private static final int EXPOSING = 2;
	private static final int DEVELOP = 3;

	private static volatile int phase = IDLE;
	private static volatile boolean grabQueued = false;
	private static volatile boolean chainReady = false;
	private static int waitFrames = 0;
	private static final int MAX_WAIT_FRAMES = 24;

	private static int savedWinW = 0;
	private static int savedWinH = 0;
	private static boolean windowResized = false;

	// --- long exposure ---
	private static int longExpMode = LongExposure.OFF;
	private static int subFrames = 0;
	private static int totalExposureTicks = 0;
	private static int ticksSteppedSoFar = 0;
	private static boolean waitingForStep = false;
	private static int warmupLeft = 0;
	private static int stacked = 0;
	private static int lastStackFrames = 0;
	private static volatile boolean readbackInFlight = false;
	private static final ExposureStack STACK = new ExposureStack();
	private static GpuTexture stackTex;
	private static GpuTextureView stackView;

	// --- exposure bracketing ---
	private static float[] bracketEvs = null;   // null = not a bracketed capture
	private static int bracketIdx = 0;
	private static String bracketStamp = "";
	private static volatile float bracketBiasEv = 0.0f;

	/** EV bias for the bracket frame currently being rendered — read by {@link ExposureParams}. */
	public static float bracketBiasEv() {
		return bracketBiasEv;
	}

	private PhotoCapture() {
	}

	public static void request() {
		if (phase != IDLE) {
			return;
		}
		if (PhotoModeSession.noLensAttached()) {
			// No glass on the mount — the shutter is dead, exactly like a real body.
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				mc.player.sendOverlayMessage(Component.literal("Attach a lens to shoot"));
			}
			return;
		}
		CameraSounds.shutter(PhotoModeSession.deviceItem());
		// Freeze the instant the shutter is pressed — the resize/settle/render sequence
		// this triggers takes multiple real-world frames (several seconds at 8K under a
		// heavy shader pack), and the world was ticking normally the whole time, so the
		// sun/clouds/weather could visibly move between "the shot I clicked" and "the shot
		// that actually got saved." Bracket sequences had it worse: each frame needs its
		// own settle-and-capture cycle, so consecutive frames could span real seconds apart
		// — too different to merge as HDR. Long exposure stays frozen the whole way
		// through and instead steps the world forward by an exact tick count per
		// sub-frame (see EXPOSING below), so this hold applies for the entire capture,
		// not just the resize/settle wait.
		PhotoModeSession.setWorldFrozen(true);
		double sec = PhotoModeSession.getShutterSeconds();
		// Bracketing takes precedence over the automatic long exposure for a capture.
		if (Bracket.on()) {
			bracketEvs = Bracket.offsets();
			bracketIdx = 0;
			bracketStamp = Util.getFilenameFormattedDateTime();
			longExpMode = LongExposure.OFF;
		} else {
			bracketEvs = null;
			longExpMode = LongExposure.modeFor(sec, PhotoModeSession.motionBlurTriggerSeconds());
		}
		bracketBiasEv = 0.0f;
		subFrames = LongExposure.subFrames(sec);
		if (longExpMode != LongExposure.OFF && PhotoModeSession.handheldShakeShot()) {
			subFrames = Math.max(subFrames, 18);   // smoother sweep + dither averaging
		}
		totalExposureTicks = LongExposure.totalTicks(sec);
		ticksSteppedSoFar = 0;
		waitingForStep = false;
		phase = RESIZING;
		grabQueued = false;
		chainReady = false;
		waitFrames = 0;
		windowResized = false;
		stacked = 0;
		warmupLeft = 3;
		readbackInFlight = false;

		if (com.itsthejimjam.realcamera.client.config.PhotoConfig.get().saveEnhancedFile && !wantsEnhancedFile()
				&& bracketEvs == null && longExpMode == LongExposure.OFF) {
			// Enabled, and otherwise eligible, but over the resolution cap for this shot —
			// say so, rather than silently not producing the second file.
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				mc.player.sendOverlayMessage(Component.literal(ShaderPackCompat.shaderPackActive()
						? "RAW mode skipped — over the " + ENHANCED_MAX_EDGE + "px cap for now"
						: "RAW mode needs a shader pack — skipped"));
			}
		}
	}

	public static boolean wantsBigFrame() {
		return phase != IDLE;
	}

	/** True only while a long exposure is stacking raw sub-frames — the effect chain is
	 *  held off then and the stack is graded once at the end. Bracket frames use EXPOSING
	 *  too, but each is a finished graded frame, so the chain must keep running for them. */
	public static boolean isLongExposureStacking() {
		return phase == EXPOSING && longExpMode != LongExposure.OFF;
	}

	/** This whole capture is a long exposure (any phase). */
	public static boolean isLongExposureCapture() {
		return phase != IDLE && longExpMode != LongExposure.OFF;
	}

	/** Long-edge cap on the enhanced-file capture. A large enhanced capture caused a full
	 *  system freeze (GPU-driver-level, not something Java can catch or recover from)
	 *  before the real root causes (undersized uniform buffer, resize-ramp texture churn,
	 *  release-before-read ordering) were found and fixed — see HdrCapture.java and
	 *  finishCapture()/readAndSave(). With those fixed, 3840 (the 4K tier) was confirmed
	 *  stable at x1 supersample. Raised to 7680 to also cover the 6K/8K tiers (there is no
	 *  size between the mod's fixed resolution tiers to test incrementally with) — test
	 *  6K then 8K one at a time, at x1 supersample, watching Task Manager, before trusting
	 *  either. Drop back to a lower tier immediately if either misbehaves. */
	private static final int ENHANCED_MAX_EDGE = 7680;

	/** HARD KILL SWITCH. A large capture with "Save Enhanced File" on caused a full
	 *  system freeze requiring a hard restart — well beyond an application crash, into
	 *  GPU-driver-hang territory. Re-enabled after: (a) the likely-real root cause was
	 *  found (the earlier freeze tests turned out to have supersample at x4, meaning the
	 *  actual render size was far larger than the resolution label suggested — e.g. a
	 *  "1080p" shot was really rendering near 8K internally), (b) real hardening (resize
	 *  churn during the resolution ramp-up, GPU memory released between captures), and
	 *  (c) the cap tightened to exactly the size actually confirmed safe. Re-test
	 *  incrementally, at x1 supersample, watching Task Manager — do not jump straight
	 *  back to a large/high-supersample combination that hasn't been individually
	 *  verified. */
	private static final boolean ENHANCED_FILE_DISABLED = false;

	/** True while the high-precision enhanced file should be captured alongside the
	 *  normal photo — a single shot or a long exposure (HdrCapture sources the stacked
	 *  result via {@code PhotoModeSession.colorViewOverride()} for the latter, same as the
	 *  live grade chain does; see HdrCapture's class doc). Brackets are handled instead by
	 *  {@link #wantsHdrMerge()}, which fuses the whole burst into one file rather than
	 *  enhancing a single frame. */
	public static boolean wantsEnhancedFile() {
		if (ENHANCED_FILE_DISABLED) {
			return false;
		}
		if (!wantsBigFrame() || !com.itsthejimjam.realcamera.client.config.PhotoConfig.get().saveEnhancedFile
				|| bracketEvs != null) {
			return false;
		}
		// HdrCapture.capture() is only ever driven from the shader-pack path (see
		// CaptureHookMixin) — without a shader pack nothing ever fills its target, so
		// promising a RAW file here would silently produce nothing.
		if (!ShaderPackCompat.shaderPackActive()) {
			return false;
		}
		return Math.max(overrideWidth(), overrideHeight()) <= ENHANCED_MAX_EDGE;
	}

	/** True while a bracket sequence's frames should be fused in-mod into one 16-bit HDR
	 *  file (see HdrMerge) instead of saved separately — reuses the exact same
	 *  HdrCapture pipeline, resolution cap, and kill switch as RAW Mode, just driven by
	 *  the bracket loop instead of a single shot. */
	public static boolean wantsHdrMerge() {
		if (ENHANCED_FILE_DISABLED) {
			return false;
		}
		if (!wantsBigFrame() || !com.itsthejimjam.realcamera.client.config.PhotoConfig.get().autoMergeHdr
				|| bracketEvs == null) {
			return false;
		}
		// Same reason as wantsEnhancedFile(): with no shader pack there's never any HDR data
		// to merge, and saveBracketFrame() skips the normal JPEGs whenever this is true — so
		// without this check a bracket taken without a shader pack saved nothing at all.
		if (!ShaderPackCompat.shaderPackActive()) {
			return false;
		}
		return Math.max(overrideWidth(), overrideHeight()) <= ENHANCED_MAX_EDGE;
	}

	/** Progress through the sub-frame stack, 0..1 (0 when not stacking). Drives the
	 *  handheld motion-blur sweep in {@link PhotoModeSession#tickShake()}. */
	public static float exposureProgress01() {
		if (phase != EXPOSING || longExpMode == LongExposure.OFF || subFrames <= 0) {
			return 0.0f;
		}
		float p = (float) stacked / subFrames;
		return p < 0.0f ? 0.0f : (p > 1.0f ? 1.0f : p);
	}

	/** Which sub-frame the stack is on — the per-frame dither-decorrelation hash key. */
	public static int stackedFrames() {
		return stacked;
	}

	/** Real window size before the capture resize (0 if not resized yet / fullscreen). */
	public static int savedWindowWidth() {
		return savedWinW;
	}

	public static int savedWindowHeight() {
		return savedWinH;
	}

	/** The window/framebuffer size the capture renders at. Long exposure is capped smaller. */
	public static int overrideWidth() {
		return renderSize()[0];
	}

	public static int overrideHeight() {
		return renderSize()[1];
	}

	private static int[] renderSize() {
		// Long exposure accumulates full frames on the CPU, so it renders at output size
		// (no supersample) and caps the long edge to keep memory sane. Bracket frames are
		// each saved independently, so they get the normal full-quality path.
		if (longExpMode != LongExposure.OFF) {
			int w = Framing.outputWidth();
			int h = Framing.outputHeight();
			int edge = Math.max(w, h);
			if (edge > LongExposure.MAX_EDGE) {
				double s = (double) LongExposure.MAX_EDGE / edge;
				w = (int) Math.round(w * s) & ~1;
				h = (int) Math.round(h * s) & ~1;
			}
			return new int[] {w, h};
		}
		int ss = effectiveSupersampleForCapture();
		return new int[] {Framing.outputWidth() * ss, Framing.outputHeight() * ss};
	}

	/** Supersample actually used for a capture — forced to x1 when the enhanced file is
	 *  on. That pipeline's own VRAM footprint (three extra full-size buffers for DoF, plus
	 *  the RGBA16F expose target — see HdrCapture) eats into the headroom that a plain
	 *  capture at the same resolution would otherwise have; an 8K x SS4 capture (which
	 *  Framing.effectiveSupersample() already reduces to an internal render no bigger than
	 *  a bare 8K x SS1 shot) crashed with the enhanced file on, right after DoF
	 *  reproduction was added, even though the same resolution had been stable before that
	 *  addition. Must be used everywhere the render size is computed AND wherever the
	 *  captured image is downsampled back to output size (see grabDownscale) — the two
	 *  have to agree or the downsample scales wrong. */
	private static int effectiveSupersampleForCapture() {
		com.itsthejimjam.realcamera.client.config.PhotoConfig cfg =
				com.itsthejimjam.realcamera.client.config.PhotoConfig.get();
		if ((cfg.saveEnhancedFile || (cfg.autoMergeHdr && Bracket.on())) && ShaderPackCompat.shaderPackActive()) {
			return 1;
		}
		return Framing.effectiveSupersample();
	}

	/** Downscale applied when grabbing (1 for long exposure — it renders at output size). */
	private static int grabDownscale() {
		return longExpMode != LongExposure.OFF ? 1 : effectiveSupersampleForCapture();
	}

	public static void ensureWindowSized() {
		if (phase == IDLE || windowResized) {
			return;
		}
		windowResized = true;
		Window w = Minecraft.getInstance().getWindow();
		// Always record the real size — the capture-FOV / focus-remap math needs it even
		// in fullscreen (where we can't grow the OS window, but the framebuffer is still
		// spoofed to the capture size).
		savedWinW = w.getScreenWidth();
		savedWinH = w.getScreenHeight();
		if (w.isFullscreen()) {
			return;
		}
		GLFW.glfwSetWindowSize(w.handle(), overrideWidth(), overrideHeight());
	}

	private static void restoreWindow() {
		if (savedWinW <= 0) {
			return;
		}
		Window w = Minecraft.getInstance().getWindow();
		if (!w.isFullscreen()) {
			GLFW.glfwSetWindowSize(w.handle(), savedWinW, savedWinH);
		}
		savedWinW = 0;
		savedWinH = 0;
	}

	/** Called from the pre-GUI effect-chain hook once it has run at the full capture size,
	 *  with the shader pack's depth and the real OS window also caught up to that size. */
	public static void markChainReady() {
		if (phase == RESIZING || phase == DEVELOP
				|| (phase == EXPOSING && bracketEvs != null)) {
			chainReady = true;
		}
	}

	/**
	 * Called at the end of {@code renderFrame}. Drives the state machine: wait for the
	 * resize, run the exposure stack, then grab.
	 */
	public static void tick(RenderTarget mainTarget) {
		if (phase == IDLE || mainTarget == null) {
			return;
		}
		boolean atSize = mainTarget.width == overrideWidth() && mainTarget.height == overrideHeight();

		if (phase == RESIZING) {
			if (!atSize) {
				return;
			}
			if (bracketEvs != null) {
				bracketIdx = 0;
				bracketBiasEv = bracketEvs[0];
				chainReady = false;
				waitFrames = 0;
				warmupLeft = 8; // extra settle for the shader pack's dither/TAA after the resize
				if (wantsHdrMerge()) {
					HdrMerge.begin(mainTarget.width, mainTarget.height, bracketEvs.length);
				}
				phase = EXPOSING;
				return;
			}
			if (longExpMode == LongExposure.OFF) {
				grabIfReady(mainTarget);
				return;
			}
			// begin the exposure: the world stays frozen and gets stepped forward by an
			// exact tick count per sub-frame below, instead of unfreezing and racing a
			// boosted tick rate against however long each sub-frame takes to render.
			STACK.begin(mainTarget.width, mainTarget.height);
			phase = EXPOSING;
			return;
		}

		if (phase == EXPOSING && bracketEvs != null) {
			if (!atSize || readbackInFlight) {
				return;
			}
			if (bracketIdx >= bracketEvs.length) {
				bracketBiasEv = 0.0f;
				int n = bracketEvs.length;
				int w = mainTarget.width / grabDownscale();
				int h = mainTarget.height / grabDownscale();
				finishCapture();
				announce(Minecraft.getInstance(),
						"Saved " + n + " bracket frames   " + w + "×" + h + "   (BRACKET)");
				return;
			}
			if (bracketBiasEv != bracketEvs[bracketIdx]) {
				bracketBiasEv = bracketEvs[bracketIdx];
				warmupLeft = 3;       // let the effect chain re-render at the new exposure
				chainReady = false;   // and re-confirm a full-res, settled pass before grabbing
				waitFrames = 0;
				return;
			}
			if (warmupLeft > 0) {
				warmupLeft--;
				return;
			}
			// Wait for a confirmed full-resolution chain pass (shader depth + real OS window
			// caught up), same as a single shot — otherwise a pack composites its sky/cloud
			// dither at the wrong scale and it reads as a lattice.
			if (!chainReady && waitFrames++ < MAX_WAIT_FRAMES) {
				return;
			}
			readbackInFlight = true;
			int frameNo = bracketIdx + 1;
			float ev = bracketEvs[bracketIdx];
			int total = bracketEvs.length;
			int ss = grabDownscale();
			if (wantsHdrMerge()) {
				boolean isLastFrame = frameNo == total;
				// Runs on the render thread (see HdrCapture.readForMerge) — addFrame and,
				// for the last frame, finishAsync just submit to HdrMerge's own executor,
				// so calling them in this order here guarantees that ordering there too.
				HdrCapture.readForMerge(isLastFrame, (w, h, raw) -> {
					HdrMerge.addFrame(w, h, raw);
					if (isLastFrame) {
						File dir = new File(Minecraft.getInstance().gameDirectory, "photos");
						File hdrFile = new File(dir, bracketStamp + "_HDR.png");
						PngWriter.Exif hdrExif = new PngWriter.Exif(PhotoModeSession.getShutterSeconds(),
								PhotoModeSession.getAperture(), PhotoModeSession.getIso(),
								PhotoModeSession.getExposureComp(), System.currentTimeMillis());
						HdrMerge.finishAsync(hdrFile, hdrExif, () -> {
							Minecraft mc = Minecraft.getInstance();
							mc.execute(() -> announce(mc, "HDR merge saved   " + hdrFile.getName()));
						});
					}
				});
			}
			try {
				Screenshot.takeScreenshot(mainTarget, ss, image ->
						saveBracketFrame(image, frameNo, total, ev));
			} catch (Throwable t) {
				readbackInFlight = false;
				bracketIdx++;
				PhotoMode.LOGGER.warn("[Photo Mode] bracket frame readback failed: {}", t.toString());
			}
			return;
		}

		if (phase == EXPOSING) {
			if (!atSize) {
				return;
			}
			if (warmupLeft > 0) {
				warmupLeft--;
				return;
			}
			if (readbackInFlight) {
				return;
			}
			if (stacked < subFrames) {
				if (waitingForStep) {
					if (PhotoModeSession.isWorldStepping()) {
						return; // still advancing ticks for this sub-frame
					}
					waitingForStep = false;
				} else {
					// Evenly distribute totalExposureTicks across subFrames sub-frames
					// (target cumulative ticks for slot N, minus what's already been run,
					// avoids drift from rounding each slot's share independently).
					int targetCumulative = (int) Math.round((double) (stacked + 1) * totalExposureTicks / subFrames);
					int stepNow = Math.max(0, targetCumulative - ticksSteppedSoFar);
					ticksSteppedSoFar = targetCumulative;
					if (stepNow > 0) {
						PhotoModeSession.stepWorldTicks(stepNow);
						waitingForStep = true;
						return; // wait for it to land before capturing this sub-frame
					}
				}
				readbackInFlight = true;
				try {
					Screenshot.takeScreenshot(mainTarget, 1, image -> {
						try (image) {
							STACK.add(image);
						} catch (Throwable t) {
							PhotoMode.LOGGER.warn("[Photo Mode] stack add failed: {}", t.toString());
						} finally {
							stacked++;
							readbackInFlight = false;
						}
					});
				} catch (Throwable t) {
					readbackInFlight = false;
					PhotoMode.LOGGER.warn("[Photo Mode] stack readback failed: {}", t.toString());
					stacked = subFrames; // bail to develop
				}
				return;
			}
			// stacking done -> develop. Unfreeze — the world stayed frozen through every
			// stepped tick above, and photo mode no longer freezes it from here.
			PhotoModeSession.setWorldFrozen(false);
			developStack(mainTarget.width, mainTarget.height);
			phase = DEVELOP;
			return;
		}

		if (phase == DEVELOP) {
			grabIfReady(mainTarget);
		}
	}

	/** Write one bracket frame (runs in the screenshot callback). All frames of a burst
	 *  share {@link #bracketStamp} and are tagged {@code BRACKET_<i>of<N>_<ev>} so they
	 *  sort together for merging in an editor later. */
	private static void saveBracketFrame(NativeImage image, int frameNo, int total, float ev) {
		bracketIdx++;
		readbackInFlight = false;
		if (wantsHdrMerge()) {
			// The merged file (see HdrMerge, dispatched alongside this readback) replaces
			// the individual bracket files when Auto Merge is on — either/or, per the
			// toggle, so there's no reason to also encode N JPEGs nobody asked to keep.
			image.close();
			return;
		}
		String evLabel = String.format(java.util.Locale.ROOT, "%+.1fEV", ev).replace("+0.0EV", "0.0EV");
		// JPEG, not PNG: Lightroom's Photo Merge -> HDR doesn't treat PNG as an eligible
		// source at all (confirmed — embedding a correct eXIf chunk wasn't enough), but
		// JPEG+EXIF is exactly what every real camera outputs for a bracket, so it's the
		// combination every merge tool is actually built around.
		String name = bracketStamp + "_BRACKET_" + frameNo + "of" + total + "_" + evLabel + ".jpg";
		Minecraft mc = Minecraft.getInstance();
		File dir = new File(mc.gameDirectory, "photos");
		File file = new File(dir, name);
		// This frame's actual exposure bias — the base compensation plus this bracket
		// slot's EV offset — is exactly what Lightroom's Photo Merge -> HDR reads to tell
		// the frames of a bracket apart and order them; shutter/aperture/ISO stay the
		// camera's real settings, unchanged across the burst.
		PngWriter.Exif exif = new PngWriter.Exif(PhotoModeSession.getShutterSeconds(), PhotoModeSession.getAperture(),
				PhotoModeSession.getIso(), PhotoModeSession.getExposureComp() + ev, System.currentTimeMillis());
		Util.ioPool().execute(() -> {
			try (image) {
				dir.mkdirs();
				PngWriter.writeJpeg(file, image, exif, 0.95f);
			} catch (Exception e) {
				PhotoMode.LOGGER.error("[Photo Mode] failed to save bracket frame", e);
			}
		});
	}

	private static void developStack(int w, int h) {
		try {
			lastStackFrames = STACK.frames();
			NativeImage stackedImage = STACK.finish();
			if (stackTex == null || stackTex.getWidth(0) != w || stackTex.getHeight(0) != h) {
				closeStackTex();
				stackTex = RenderSystem.getDevice().createTexture("realcamera long-exposure",
						GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
						GpuFormat.RGBA8_UNORM, w, h, 1, 1);
			}
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(stackTex, stackedImage);
			stackedImage.close();
			stackView = RenderSystem.getDevice().createTextureView(stackTex);
			PhotoModeSession.setColorViewOverride(stackView);
		} catch (Throwable t) {
			PhotoMode.LOGGER.error("[Photo Mode] developing the exposure stack failed", t);
			phase = DEVELOP; // grabIfReady will still fire a fallback grab of the raw frame
		}
	}

	private static void grabIfReady(RenderTarget mainTarget) {
		if (grabQueued) {
			return;
		}
		if (mainTarget.width != overrideWidth() || mainTarget.height != overrideHeight()) {
			return;
		}
		if (!chainReady && waitFrames++ < MAX_WAIT_FRAMES) {
			return;
		}
		if (!chainReady) {
			PhotoMode.LOGGER.warn("[Photo Mode] capturing without a confirmed effect-chain pass");
		}

		grabQueued = true;
		int ss = grabDownscale();
		int[] rs = renderSize();
		int outW = rs[0] / ss;
		int outH = rs[1] / ss;
		boolean wasLong = longExpMode != LongExposure.OFF;
		String modeNote = wasLong ? "  (" + LongExposure.OPTIONS[longExpMode] + " · " + lastStackFrames + " frames)" : "";

		PngWriter.Exif exif = new PngWriter.Exif(PhotoModeSession.getShutterSeconds(), PhotoModeSession.getAperture(),
				PhotoModeSession.getIso(), PhotoModeSession.getExposureComp(), System.currentTimeMillis());
		boolean wantsEnhanced = wantsEnhancedFile();

		Screenshot.takeScreenshot(mainTarget, ss, image -> {
			finishCapture();
			String stamp = Util.getFilenameFormattedDateTime();
			// Isolated from the normal save below on purpose: this is new, lower-level GPU
			// code (see HdrCapture) — a failure here must never take the normal, already-
			// working photo save down with it.
			if (wantsEnhanced) {
				try {
					File dir = new File(Minecraft.getInstance().gameDirectory, "photos");
					dir.mkdirs();
					HdrCapture.readAndSave(new File(dir, stamp + "_enhanced.png"), exif);
				} catch (Exception e) {
					PhotoMode.LOGGER.error("[Photo Mode] enhanced file capture failed", e);
				}
			}
			try {
				Minecraft mc = Minecraft.getInstance();
				File dir = new File(mc.gameDirectory, "photos");
				File file = new File(dir, stamp + ".png");
				Util.ioPool().execute(() -> {
					try (image) {
						dir.mkdirs();
						// Vanilla writer, not PngWriter: the hand-rolled PNG/EXIF encoder was
						// only ever verified at small resolutions, and a system-freeze-level
						// crash appeared at 4K the same day it was wired into every normal
						// capture (not just enhanced ones, which are separately disabled).
						// Reverting the one normal-photo save path back to the thing that's
						// been stable for this mod's whole history until that's understood.
						image.writeToFile(file);
						mc.execute(() -> announce(mc, "Saved  " + file.getName() + "   " + outW + "×" + outH + modeNote));
					} catch (Exception e) {
						PhotoMode.LOGGER.error("[Photo Mode] failed to save photo", e);
						mc.execute(() -> announce(mc, "Photo save failed — see log"));
					}
				});
			} catch (Exception e) {
				PhotoMode.LOGGER.error("[Photo Mode] capture failed", e);
				image.close();
			}
		});
	}

	/** Runs in the screenshot callback: tear the capture state down. NOT the place to
	 *  release HdrCapture's texture — this runs before the enhanced-file readback even
	 *  starts (see grabIfReady), and destroying it here left readAndSave with nothing to
	 *  read every time, silently. HdrCapture releases itself once its own GPU copy is
	 *  actually confirmed done (see readAndSave). */
	private static void finishCapture() {
		PhotoModeSession.setWorldFrozen(false);
		PhotoModeSession.setColorViewOverride(null);
		closeStackView();
		STACK.reset();
		restoreWindow();
		phase = IDLE;
		grabQueued = false;
		longExpMode = LongExposure.OFF;
		bracketEvs = null;
		bracketBiasEv = 0.0f;
	}

	public static void reset() {
		PhotoModeSession.setWorldFrozen(false);
		PhotoModeSession.setColorViewOverride(null);
		closeStackView();
		STACK.reset();
		restoreWindow();
		HdrCapture.release();
		phase = IDLE;
		grabQueued = false;
		chainReady = false;
		waitFrames = 0;
		windowResized = false;
		stacked = 0;
		ticksSteppedSoFar = 0;
		waitingForStep = false;
		longExpMode = LongExposure.OFF;
		bracketEvs = null;
		bracketBiasEv = 0.0f;
	}

	private static void closeStackView() {
		if (stackView != null) {
			try {
				stackView.close();
			} catch (Throwable ignored) {
			}
			stackView = null;
		}
	}

	private static void closeStackTex() {
		if (stackTex != null) {
			try {
				stackTex.close();
			} catch (Throwable ignored) {
			}
			stackTex = null;
		}
	}

	private static void announce(Minecraft mc, String text) {
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.literal(text));
		}
	}
}
