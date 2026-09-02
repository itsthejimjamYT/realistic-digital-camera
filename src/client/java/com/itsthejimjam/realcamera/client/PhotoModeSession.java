package com.itsthejimjam.realcamera.client;

import com.itsthejimjam.realcamera.FilterSpec;
import com.itsthejimjam.realcamera.LensSpec;
import com.itsthejimjam.realcamera.PhotoMode;
import com.mojang.blaze3d.textures.GpuTextureView;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Owns the photo-mode state: entering detaches the camera and locks the HUD; exiting
 * puts everything back. The world keeps ticking while you shoot. All client-side;
 * singleplayer only for now.
 */
public final class PhotoModeSession {
	/** Camera fly speed, blocks per tick. Tuned by feel during testing. */
	private static final double HORIZONTAL_SPEED = 0.35;
	private static final double VERTICAL_SPEED = 0.30;

	/** Base FOV (zoom = 1, a "normal" lens), widest FOV, and longest zoom are all
	 *  user-configurable; defaults are 70°, 124° and 8×. Zoom multiplies in from the base. */
	private static float baseFov() {
		return com.itsthejimjam.realcamera.client.config.PhotoConfig.get().baseFov();
	}

	private static float maxRenderFov() {
		return com.itsthejimjam.realcamera.client.config.PhotoConfig.get().widestFov();
	}

	/** Lowest zoom factor — the point where the base FOV has opened out to the widest FOV. */
	private static float minZoom() {
		return baseFov() / maxRenderFov();
	}

	private static float maxZoom() {
		return com.itsthejimjam.realcamera.client.config.PhotoConfig.get().maxZoom();
	}

	private static final float ZOOM_STEP = 1.06f;

	/** How the viewpoint moves while in photo mode. */
	public enum Mode {
		/** Handheld camera: you walk your character around (survival physics), no flight. */
		CAMERA,
		/** Drone: free flight, character frozen in place — for aerial shots. */
		DRONE,
	}

	private static boolean active = false;
	private static Mode mode = Mode.CAMERA;
	private static FreeCameraEntity camera = null;
	private static CameraType savedCameraType = null;
	/** DRONE mode stashes the player's held items so the character isn't shown clutching
	 *  the drone while it's "flying". Restored on exit. */
	private static ItemStack stashMain = ItemStack.EMPTY;
	private static ItemStack stashOff = ItemStack.EMPTY;
	/** Which camera/drone item opened this session — used to colour the shutter sound. */
	private static Item deviceItem = PhotoMode.CREATIVE_CAMERA;
	private static float zoom = 1.0f;

	/** Zoom range imposed by an installed lens (null = no lens / use PhotoConfig). Equal
	 *  min == max means a prime — the zoom is locked. */
	private static Float lensZoomMin = null;
	private static Float lensZoomMax = null;
	private static String lensLabel = "";

	/** Active filter effect. When {@link #filtersFromPanel} the values come from the
	 *  panel's Filters cells (creative camera / drone); otherwise from the camera body's
	 *  installed filter. Wired to the shader in a later chunk. */
	private static float filterNd = 0.0f;
	private static float filterPolar = 0.0f;
	private static float filterMist = 0.0f;
	private static boolean filtersFromPanel = true;
	private static String filterLabel = "";

	/** f-stops in 1/3-stop steps, wide-open to stopped-down. */
	private static final float[] F_STOPS = {
			1.4f, 1.6f, 1.8f, 2.0f, 2.2f, 2.5f, 2.8f, 3.2f, 3.5f, 4.0f, 4.5f, 5.0f, 5.6f,
			6.3f, 7.1f, 8.0f, 9.0f, 10.0f, 11.0f, 13.0f, 14.0f, 16.0f, 18.0f, 20.0f, 22.0f};
	private static final int APERTURE_BASE = 6; // f/2.8
	private static int apertureIndex = APERTURE_BASE;
	/** Smallest allowed f-stop index — a slower lens can't open past its widest aperture. */
	private static int apertureFloorIndex = 0;
	/** The equipped lens, so the aperture floor can track focal length on a variable-
	 *  aperture zoom. Null for the lens-less creative camera / drone. */
	private static LensSpec activeLens = null;

	private static void setApertureFloor(float widestFNumber) {
		int floor = 0;
		float bestErr = Float.MAX_VALUE;
		for (int i = 0; i < F_STOPS.length; i++) {
			float err = Math.abs(F_STOPS[i] - widestFNumber);
			if (err < bestErr) {
				bestErr = err;
				floor = i;
			}
		}
		apertureFloorIndex = floor;
		if (apertureIndex < floor) {
			apertureIndex = floor;
		}
	}

	/** Re-clamp the aperture dial to the widest f-stop the equipped lens can give at the
	 *  current focal length (variable-aperture zooms only actually move). If you were
	 *  shooting wide open, the dial follows the lens both ways as you zoom. */
	private static void refreshApertureFloor() {
		if (activeLens == null) {
			return;
		}
		boolean wasWideOpen = apertureIndex == apertureFloorIndex;
		setApertureFloor(activeLens.widestApertureAt(focalLengthMm()));
		if (wasWideOpen && apertureIndex > apertureFloorIndex) {
			apertureIndex = apertureFloorIndex;
		}
	}

	public static float getAperture() {
		return F_STOPS[apertureIndex];
	}

	public static void cycleAperture() {
		stepAperture(1);
	}

	/** Step the f-stop: dir &gt; 0 stops down (higher f-number), dir &lt; 0 opens up. Clamps
	 *  at the ends (wide-open / fully stopped down) — no wrap-around. */
	public static void stepAperture(int dir) {
		apertureIndex = Mth.clamp(apertureIndex + dir, apertureFloorIndex, F_STOPS.length - 1);
	}

	// --- exposure triangle: shutter + ISO + aperture all shift brightness; a comp
	//     dial rebalances. Baseline f/2.8 · 1/1000 · ISO 100 (good midday). ---

	/** Shutter speed in SECONDS, 1/3-stop steps from 1/4000 up to a 30 s long exposure.
	 *  Longer = brighter (and, once motion blur lands, more streak). */
	private static final double[] SHUTTER_S = {
			1 / 8000.0, 1 / 6400.0, 1 / 5000.0,
			1 / 4000.0, 1 / 3200.0, 1 / 2500.0, 1 / 2000.0, 1 / 1600.0, 1 / 1250.0, 1 / 1000.0,
			1 / 800.0, 1 / 640.0, 1 / 500.0, 1 / 400.0, 1 / 320.0, 1 / 250.0, 1 / 200.0, 1 / 160.0,
			1 / 125.0, 1 / 100.0, 1 / 80.0, 1 / 60.0, 1 / 50.0, 1 / 40.0, 1 / 30.0, 1 / 25.0, 1 / 20.0,
			1 / 15.0, 1 / 13.0, 1 / 10.0, 1 / 8.0, 1 / 6.0, 1 / 5.0, 1 / 4.0, 1 / 3.0, 0.4, 0.5, 0.6,
			0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0};
	private static final int SHUTTER_BASE = 9; // 1/1000
	private static int shutterIndex = SHUTTER_BASE;

	/** Film speed in 1/3-stop steps (50 is an extended "pull" below base). Higher = brighter + grainier. */
	private static final int[] ISO = {
			50, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000,
			2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200};
	private static final int ISO_BASE = 1; // ISO 100
	private static int isoIndex = ISO_BASE;
	/** Auto ISO: the exposure driver is free to move {@link #isoIndex} (base..ceiling)
	 *  to finish an exposure the shutter/aperture couldn't. Works in any mode, M included. */
	private static boolean autoIso = false;
	private static final int ISO_AUTO_CEIL = 22;      // ISO 12800 — noise ceiling
	private static final int AUTO_SHUTTER_FLOOR = 21; // 1/60 s — with Auto ISO on, the driver
	                                                  //          raises ISO rather than going slower

	/** Exposure compensation in EV, clamped (does not wrap). Wide range so night long
	 *  exposures can be pulled back down. */
	private static float exposureComp = 0.0f;
	private static final float EV_STEP = 1.0f / 3.0f;
	private static final float EV_LIMIT = 5.0f;

	/** White balance dial, in Kelvin. Higher = warmer image (like a camera's WB). */
	private static final int[] WB_KELVIN = {
			2500, 2900, 3200, 3600, 4000, 4400, 4800, 5200, 5500, 5900, 6300,
			6800, 7300, 8000, 9000, 10000};
	private static final int WB_BASE = 8; // 5500 K, neutral
	private static int wbIndex = WB_BASE;

	/** Shutter speed in seconds. */
	public static double getShutterSeconds() {
		return SHUTTER_S[shutterIndex];
	}

	/** Human-readable shutter, e.g. {@code "1/1000"} or {@code "15\""}. */
	public static String getShutterLabel() {
		double s = SHUTTER_S[shutterIndex];
		if (s >= 1.0) {
			return (s == Math.rint(s) ? Integer.toString((int) s) : String.valueOf(s)) + "\"";
		}
		return "1/" + Math.round(1.0 / s);
	}

	public static int getIso() {
		return ISO[isoIndex];
	}

	public static boolean isoAuto() {
		return autoIso;
	}

	/** ISO for the readout — prefixed {@code A} when the camera is choosing it. */
	public static String isoDisplay() {
		return (autoIso ? "A" : "") + ISO[isoIndex];
	}

	public static float getExposureComp() {
		return exposureComp;
	}

	public static int getWhiteBalanceKelvin() {
		return WB_KELVIN[wbIndex];
	}

	/** White balance as a -1..+1 scalar for the shader (negative = cooler, positive = warmer). */
	public static float getWhiteBalance() {
		return (WB_KELVIN[wbIndex] - 5500) / 4500.0f;
	}

	public static void stepShutter(int dir) {
		// Negated so "up / forward / scroll-up" = a faster shutter (higher 1/x number),
		// matching how the aperture and ISO dials read. Clamps at the ends — no wrap.
		shutterIndex = Mth.clamp(shutterIndex - dir, 0, SHUTTER_S.length - 1);
	}

	public static void stepIso(int dir) {
		isoIndex = Mth.clamp(isoIndex + dir, 0, ISO.length - 1);
	}

	// --- ISO dial as shown in the panel: "AUTO" then the 1/3-stop values ---

	public static int isoModeIndex() {
		return autoIso ? 0 : isoIndex + 1;
	}

	public static void setIsoModeIndex(int i) {
		if (i <= 0) {
			autoIso = true;
		} else {
			autoIso = false;
			isoIndex = Mth.clamp(i - 1, 0, ISO.length - 1);
		}
	}

	public static void stepIsoMode(int dir) {
		// AUTO sits at index 0, then the manual values 1..ISO.length. Clamps — no wrap.
		setIsoModeIndex(Mth.clamp(isoModeIndex() + dir, 0, ISO.length));
	}

	public static void stepExposureComp(int dir) {
		exposureComp = Mth.clamp(exposureComp + dir * EV_STEP, -EV_LIMIT, EV_LIMIT);
	}

	public static void stepWhiteBalance(int dir) {
		wbIndex = Mth.clamp(wbIndex + dir, 0, WB_KELVIN.length - 1);
	}

	public static void resetAperture() {
		apertureIndex = APERTURE_BASE;
	}

	public static void resetShutter() {
		shutterIndex = SHUTTER_BASE;
	}

	public static void resetIso() {
		isoIndex = ISO_BASE;
		autoIso = false;
	}

	public static void resetExposureComp() {
		exposureComp = 0.0f;
	}

	public static void resetWhiteBalance() {
		wbIndex = WB_BASE;
	}

	// --- shooting mode (P/A/S/M) + metering -------------------------------------

	public static final String[] SHOOT_MODE_OPTIONS = {"P", "A", "S", "M"};
	private static final int SHOOT_M = 3;
	/** 0 P (auto both), 1 A (auto shutter), 2 S (auto aperture), 3 M (fully manual). */
	private static int shootMode = SHOOT_M;

	public static final String[] METERING_OPTIONS = {"Matrix", "Center", "Spot"};
	/** 0 matrix (whole frame), 1 centre-weighted, 2 spot (at the focus point). */
	private static int meteringMode = 1;

	/** P won't drift the aperture outside f/2.8 .. f/10. */
	private static final int AP_PROGRAM_LO = 6;
	private static final int AP_PROGRAM_HI = 17;

	public static int shootModeIndex() {
		return shootMode;
	}

	public static void setShootModeIndex(int i) {
		shootMode = Mth.clamp(i, 0, 3);
	}

	public static void stepShootMode(int dir) {
		shootMode = Math.floorMod(shootMode + dir, 4);
	}

	public static void resetShootMode() {
		shootMode = SHOOT_M;
	}

	public static String shootModeLabel() {
		return SHOOT_MODE_OPTIONS[shootMode];
	}

	/** Aperture is camera-controlled in P and S. */
	public static boolean autoAperture() {
		return shootMode == 0 || shootMode == 2;
	}

	/** Shutter is camera-controlled in P and A. */
	public static boolean autoShutter() {
		return shootMode == 0 || shootMode == 1;
	}

	public static int meteringIndex() {
		return meteringMode;
	}

	public static void setMeteringIndex(int i) {
		meteringMode = Mth.clamp(i, 0, 2);
	}

	public static void stepMetering(int dir) {
		meteringMode = Math.floorMod(meteringMode + dir, 3);
	}

	public static void resetMetering() {
		meteringMode = 1;
	}

	/** 0 matrix / 1 centre / 2 spot — read by {@link Histogram}. */
	public static int meteringMode() {
		return meteringMode;
	}

	/**
	 * Walk the camera-controlled dials toward a neutral meter reading (offset by the
	 * exposure-comp dial). Shutter/aperture are driven in P/A/S; ISO is driven when
	 * {@link #autoIso} — including in M, where it's the only free dial. Whatever the
	 * primary dial can't absorb (it clamped) rolls onto Auto ISO; if that's pegged too,
	 * the meter just reads over/under and the shooter needs an ND / more light.
	 */
	private static void driveAutoExposure() {
		boolean anyAuto = shootMode != SHOOT_M || autoIso;
		if (!anyAuto || PhotoCapture.wantsBigFrame() || !Histogram.meterReady()) {
			return;
		}
		float delta = Histogram.meterStops() - exposureComp;      // + = the shot is too bright
		if (Math.abs(delta) < 0.34f) {
			return;                                                // within ~1/3 stop: hold
		}
		int steps = Math.round(delta * 3.0f * 0.7f);              // 1/3-stop steps, eased in
		if (steps == 0) {
			steps = delta > 0 ? 1 : -1;
		}
		int shFloor = autoIso ? AUTO_SHUTTER_FLOOR : SHUTTER_S.length - 1;
		int absorbed = 0;

		if (autoShutter() && autoAperture()) {                    // P — split, favouring shutter
			int shWant = Math.round(steps * 0.6f);
			int sh0 = shutterIndex;
			setShutterIndex(Mth.clamp(sh0 - shWant, 0, shFloor));
			absorbed += sh0 - shutterIndex;                      // darken = lower index
			int ap0 = apertureIndex;
			apertureIndex = Mth.clamp(ap0 + (steps - shWant),
					Math.max(apertureFloorIndex, AP_PROGRAM_LO), AP_PROGRAM_HI);
			absorbed += apertureIndex - ap0;                     // darken = higher index
		} else if (autoShutter()) {                               // A
			int sh0 = shutterIndex;
			setShutterIndex(Mth.clamp(sh0 - steps, 0, shFloor));
			absorbed = sh0 - shutterIndex;
		} else if (autoAperture()) {                              // S
			int ap0 = apertureIndex;
			setApertureIndex(apertureIndex + steps);
			absorbed = apertureIndex - ap0;
		}

		int leftover = steps - absorbed;                          // primary dial clamped this off
		if (autoIso && leftover != 0) {
			// Auto ISO floors at base 100 — the ISO 50 pull stays a manual choice.
			isoIndex = Mth.clamp(isoIndex - leftover, ISO_BASE, ISO_AUTO_CEIL);
		}
	}

	// --- handheld camera shake (survival CAMERA BODY only) --------------------

	/** Smoothed shake magnitude 0..1; the render-frame yaw/pitch wobble it drives. */
	private static float shakeAmt = 0.0f;
	private static float shakeYaw = 0.0f;
	private static float shakePitch = 0.0f;
	/** The body was mounted on a placed tripod block — movement is locked and the
	 *  camera is dead steady (no handheld shake). */
	private static boolean tripodMounted = false;

	public static boolean tripodMounted() {
		return tripodMounted;
	}

	/** When entering from a placed tripod block: the world position the camera sits at
	 *  (the stand's head). Null when shooting handheld (camera stays at the player). */
	private static Vec3 tripodAnchor = null;
	/** The tripod's (LOWER half) block position when shooting from one — for the loadout
	 *  menu and mid-session lens reloads. Null when handheld. */
	private static net.minecraft.core.BlockPos tripodPos = null;

	public static void setTripodAnchor(Vec3 v) {
		tripodAnchor = v;
	}

	public static void setTripodPos(net.minecraft.core.BlockPos p) {
		tripodPos = p == null ? null : p.immutable();
	}

	public static Vec3 tripodAnchor() {
		return active ? tripodAnchor : null;
	}

	/** True while photo mode is running from a placed tripod. The tripod's block-entity
	 *  renderer reads this and draws nothing, so the stand + camera stay out of frame. */
	public static boolean shootingFromTripod() {
		return active && tripodMounted && tripodAnchor != null;
	}

	/** A handheld survival body session (whatever its support state). */
	private static boolean bodyShooting() {
		return active && mode == Mode.CAMERA && deviceItem == PhotoMode.CAMERA_BODY;
	}

	/** True when the handheld wobble should be applied to the camera this frame. */
	public static boolean shakeActive() {
		return bodyShooting() && !tripodMounted;
	}

	private static boolean braced() {
		return Minecraft.getInstance().options.keyShift.isDown();
	}

	/** Small deterministic per-sub-frame hash [0,1) — decorrelates the shader pack's
	 *  dither across the motion-blur stack so it averages out instead of banding. */
	private static float hash1(int n) {
		n = (n ^ 61) ^ (n >>> 16);
		n += n << 3;
		n ^= n >>> 4;
		n *= 0x27d4eb2d;
		n ^= n >>> 15;
		return (n & 0xffffff) / (float) 0x1000000;
	}

	/** How far the shutter sits below the 1/focal reciprocal rule, in stops (0 if at or
	 *  above it). Drives both the shake amplitude and the motion-blur sweep. */
	private static float shakeStopsBelow() {
		double sec = getShutterSeconds();
		float focal = Math.max(8.0f, focalLengthMm());
		return (float) Mth.clamp(Math.log(sec * focal) / Math.log(2.0), 0.0, 6.0);
	}

	/** Target shake, before smoothing: grows as the shutter drops below the 1/focal
	 *  reciprocal rule and as focal length climbs. Zero on a tripod; ~halved braced. */
	private static float shakeTarget() {
		if (!shakeActive()) {
			return 0.0f;
		}
		float amt = shakeStopsBelow() / 6.0f;
		return braced() ? amt * 0.45f : amt;
	}

	/** This shot is a handheld camera-body frame slow enough to shake — the capture
	 *  switches to the multi-frame path so the wobble becomes real motion blur. */
	public static boolean handheldShakeShot() {
		return shakeTarget() > 0.0f;
	}

	/** Advance the shake model. Called each render frame from {@code CameraShakeMixin}. */
	public static void tickShake() {
		shakeAmt += (shakeTarget() - shakeAmt) * 0.14f;

		if (PhotoCapture.isLongExposureStacking() && shakeTarget() > 0.0f) {
			// Sweep the camera along a tilted axis across the exposure so the stacked
			// frames integrate a directional smear that lengthens with slowness, plus a
			// tiny per-sub-frame random nudge so the shader dither averages smooth.
			float p = PhotoCapture.exposureProgress01();          // 0..1 across the stack
			float mag = shakeTarget();
			float sweep = mag * (0.4f + 0.5f * shakeStopsBelow());  // total degrees
			float off = (p - 0.5f) * sweep;
			int f = PhotoCapture.stackedFrames();
			float jit = 0.04f + mag * 0.12f;
			shakeYaw = off * 0.93f + (hash1(f * 2 + 1) - 0.5f) * jit;
			shakePitch = off * 0.36f + (hash1(f * 2 + 7) - 0.5f) * jit;
			return;
		}
		if (PhotoCapture.wantsBigFrame()) {
			return;   // RESIZING / DEVELOP / non-blur EXPOSING: hold the framing
		}
		double t = System.nanoTime() * 1.0e-9;
		float a = shakeAmt * 0.8f;   // peak ~0.85 deg
		shakeYaw = a * (float) (0.55 * Math.sin(t * 1.7)
				+ 0.22 * Math.sin(t * 4.3 + 1.1) + 0.30 * Math.sin(t * 0.6 + 2.0));
		shakePitch = a * (float) (0.50 * Math.sin(t * 2.1 + 0.7)
				+ 0.20 * Math.sin(t * 5.1 + 2.3) + 0.28 * Math.sin(t * 0.5 + 0.3));
	}

	/** Shutter time at/above which a capture switches to the multi-frame motion-blur
	 *  path. A steady camera uses the fixed world-motion threshold (1/40 s); a handheld
	 *  camera body uses the lens's 1/focal reciprocal rule (a stop more lenient when
	 *  braced), so a long lens starts blurring far sooner. */
	public static double motionBlurTriggerSeconds() {
		double worldMotion = 1.0 / 40.0;
		if (!shakeActive()) {
			return worldMotion;
		}
		double recip = 1.0 / Math.max(8.0f, focalLengthMm());
		if (braced()) {
			recip *= 2.0;
		}
		return Math.min(worldMotion, recip);
	}

	public static boolean willLongExpose() {
		return getShutterSeconds() >= motionBlurTriggerSeconds();
	}

	public static float shakeYawDeg() {
		return shakeYaw;
	}

	public static float shakePitchDeg() {
		return shakePitch;
	}

	/** Shake magnitude 0..1 (for HUD state colour). */
	public static float handheldShake01() {
		return shakeAmt;
	}

	/** Directional-smear amount for {@code blit.fsh} — only for a single-frame capture
	 *  (a long exposure does real multi-frame motion blur instead, so no fake smear). */
	public static float captureShakeBlur01() {
		if (!PhotoCapture.wantsBigFrame() || PhotoCapture.isLongExposureCapture()) {
			return 0.0f;
		}
		return Math.min(1.0f, shakeAmt * 1.4f);
	}

	/** Support state for the HUD: "" for non-body cameras, else TRIPOD/BRACED/HANDHELD. */
	public static String shakeState() {
		if (!bodyShooting()) {
			return "";
		}
		if (tripodMounted) {
			return "TRIPOD";
		}
		return braced() ? "BRACED" : "HANDHELD";
	}

	/** Film-look preset ("recipe") index; 0 = Standard (no grading). */
	private static int recipeIndex = 0;

	public static int getRecipeIndex() {
		return recipeIndex;
	}

	public static String getRecipeName() {
		return FilmParams.recipeName(recipeIndex);
	}

	public static void stepRecipe(int dir) {
		// Negated so scrolling down walks toward Standard / the built-ins and up toward
		// the custom slots, matching the other dials.
		recipeIndex = Math.floorMod(recipeIndex - dir, FilmParams.RECIPE_COUNT);
	}

	public static void resetRecipe() {
		recipeIndex = 0;
	}

	/** Global recipe intensity (wet/dry), 0 = off, 1 = as designed, up to 1.5 = punchier. */
	private static final float RECIPE_STRENGTH_BASE = 1.0f;
	private static float recipeStrength = RECIPE_STRENGTH_BASE;

	public static float getRecipeStrength() {
		return recipeStrength;
	}

	public static void stepRecipeStrength(int dir) {
		recipeStrength = Mth.clamp(recipeStrength + dir * 0.1f, 0.0f, 1.5f);
	}

	public static void resetRecipeStrength() {
		recipeStrength = RECIPE_STRENGTH_BASE;
	}

	/** Reset the photographic controls (not the output framing) to their defaults. */
	public static void resetPhotoSettings() {
		apertureIndex = APERTURE_BASE;
		shutterIndex = SHUTTER_BASE;
		isoIndex = ISO_BASE;
		autoIso = false;
		exposureComp = 0.0f;
		wbIndex = WB_BASE;
		recipeIndex = 0;
		recipeStrength = RECIPE_STRENGTH_BASE;
		shootMode = SHOOT_M;
		meteringMode = 1;
	}

	// --- option lists + index accessors, for the settings panel's pick-a-value menus ---

	private static final int EXPOSURE_STEPS = Math.round(2 * EV_LIMIT / EV_STEP) + 1;
	private static final int STRENGTH_STEPS = 16; // 0 % .. 150 % by 10

	public static final String[] APERTURE_OPTIONS = buildStrings(F_STOPS.length, i -> "f/" + fmtF(F_STOPS[i]));
	public static final String[] SHUTTER_OPTIONS = buildStrings(SHUTTER_S.length, PhotoModeSession::shutterLabelAt);
	public static final String[] ISO_OPTIONS = buildStrings(ISO.length, i -> Integer.toString(ISO[i]));
	/** ISO options for the panel: {@code AUTO} then every 1/3-stop value. */
	public static final String[] ISO_MODE_OPTIONS =
			buildStrings(ISO.length + 1, i -> i == 0 ? "AUTO" : Integer.toString(ISO[i - 1]));
	public static final String[] WB_OPTIONS = buildStrings(WB_KELVIN.length, i -> WB_KELVIN[i] + "K");
	public static final String[] EXPOSURE_OPTIONS = buildStrings(EXPOSURE_STEPS, PhotoModeSession::exposureLabelAt);
	public static final String[] STRENGTH_OPTIONS = buildStrings(STRENGTH_STEPS, i -> (i * 10) + "%");

	private interface IntToStr {
		String get(int i);
	}

	private static String[] buildStrings(int n, IntToStr fn) {
		String[] out = new String[n];
		for (int i = 0; i < n; i++) {
			out[i] = fn.get(i);
		}
		return out;
	}

	private static String fmtF(float f) {
		return f == Math.rint(f) ? Integer.toString((int) f) : Float.toString(f);
	}

	private static String shutterLabelAt(int i) {
		double s = SHUTTER_S[i];
		if (s >= 1.0) {
			return (s == Math.rint(s) ? Integer.toString((int) s) : String.valueOf(s)) + "\"";
		}
		return "1/" + Math.round(1.0 / s);
	}

	private static String exposureLabelAt(int i) {
		float ev = -EV_LIMIT + i * EV_STEP;
		return Math.abs(ev) < 0.05f ? "0.0 EV" : String.format(java.util.Locale.ROOT, "%+.1f EV", ev);
	}

	public static int apertureIndex() {
		return apertureIndex;
	}

	public static void setApertureIndex(int i) {
		apertureIndex = Mth.clamp(i, apertureFloorIndex, F_STOPS.length - 1);
	}

	public static int shutterIndex() {
		return shutterIndex;
	}

	public static void setShutterIndex(int i) {
		shutterIndex = Mth.clamp(i, 0, SHUTTER_S.length - 1);
	}

	public static int isoIndex() {
		return isoIndex;
	}

	public static void setIsoIndex(int i) {
		isoIndex = Mth.clamp(i, 0, ISO.length - 1);
	}

	public static int whiteBalanceIndex() {
		return wbIndex;
	}

	public static void setWhiteBalanceIndex(int i) {
		wbIndex = Mth.clamp(i, 0, WB_KELVIN.length - 1);
	}

	public static int exposureIndex() {
		return Math.round((exposureComp + EV_LIMIT) / EV_STEP);
	}

	public static void setExposureIndex(int i) {
		exposureComp = Mth.clamp(-EV_LIMIT + Mth.clamp(i, 0, EXPOSURE_STEPS - 1) * EV_STEP, -EV_LIMIT, EV_LIMIT);
	}

	public static void setRecipeIndex(int i) {
		recipeIndex = Math.floorMod(i, FilmParams.RECIPE_COUNT);
	}

	public static int strengthIndex() {
		return Mth.clamp(Math.round(recipeStrength / 0.1f), 0, STRENGTH_STEPS - 1);
	}

	public static void setStrengthIndex(int i) {
		recipeStrength = Mth.clamp(Mth.clamp(i, 0, STRENGTH_STEPS - 1) * 0.1f, 0.0f, 1.5f);
	}

	// --- focus point (screen UV, origin bottom-left to match the depth sampler) ---
	private static float focusU = 0.5f;
	private static float focusV = 0.5f;
	private static boolean focusPicking = false;
	private static float cursorU = 0.5f;
	private static float cursorV = 0.5f;
	private static final float CURSOR_SENS = 0.0016f;

	public static float getFocusU() {
		return focusU;
	}

	public static float getFocusV() {
		return focusV;
	}

	public static boolean isFocusPicking() {
		return focusPicking;
	}

	public static float getCursorU() {
		return cursorU;
	}

	public static float getCursorV() {
		return cursorV;
	}

	/** Enter focus-pick mode: mouse now moves a reticle instead of the camera. */
	public static void beginFocusPick() {
		if (!active) {
			return;
		}
		focusPicking = true;
		cursorU = focusU;
		cursorV = focusV;
	}

	public static void cancelFocusPick() {
		focusPicking = false;
	}

	/** Commit the reticle position as the focus point and leave focus-pick mode. */
	public static void commitFocus() {
		focusU = Mth.clamp(cursorU, 0.0f, 1.0f);
		focusV = Mth.clamp(cursorV, 0.0f, 1.0f);
		focusPicking = false;
		CameraSounds.focusBeep();
	}

	/** Set the focus point directly from a screen UV (origin bottom-left). */
	public static void setFocus(float u, float v) {
		focusU = Mth.clamp(u, 0.0f, 1.0f);
		focusV = Mth.clamp(v, 0.0f, 1.0f);
		CameraSounds.focusBeep();
	}

	/** Called from the turn redirect while picking: mouse delta moves the reticle. */
	public static void moveCursor(double dx, double dy) {
		cursorU = Mth.clamp(cursorU + (float) dx * CURSOR_SENS, 0.0f, 1.0f);
		cursorV = Mth.clamp(cursorV - (float) dy * CURSOR_SENS, 0.0f, 1.0f);
	}

	/** Set briefly by the shader-pack path so the depth-swap mixin can substitute the scene depth. */
	private static volatile GpuTextureView depthViewOverride = null;

	/** Diagnostics: log the shader-pack path state and the depth-view swap once per session. */
	public static boolean shaderPathLogged = false;
	public static boolean depthSwapLogged = false;
	public static boolean afterLevelLogged = false;

	public static GpuTextureView depthViewOverride() {
		return depthViewOverride;
	}

	public static void setDepthViewOverride(GpuTextureView view) {
		depthViewOverride = view;
	}

	/** Set by {@link PhotoCapture} during a long exposure: the post chain's colour input
	 *  ({@code minecraft:main}) is replaced with the stacked image so DoF/grade still apply. */
	private static volatile GpuTextureView colorViewOverride = null;

	public static GpuTextureView colorViewOverride() {
		return colorViewOverride;
	}

	public static void setColorViewOverride(GpuTextureView view) {
		colorViewOverride = view;
	}

	/** Freeze / unfreeze the integrated server world (used by long exposure). */
	public static void setWorldFrozen(boolean frozen) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getSingleplayerServer() != null) {
			mc.getSingleplayerServer().execute(
					() -> mc.getSingleplayerServer().tickRateManager().setFrozen(frozen));
		}
	}

	/** Set the server tick rate (used to fast-forward the world through a long exposure). */
	public static void setWorldTickRate(float rate) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getSingleplayerServer() != null) {
			mc.getSingleplayerServer().execute(
					() -> mc.getSingleplayerServer().tickRateManager().setTickRate(rate));
		}
	}

	private PhotoModeSession() {
	}

	public static boolean isActive() {
		return active;
	}

	public static FreeCameraEntity getCamera() {
		return camera;
	}

	public static float getZoom() {
		return zoom;
	}

	public static Mode mode() {
		return mode;
	}

	public static boolean isDrone() {
		return active && mode == Mode.DRONE;
	}

	public static boolean isCameraMode() {
		return active && mode == Mode.CAMERA;
	}

	public static Item deviceItem() {
		return deviceItem;
	}

	public static float filterNd() {
		return filterNd;
	}

	public static float filterPolar() {
		return filterPolar;
	}

	public static float filterMist() {
		return filterMist;
	}

	public static boolean filtersFromPanel() {
		return filtersFromPanel;
	}

	// --- panel Filters cells (creative camera / drone only; camera body uses its item) ---

	public static final String[] FILTER_ND_OPTIONS = {"Off", "ND8", "ND64", "ND1000"};
	private static final float[] FILTER_ND_STOPS = {0.0f, 3.0f, 6.0f, 10.0f};
	public static final String[] PERCENT_OPTIONS = {
			"0%", "10%", "20%", "30%", "40%", "50%", "60%", "70%", "80%", "90%", "100%"};

	public static int filterNdIndex() {
		for (int i = FILTER_ND_STOPS.length - 1; i >= 0; i--) {
			if (filterNd >= FILTER_ND_STOPS[i] - 0.01f) {
				return i;
			}
		}
		return 0;
	}

	public static void setFilterNdIndex(int i) {
		if (filtersFromPanel) {
			filterNd = FILTER_ND_STOPS[Mth.clamp(i, 0, FILTER_ND_STOPS.length - 1)];
		}
	}

	public static void stepFilterNd(int dir) {
		setFilterNdIndex(filterNdIndex() + dir);
	}

	public static void resetFilterNd() {
		if (filtersFromPanel) {
			filterNd = 0.0f;
		}
	}

	public static int filterPolarIndex() {
		return Mth.clamp(Math.round(filterPolar * 10.0f), 0, 10);
	}

	public static void setFilterPolarIndex(int i) {
		if (filtersFromPanel) {
			filterPolar = Mth.clamp(i, 0, 10) / 10.0f;
		}
	}

	public static void stepFilterPolar(int dir) {
		setFilterPolarIndex(filterPolarIndex() + dir);
	}

	public static void resetFilterPolar() {
		if (filtersFromPanel) {
			filterPolar = 0.0f;
		}
	}

	public static int filterMistIndex() {
		return Mth.clamp(Math.round(filterMist * 10.0f), 0, 10);
	}

	public static void setFilterMistIndex(int i) {
		if (filtersFromPanel) {
			filterMist = Mth.clamp(i, 0, 10) / 10.0f;
		}
	}

	public static void stepFilterMist(int dir) {
		setFilterMistIndex(filterMistIndex() + dir);
	}

	public static void resetFilterMist() {
		if (filtersFromPanel) {
			filterMist = 0.0f;
		}
	}

	public static String lensLabel() {
		return lensLabel;
	}

	public static String filterLabel() {
		return filterLabel;
	}

	/** A camera body was activated with no lens installed — the finder shows white and
	 *  the shutter is disabled, exactly like a real body with no glass on the mount. */
	public static boolean noLensAttached() {
		return active && deviceItem == PhotoMode.CAMERA_BODY && lensZoomMin == null;
	}

	/**
	 * Enter (or exit) photo mode. {@code lens} null ⇒ continuous zoom (creative camera);
	 * otherwise the zoom clamps to the lens's focal range. {@code filter} null ⇒ filters
	 * are panel-driven (creative / drone); otherwise the installed filter's fixed effect.
	 */
	public static void toggle(Mode m, Item device, LensSpec lens, FilterSpec filter) {
		toggle(m, device, lens, filter, false);
	}

	public static void toggle(Mode m, Item device, LensSpec lens, FilterSpec filter, boolean tripod) {
		if (active) {
			exit();
			return;
		}
		deviceItem = device;
		tripodMounted = tripod && device == PhotoMode.CAMERA_BODY;
		if (!tripodMounted) {
			tripodAnchor = null;
			tripodPos = null;
		}
		applyLensAndFilter(m, lens, filter);
		enter(m);
	}

	/** Re-read the held camera body's loadout mid-session — called (a few ticks after,
	 *  to let the edit sync) when the loadout menu closes, so slotting a lens without
	 *  leaving the finder actually takes effect. */
	public static void queueReloadLoadout() {
		reloadPending = 5;
	}

	private static int reloadPending = 0;

	private static void reloadLoadout() {
		if (!active || deviceItem != PhotoMode.CAMERA_BODY) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		ItemStack body;
		if (tripodPos != null) {
			body = mc.level.getBlockEntity(tripodPos) instanceof com.itsthejimjam.realcamera.block.TripodBlockEntity be
					? be.getCamera() : ItemStack.EMPTY;
		} else {
			body = mc.player.getMainHandItem();
		}
		if (!PhotoMode.isCameraBody(body)) {
			return;
		}
		boolean wasNoLens = noLensAttached();
		ItemStack lensStack = PhotoMode.loadoutLens(body);
		ItemStack filterStack = PhotoMode.loadoutFilter(body);
		applyLensAndFilter(mode,
				lensStack.isEmpty() ? null : PhotoMode.lensSpec(lensStack.getItem()),
				filterStack.isEmpty() ? FilterSpec.NONE : PhotoMode.filterSpec(filterStack.getItem()));
		if (wasNoLens && lensZoomMin != null) {
			zoom = lensZoomMin;   // just got glass — snap the zoom into the lens's range
		}
	}

	private static void applyLensAndFilter(Mode m, LensSpec lens, FilterSpec filter) {
		if (lens != null) {
			float bf = baseFov();
			lensZoomMin = LensSpec.zoomForFocal(lens.focalMin(), bf);
			lensZoomMax = LensSpec.zoomForFocal(lens.focalMax(), bf);
			lensLabel = lens.rangeLabel();
			activeLens = lens;
			// A lens can't open wider than its widest aperture — clamp the f-stop dial
			// (variable-aperture zooms recompute this as the focal length changes).
			setApertureFloor(lens.widestAperture());
		} else {
			lensZoomMin = null;
			lensZoomMax = null;
			lensLabel = "";
			activeLens = null;
			setApertureFloor(1.4f);
		}

		if (m == Mode.DRONE) {
			// The drone is a bare aerial camera — no filters, no Rig panel.
			filtersFromPanel = false;
			filterNd = filterPolar = filterMist = 0.0f;
			filterLabel = "";
		} else if (filter != null && !filter.isEmpty()) {
			filtersFromPanel = false;
			filterNd = filter.ndStops();
			filterPolar = filter.polarizer();
			filterMist = filter.mist();
			filterLabel = filter.ndStops() > 0 ? "ND"
					: filter.polarizer() > 0 ? "Polarizer" : "Mist";
		} else if (filter != null) {
			filtersFromPanel = false;
			filterNd = filterPolar = filterMist = 0.0f;
			filterLabel = "";
		} else {
			filtersFromPanel = true;
			filterLabel = "";
			// panel filter values persist like the other panel settings — leave as-is
		}
	}

	public static void enter(Mode m) {
		mode = m;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		if (!mc.hasSingleplayerServer()) {
			mc.player.sendOverlayMessage(Component.literal("Photo mode is singleplayer-only for now"));
			return;
		}

		zoom = lensZoomMin != null ? lensZoomMin : 1.0f;
		// Photo settings (exposure, recipe, framing, aids) deliberately persist between
		// sessions — set up a shot, leave to reposition, come back to the same look.
		// "RESET ALL SETTINGS" in the panel clears them on demand.
		shaderPathLogged = false;
		depthSwapLogged = false;
		afterLevelLogged = false;
		ShaderPackCompat.resetDebug();
		ShaderPackCompat.resetActiveLog();
		focusPicking = false;

		if (mode == Mode.DRONE) {
			setDroneActive(true);
		} else {
			// Handheld: the player IS the camera and walks around normally.
			camera = null;
		}

		savedCameraType = mc.options.getCameraType();
		// Both modes use a first-person camera type: it keeps third-party third-person
		// camera mods dormant so they don't fight our view control. In DRONE
		// mode DroneCameraMixin moves the camera onto the drone and CameraDetachedMixin
		// forces the "detached" flag so the frozen character still renders in frame.
		mc.options.setCameraType(CameraType.FIRST_PERSON);
		// Vanilla hotbar/crosshair/effects are suppressed by HudSuppressMixin while active;
		// the framing overlay is drawn by PhotoOverlay.

		// The world keeps ticking while you shoot — mobs move, water flows, time passes.
		// (Long exposure still briefly boosts the tick rate to fast-forward the shutter.)
		active = true;
		wasUseKeyDown = mc.options.keyUse.isDown();
		mc.player.sendOverlayMessage(Component.literal(mode == Mode.DRONE
				? "Drone  ·  fly to compose  ·  scroll zoom  ·  right-click to exit"
				: "Camera  ·  walk to compose  ·  scroll zoom  ·  right-click to exit"));
		PhotoMode.LOGGER.info("[Photo Mode] entered ({})", mode);
	}

	/** Spin up / tear down the free-flying drone camera. Used by {@link #enter} and by
	 *  the creative camera's live Walk⇄Fly toggle. */
	private static void setDroneActive(boolean on) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		if (on) {
			if (camera != null) {
				return;
			}
			// Free-flying camera. The player stays the camera entity (so the character
			// keeps rendering); DroneCameraMixin moves the view onto this drone. The held
			// items are hidden — the drone is "in the air", not in hand.
			camera = new FreeCameraEntity(mc.level);
			camera.copyFrom(mc.player);
			Vec3 eye = mc.player.getEyePosition();
			Vec3 back = mc.player.getLookAngle().scale(-2.6);
			camera.placeAt(eye.x + back.x, eye.y + back.y + 0.6, eye.z + back.z,
					mc.player.getYRot(), mc.player.getXRot());
			stashMain = mc.player.getItemInHand(InteractionHand.MAIN_HAND).copy();
			stashOff = mc.player.getItemInHand(InteractionHand.OFF_HAND).copy();
			mc.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			mc.player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
			mode = Mode.DRONE;
		} else {
			camera = null;
			restoreHeldItems(mc);
			mode = Mode.CAMERA;
		}
	}

	/** Creative camera only: swap between walking the character and flying a drone camera,
	 *  without leaving photo mode. */
	public static void toggleMovement() {
		if (!active || deviceItem != PhotoMode.CREATIVE_CAMERA) {
			return;
		}
		boolean toFly = mode != Mode.DRONE;
		setDroneActive(toFly);
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.literal(toFly ? "Fly" : "Walk"));
		}
	}

	/** Panel cell: 0 = Walk, 1 = Fly. */
	public static final String[] MOVEMENT_OPTIONS = {"Walk", "Fly"};

	public static int movementIndex() {
		return mode == Mode.DRONE ? 1 : 0;
	}

	public static void setMovementIndex(int i) {
		boolean wantFly = i == 1;
		if (active && deviceItem == PhotoMode.CREATIVE_CAMERA && wantFly != (mode == Mode.DRONE)) {
			setDroneActive(wantFly);
		}
	}

	public static void stepMovement(int dir) {
		setMovementIndex(movementIndex() == 0 ? 1 : 0);
	}

	public static void resetMovement() {
		setMovementIndex(0);
	}

	/** True when the panel should show a movement (Walk/Fly) cell. */
	public static boolean movementToggleAvailable() {
		return active && deviceItem == PhotoMode.CREATIVE_CAMERA;
	}

	/** Put the drone-stashed held items back on the player. */
	private static void restoreHeldItems(Minecraft mc) {
		if (stashMain.isEmpty() && stashOff.isEmpty()) {
			return;
		}
		if (mc.player != null) {
			mc.player.setItemInHand(InteractionHand.MAIN_HAND, stashMain);
			mc.player.setItemInHand(InteractionHand.OFF_HAND, stashOff);
		}
		stashMain = ItemStack.EMPTY;
		stashOff = ItemStack.EMPTY;
	}

	public static void exit() {
		Minecraft mc = Minecraft.getInstance();
		active = false;
		tripodMounted = false;
		tripodAnchor = null;
		tripodPos = null;
		shakeAmt = shakeYaw = shakePitch = 0.0f;
		restoreHeldItems(mc);

		if (mc.player != null) {
			mc.setCameraEntity(mc.player);
			mc.player.input = new KeyboardInput(mc.options);
		} else {
			mc.setCameraEntity(null);
		}
		if (savedCameraType != null) {
			mc.options.setCameraType(savedCameraType);
			savedCameraType = null;
		}
		zoom = 1.0f;
		PhotoCapture.reset();

		setFrozen(mc, false); // safety: clear any freeze a capture left behind
		camera = null;
		CustomRecipes.save();
		PhotoMode.LOGGER.info("[Photo Mode] exited");
	}

	/** Lowest / highest zoom right now — the installed lens's range if there is one,
	 *  otherwise the configured range. A prime lens returns min == max (locked). */
	private static float zoomFloor() {
		return lensZoomMin != null ? lensZoomMin : minZoom();
	}

	private static float zoomCeil() {
		return lensZoomMax != null ? lensZoomMax : maxZoom();
	}

	/** Scroll wheel while in photo mode: dir > 0 zooms in, dir < 0 zooms out. */
	public static void adjustZoom(double dir) {
		if (!active || noLensAttached()) {
			return;   // no glass on the mount — nothing to zoom
		}
		float lo = zoomFloor();
		float hi = zoomCeil();
		float before = zoom;
		if (dir > 0) {
			zoom = Math.min(hi, zoom * ZOOM_STEP);
		} else if (dir < 0) {
			zoom = Math.max(lo, zoom / ZOOM_STEP);
		}
		if (zoom != before) {
			float pos01 = hi > lo ? (zoom - lo) / (hi - lo) : 0.5f;
			CameraSounds.zoomTick(pos01);
			refreshApertureFloor();   // variable-aperture zoom: widest f-stop tracks focal length
		}
		// Live FOV / zoom readout is drawn by PhotoOverlay, so no action-bar spam here.
	}

	/** Applied by the Camera mixin. In photo mode the render FOV is our base lens FOV
	 *  divided by the current zoom — the game's FOV setting is deliberately ignored. */
	public static float applyZoomToFov(float gameFov) {
		return active ? renderFov() : gameFov;
	}

	/** Effective vertical FOV of the lens (base / zoom, capped). Aspect-independent —
	 *  this is what focal length, DoF and the readout are derived from. */
	public static float effectiveFov() {
		float f = baseFov() / Mth.clamp(zoom, zoomFloor(), zoomCeil());
		// The widest-FOV cap is a config guard for the lens-less creative camera; a real
		// ultra-wide lens (14 mm ≈ 81°) stays under it anyway.
		return lensZoomMin != null ? f : Math.min(maxRenderFov(), f);
	}

	/** Vertical FOV to actually render at. Same as {@link #effectiveFov()} in the live
	 *  preview and for capture aspects up to the window's. When a capture's target aspect
	 *  is WIDER than the window, Minecraft would keep the vertical FOV and widen the
	 *  horizontal field to fill it — so the saved photo would show more than the
	 *  letterboxed preview did. Shrink the vertical FOV instead, so the capture frames
	 *  exactly the preview crop. */
	private static boolean renderFovLogged = false;

	public static float renderFov() {
		float vfov = effectiveFov();
		if (!PhotoCapture.wantsBigFrame()) {
			renderFovLogged = false;
			return vfov;
		}
		int mw = PhotoCapture.savedWindowWidth();
		int mh = PhotoCapture.savedWindowHeight();
		if (mw <= 0 || mh <= 0) {
			return vfov;
		}
		double monitorAspect = (double) mw / (double) mh;
		double targetAspect = Framing.ratio();
		float out = vfov;
		if (targetAspect > monitorAspect) {
			double tanHalf = Math.tan(Math.toRadians(vfov * 0.5)) * (monitorAspect / targetAspect);
			out = (float) Math.toDegrees(2.0 * Math.atan(tanHalf));
		}
		if (!renderFovLogged) {
			renderFovLogged = true;
			PhotoMode.LOGGER.info(
					"[Photo Mode] renderFov: monitor={}x{} ({}), target={}, effFov={}, renderFov={}",
					mw, mh, String.format("%.3f", monitorAspect),
					String.format("%.3f", targetAspect), String.format("%.1f", vfov),
					String.format("%.1f", out));
		}
		return out;
	}

	/** Rough full-frame focal-length equivalent for the current FOV, for the readout. */
	public static int focalLengthMm() {
		double f = 12.0 / Math.tan(Math.toRadians(effectiveFov() * 0.5));
		return (int) Math.max(1, Math.round(f));
	}

	/** Called at the start of every client tick. Controls whether the character reads
	 *  the keyboard. In DRONE mode the character is always frozen (a plain ClientInput
	 *  reads nothing). In CAMERA mode the character walks — except while a photo is
	 *  being taken, or the settings panel / focus reticle is up, when it must hold still. */
	private static boolean wasUseKeyDown = false;

	public static void onStartClientTick(Minecraft mc) {
		if (!active || mc.player == null) {
			return;
		}
		if (reloadPending > 0) {
			reloadPending--;
			reloadLoadout();
		}
		// F is the mod's focus key but also vanilla "swap item to offhand" — drain the
		// swap presses so the camera never jumps to the offhand while in photo mode.
		while (mc.options.keySwapOffhand.consumeClick()) {
			// discard
		}
		// While shooting with a camera body, the inventory key opens the loadout menu
		// (swap lens / filter) instead of the player inventory — held or on a tripod.
		// Grab it here, before vanilla's handleKeybinds opens the player inventory.
		if (mode == Mode.CAMERA && deviceItem == PhotoMode.CAMERA_BODY) {
			boolean open = false;
			while (mc.options.keyInventory.consumeClick()) {
				open = true;
			}
			if (open && mc.gui.screen() == null) {
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
						new com.itsthejimjam.realcamera.OpenLoadoutPayload(
								tripodPos == null ? java.util.Optional.empty() : java.util.Optional.of(tripodPos)));
			}
		} else {
			// Other devices: don't let the inventory key do anything odd mid-session.
			while (mc.options.keyInventory.consumeClick()) {
				// discard
			}
		}
		// Held camera body pulled out of the hand mid-session — bail rather than get stuck.
		if (mode == Mode.CAMERA && deviceItem == PhotoMode.CAMERA_BODY && tripodPos == null
				&& !PhotoMode.isCameraBody(mc.player.getMainHandItem())) {
			exit();
			return;
		}
		boolean holdStill = mode == Mode.DRONE
				|| focusPicking
				|| mc.gui.screen() != null
				|| PhotoCapture.wantsBigFrame()
				|| (tripodMounted && mode == Mode.CAMERA);
		if (holdStill) {
			mc.player.input = new ClientInput();
		} else if (!(mc.player.input instanceof KeyboardInput)) {
			mc.player.input = new KeyboardInput(mc.options);
		}
	}

	/** Called at the end of every client tick. Moves the free camera (drone mode only)
	 *  and edge-detects the right-click to leave photo mode. */
	public static void onEndClientTick(Minecraft mc) {
		if (!active) {
			wasUseKeyDown = false;
			return;
		}
		if (mc.player == null || mc.level == null) {
			exit();
			return;
		}
		// Right-click leaves photo mode. Poll the key STATE (not the consumed click
		// queue, which is timing- and hand-dependent) and fire once on the press edge.
		boolean useDown = mc.options.keyUse.isDown();
		if (useDown && !wasUseKeyDown && mc.gui.screen() == null) {
			wasUseKeyDown = true;
			exit();
			return;
		}
		wasUseKeyDown = useDown;

		driveAutoExposure();

		if (mode != Mode.DRONE) {
			return;
		}
		if (camera == null) {
			exit();
			return;
		}
		// The camera is locked while a photo is being taken — a multi-second 8K capture
		// must not drift if a movement key is held or bumped.
		if (!PhotoCapture.wantsBigFrame()) {
			camera.driveTick(HORIZONTAL_SPEED, VERTICAL_SPEED);
		}
	}

	/** Server is gone (disconnect / world close) — drop state without touching it. */
	public static void forceReset() {
		active = false;
		tripodMounted = false;
		tripodAnchor = null;
		tripodPos = null;
		shakeAmt = shakeYaw = shakePitch = 0.0f;
		camera = null;
		savedCameraType = null;
		zoom = 1.0f;
		activeLens = null;
		lensZoomMin = null;
		lensZoomMax = null;
		lensLabel = "";
		filterLabel = "";
		apertureFloorIndex = 0;
		stashMain = ItemStack.EMPTY;
		stashOff = ItemStack.EMPTY;
		PhotoCapture.reset();
		CustomRecipes.save();
	}

	private static void setFrozen(Minecraft mc, boolean frozen) {
		if (mc.getSingleplayerServer() != null) {
			mc.getSingleplayerServer().execute(
					() -> mc.getSingleplayerServer().tickRateManager().setFrozen(frozen));
		}
	}
}
