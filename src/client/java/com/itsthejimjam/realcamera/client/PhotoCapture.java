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
 * state machine: boost the world tick rate to fast-forward it through the shutter's
 * worth of game time while stacking sub-frames on the CPU, drop the tick rate back to
 * normal, write the stacked image back into {@code minecraft:main} (via a colour-input
 * override on the post chain) so DoF / grade / grain still apply, then grab.
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
	private static float boostRate = 20.0f;
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
		boostRate = LongExposure.boostTickRate(sec, subFrames);
		phase = RESIZING;
		grabQueued = false;
		chainReady = false;
		waitFrames = 0;
		windowResized = false;
		stacked = 0;
		warmupLeft = 3;
		readbackInFlight = false;
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
		int ss = Framing.effectiveSupersample();
		return new int[] {Framing.outputWidth() * ss, Framing.outputHeight() * ss};
	}

	/** Downscale applied when grabbing (1 for long exposure — it renders at output size). */
	private static int grabDownscale() {
		return longExpMode != LongExposure.OFF ? 1 : Framing.effectiveSupersample();
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
				phase = EXPOSING;
				return;
			}
			if (longExpMode == LongExposure.OFF) {
				grabIfReady(mainTarget);
				return;
			}
			// begin the exposure: run the world through the shutter time
			STACK.begin(mainTarget.width, mainTarget.height);
			PhotoModeSession.setWorldFrozen(false);
			PhotoModeSession.setWorldTickRate(boostRate);
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
			// stacking done -> develop. Drop the tick rate back to normal but leave the
			// world running (photo mode no longer freezes it).
			PhotoModeSession.setWorldTickRate(20.0f);
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
		String evLabel = String.format(java.util.Locale.ROOT, "%+.1fEV", ev).replace("+0.0EV", "0.0EV");
		String name = bracketStamp + "_BRACKET_" + frameNo + "of" + total + "_" + evLabel + ".png";
		Minecraft mc = Minecraft.getInstance();
		File dir = new File(mc.gameDirectory, "photos");
		File file = new File(dir, name);
		Util.ioPool().execute(() -> {
			try (image) {
				dir.mkdirs();
				image.writeToFile(file);
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

		Screenshot.takeScreenshot(mainTarget, ss, image -> {
			finishCapture();
			try {
				Minecraft mc = Minecraft.getInstance();
				File dir = new File(mc.gameDirectory, "photos");
				File file = new File(dir, Util.getFilenameFormattedDateTime() + ".png");
				Util.ioPool().execute(() -> {
					try (image) {
						dir.mkdirs();
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

	/** Runs in the screenshot callback: tear the capture state down. */
	private static void finishCapture() {
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
		if (phase == EXPOSING || phase == DEVELOP) {
			PhotoModeSession.setWorldTickRate(20.0f);
		}
		PhotoModeSession.setColorViewOverride(null);
		closeStackView();
		STACK.reset();
		restoreWindow();
		phase = IDLE;
		grabQueued = false;
		chainReady = false;
		waitFrames = 0;
		windowResized = false;
		stacked = 0;
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
