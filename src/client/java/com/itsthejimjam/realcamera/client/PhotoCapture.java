package com.itsthejimjam.realcamera.client;

import java.io.File;

import com.itsthejimjam.realcamera.PhotoMode;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.Util;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;

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
 * normal, then grab.
 *
 * <p><b>1.20.4 status:</b> {@code Screenshot.takeScreenshot} is synchronous here (no
 * downscale-factor param, no callback — unlike 26.2), so this box-filters the
 * supersampled readback down to output size by hand instead of asking Screenshot to do
 * it. A finished long-exposure stack is saved straight to disk rather than fed back
 * through the post-processing chain as a colour-input override — there's no DoF/grade
 * chain to feed it through yet (see {@link FilmParams}, {@link DofParams}); revisit once
 * that's ported.
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
	private static int resizeWaitFrames = 0;
	// Generous: a heavy shader pack can genuinely take a while to reallocate every G-buffer
	// at a big capture size. This is a last-resort escape hatch, not the primary fix — see
	// ensureWindowSized()'s comment for why the resize itself should now land in ~1 frame.
	private static final int MAX_RESIZE_WAIT_FRAMES = 200;

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
	/** Long edge this capture's long exposure renders at — fixed at the shutter press
	 *  (see {@link #longExposureEdge}), since the render size must not change mid-capture. */
	private static int longExpEdge = Integer.MAX_VALUE;
	private static final ExposureStack STACK = new ExposureStack();

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
				mc.player.displayClientMessage(Component.literal("Attach a lens to shoot"), true);
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
			if (longExpMode != LongExposure.OFF) {
				longExpEdge = longExposureEdge(Framing.outputWidth(), Framing.outputHeight());
			}
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
		resizeWaitFrames = 0;
		windowResized = false;
		stacked = 0;
		warmupLeft = 3;
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
		// (no supersample) — stepped down only if the heap can't hold the stack (see
		// longExposureEdge). Bracket frames are each saved independently, so they get the
		// normal full-quality path.
		if (longExpMode != LongExposure.OFF) {
			int w = Framing.outputWidth();
			int h = Framing.outputHeight();
			int edge = Math.max(w, h);
			if (edge > longExpEdge) {
				double s = (double) longExpEdge / edge;
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
		if (!w.isFullscreen()) {
			GLFW.glfwSetWindowSize(w.getWindow(), overrideWidth(), overrideHeight());
		}
		// 1.20.4's own GLFW framebuffer-resize callback (Window.onFramebufferResize) decides
		// whether to call Minecraft.resizeDisplay() by comparing Window.getWidth()/getHeight()
		// before and after the resize — but WindowSizeMixin spoofs those exact same accessors
		// to always report the override size while wantsBigFrame() is true, so that
		// before/after comparison never sees a change and the implicit callback never fires.
		// mainRenderTarget then never grows to the capture size and the shutter hangs forever
		// waiting for it (confirmed via javap on Window.onFramebufferResize's bytecode — it
		// re-reads getWidth()/getHeight(), not the raw framebufferWidth/Height fields, to
		// decide). Drive the resize directly instead; also required in fullscreen, where
		// there's no real window resize to ever trigger a callback at all.
		Minecraft.getInstance().resizeDisplay();
	}

	private static void restoreWindow() {
		if (savedWinW <= 0) {
			return;
		}
		Window w = Minecraft.getInstance().getWindow();
		if (!w.isFullscreen()) {
			GLFW.glfwSetWindowSize(w.getWindow(), savedWinW, savedWinH);
			// Same self-cancelling-spoof issue as ensureWindowSized() above, in reverse —
			// callers reset phase to IDLE before calling this, so getWidth()/getHeight() are
			// unspoofed here and resizeDisplay() correctly snaps mainRenderTarget back down.
			Minecraft.getInstance().resizeDisplay();
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
				if (resizeWaitFrames++ > MAX_RESIZE_WAIT_FRAMES) {
					PhotoMode.LOGGER.error(
							"[Photo Mode] capture resize timed out: mainTarget={}x{} wanted={}x{}",
							mainTarget.width, mainTarget.height, overrideWidth(), overrideHeight());
					announce(Minecraft.getInstance(), "Photo capture failed — resize timed out");
					reset();
				}
				return;
			}
			resizeWaitFrames = 0;
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
			if (!atSize) {
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
			int frameNo = bracketIdx + 1;
			float ev = bracketEvs[bracketIdx];
			int total = bracketEvs.length;
			int ss = grabDownscale();
			try {
				NativeImage image = Screenshot.takeScreenshot(mainTarget);
				// Downscale + encode off the render thread — see grabIfReady's comment.
				saveBracketFrame(image, ss, frameNo, total, ev);
			} catch (Throwable t) {
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
			if (stacked < subFrames) {
				try (NativeImage image = Screenshot.takeScreenshot(mainTarget)) {
					STACK.add(image);
				} catch (Throwable t) {
					PhotoMode.LOGGER.warn("[Photo Mode] stack readback failed: {}", t.toString());
					stacked = subFrames; // bail to develop
				} finally {
					stacked++;
				}
				return;
			}
			// stacking done -> develop. Drop the tick rate back to normal but leave the
			// world running (photo mode no longer freezes it).
			PhotoModeSession.setWorldTickRate(20.0f);
			// developStack() already saves the stacked image and calls finishCapture()
			// itself (phase -> IDLE) — it does NOT hand off to the DEVELOP/grabIfReady path
			// the way a single shot does (grabIfReady would just grab a second, unstacked,
			// raw frame on top of it). Setting phase = DEVELOP here used to immediately
			// clobber the IDLE finishCapture() just set, permanently stuck: overrideWidth/
			// Height() no longer matched the (now-restored) window once longExpMode was
			// reset, so grabIfReady()'s size check never passed again — the exact "photo
			// saves fine but the UI never comes back" freeze.
			developStack();
			return;
		}

		if (phase == DEVELOP) {
			grabIfReady(mainTarget);
		}
	}

	/** Write one bracket frame. All frames of a burst share {@link #bracketStamp} and are
	 *  tagged {@code BRACKET_<i>of<N>_<ev>} so they sort together for merging later. */
	private static void saveBracketFrame(NativeImage image, int downscaleFactor, int frameNo, int total, float ev) {
		bracketIdx++;
		String evLabel = String.format(java.util.Locale.ROOT, "%+.1fEV", ev).replace("+0.0EV", "0.0EV");
		String name = bracketStamp + "_BRACKET_" + frameNo + "of" + total + "_" + evLabel + ".png";
		Minecraft mc = Minecraft.getInstance();
		File dir = new File(mc.gameDirectory, "photos");
		File file = new File(dir, name);
		Util.ioPool().execute(() -> {
			try (NativeImage scaled = downscale(image, downscaleFactor)) {
				dir.mkdirs();
				scaled.writeToFile(file);
			} catch (Exception e) {
				PhotoMode.LOGGER.error("[Photo Mode] failed to save bracket frame", e);
			}
		});
	}

	/** No post-processing chain to feed the stack back through yet — save it straight to
	 *  disk instead (see the class doc). Does its own complete save + announce +
	 *  {@link #finishCapture()} — the caller must NOT also transition to DEVELOP /
	 *  call {@link #grabIfReady}, or it double-captures a second, unstacked raw frame
	 *  on top of the averaged one. */
	private static void developStack() {
		try {
			// STACK.finish() has to run here, not on the io pool: it resets the shared
			// STACK singleton's fields as its last step, and finishCapture() below (also
			// on this thread) touches those same fields — running finish() on a
			// background thread would race the two. It's one pass over the output
			// resolution, a one-time cost per long exposure, not per frame — smaller and
			// rarer than the downscale stall this was modeled on, so left synchronous.
			lastStackFrames = STACK.frames();
			NativeImage stackedImage = STACK.finish();
			Minecraft mc = Minecraft.getInstance();
			File dir = new File(mc.gameDirectory, "photos");
			File file = new File(dir, Util.getFilenameFormattedDateTime() + ".png");
			int w = stackedImage.getWidth();
			int h = stackedImage.getHeight();
			// Built now: finishCapture() below resets longExpMode before the save finishes.
			boolean capped = Math.max(Framing.outputWidth(), Framing.outputHeight()) > longExpEdge;
			String note = "  (" + LongExposure.OPTIONS[longExpMode] + " · " + lastStackFrames + " frames"
					+ (capped ? " · reduced to " + longExpEdge + "px, not enough memory" : "") + ")";
			Util.ioPool().execute(() -> {
				try (stackedImage) {
					dir.mkdirs();
					stackedImage.writeToFile(file);
					mc.execute(() -> announce(mc, "Saved  " + file.getName() + "   " + w + "×" + h + note));
				} catch (Exception e) {
					PhotoMode.LOGGER.error("[Photo Mode] failed to save long exposure", e);
					mc.execute(() -> announce(mc, "Photo save failed — see log"));
				}
			});
			finishCapture();
		} catch (Throwable t) {
			PhotoMode.LOGGER.error("[Photo Mode] developing the exposure stack failed", t);
			finishCapture();
		}
	}

	/** Box-filter downscale by an integer factor (supersampling). Factor 1 returns the
	 *  input image itself, unclosed. */
	private static NativeImage downscale(NativeImage src, int factor) {
		if (factor <= 1) {
			return src;
		}
		int outW = src.getWidth() / factor;
		int outH = src.getHeight() / factor;
		NativeImage out = new NativeImage(outW, outH, false);
		try {
			int area = factor * factor;
			for (int oy = 0; oy < outH; oy++) {
				for (int ox = 0; ox < outW; ox++) {
					long r = 0, g = 0, b = 0, a = 0;
					int sx0 = ox * factor;
					int sy0 = oy * factor;
					for (int dy = 0; dy < factor; dy++) {
						for (int dx = 0; dx < factor; dx++) {
							int p = src.getPixelRGBA(sx0 + dx, sy0 + dy);
							r += (p >> 16) & 0xFF;
							g += (p >> 8) & 0xFF;
							b += p & 0xFF;
							a += (p >>> 24) & 0xFF;
						}
					}
					int rgba = (int) (((a / area) << 24) | ((r / area) << 16) | ((g / area) << 8) | (b / area));
					out.setPixelRGBA(ox, oy, rgba);
				}
			}
		} finally {
			src.close();
		}
		return out;
	}

	/** Long edge for a long exposure of {@code w x h}: the full size when the Java heap has
	 *  room for the stack ({@link ExposureStack#BYTES_PER_PIXEL} per pixel, plus headroom for
	 *  the game), otherwise stepped down by quarters to no less than
	 *  {@link LongExposure#MIN_EDGE}. */
	private static int longExposureEdge(int w, int h) {
		Runtime rt = Runtime.getRuntime();
		long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
		long budget = free - 512L * 1024 * 1024;
		int full = Math.max(w, h);
		int edge = full;
		while (edge > LongExposure.MIN_EDGE) {
			double s = (double) edge / full;
			long pixels = (long) (w * s) * (long) (h * s);
			if (pixels * ExposureStack.BYTES_PER_PIXEL <= budget) {
				break;
			}
			edge = Math.max(LongExposure.MIN_EDGE, edge * 3 / 4);
		}
		if (edge < full) {
			PhotoMode.LOGGER.warn("[Photo Mode] long exposure reduced to {}px (free heap {} MB)",
					edge, free / (1024 * 1024));
		}
		return edge;
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

		// The GL readback itself has to happen here (needs the render thread's GL
		// context), but everything after it — the box-filter downscale (a plain Java
		// pixel loop; at 4K x SS2 that's tens of millions of iterations) and the PNG
		// encode/write — must NOT run on the render thread, or a capture visibly
		// freezes the game for several seconds. Hand the raw image to the io pool
		// immediately and do the rest there.
		NativeImage image = Screenshot.takeScreenshot(mainTarget);
		finishCapture();
		Minecraft mc = Minecraft.getInstance();
		File dir = new File(mc.gameDirectory, "photos");
		File file = new File(dir, Util.getFilenameFormattedDateTime() + ".png");
		Util.ioPool().execute(() -> {
			try (NativeImage scaled = downscale(image, ss)) {
				dir.mkdirs();
				scaled.writeToFile(file);
				mc.execute(() -> announce(mc, "Saved  " + file.getName() + "   " + outW + "×" + outH + modeNote));
			} catch (Exception e) {
				PhotoMode.LOGGER.error("[Photo Mode] failed to save photo", e);
				mc.execute(() -> announce(mc, "Photo save failed — see log"));
			}
		});
	}

	/** Tear the capture state down. */
	private static void finishCapture() {
		STACK.reset();
		// Clear phase before restoring the window — restoreWindow()'s shrink-resize needs
		// getWidth()/getHeight() unspoofed (see its comment) to snap mainRenderTarget back down.
		phase = IDLE;
		restoreWindow();
		grabQueued = false;
		longExpMode = LongExposure.OFF;
		bracketEvs = null;
		bracketBiasEv = 0.0f;
	}

	public static void reset() {
		if (phase == EXPOSING || phase == DEVELOP) {
			PhotoModeSession.setWorldTickRate(20.0f);
		}
		STACK.reset();
		phase = IDLE;
		restoreWindow();
		grabQueued = false;
		chainReady = false;
		waitFrames = 0;
		resizeWaitFrames = 0;
		windowResized = false;
		stacked = 0;
		longExpMode = LongExposure.OFF;
		bracketEvs = null;
		bracketBiasEv = 0.0f;
	}

	private static void announce(Minecraft mc, String text) {
		if (mc.player != null) {
			mc.player.displayClientMessage(Component.literal(text), true);
		}
	}
}
