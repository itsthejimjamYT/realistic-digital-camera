package com.itsthejimjam.realcamera.client.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.itsthejimjam.realcamera.PhotoMode;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

/**
 * User-tunable settings, saved to {@code config/realcamera.json}. Everything here is a
 * multiplier or offset applied on top of the built-in tuned formulas, so a fresh file
 * (all defaults) renders identically to having no config at all.
 *
 * <p>Read live every frame by {@link com.itsthejimjam.realcamera.client.DofParams},
 * {@link com.itsthejimjam.realcamera.client.ExposureParams} and
 * {@link com.itsthejimjam.realcamera.client.PhotoModeSession}, so edits from the Mod Menu screen
 * take effect immediately. The getters clamp, so a hand-edited file can't break rendering.
 */
public final class PhotoConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH =
			FabricLoader.getInstance().getConfigDir().resolve("realcamera.json");

	private static PhotoConfig instance = new PhotoConfig();

	public static PhotoConfig get() {
		return instance;
	}

	// ---- Depth of field ----
	/** Multiplies the CoC ramp rate. 1 = as tuned. */
	public float blurIntensity = 1.0f;
	/** Multiplies the peak bokeh radius. 1 = as tuned. */
	public float blurRadius = 1.0f;
	/** Extra gain on the far "no depth" layer (sky + LOD terrain with no depth). */
	public float backgroundBlurGain = 1.3f;

	// ---- Depth of field (advanced) ----
	/** Dioptric knee width — how gently blur eases in around the focus plane. */
	public float focusTransitionSoftness = 0.045f;
	/** How much out-of-focus highlights bloom toward bright discs. 0 = off. */
	public float highlightBloom = 0.25f;
	/** Luma above which a sample counts as a highlight for the bloom. */
	public float highlightThreshold = 0.80f;
	/** Blur radius, in reference pixels, at which the effect is fully faded in. */
	public float blurOnsetPixels = 5.5f;

	// ---- Grain ----
	/** Multiplies ISO-driven grain. 0 = never any grain, 1 = as tuned. */
	public float grainAmount = 1.0f;
	/** Grain cell size. 1 = as tuned, higher = coarser. */
	public float grainSize = 1.0f;
	/** ISO at which grain begins to appear. */
	public int grainOnsetIso = 3200;

	// ---- Lens / zoom ----
	/** Longest zoom the scroll wheel reaches, as a multiple of the base lens. */
	public float maxZoom = 8.0f;
	/** Widest field of view the lens reaches, in degrees. */
	public float widestFov = 124.0f;
	/** Field of view of the "normal" lens (zoom = 1), in degrees. */
	public float baseFov = 70.0f;

	// ---- clamped accessors ----

	public float blurIntensity() {
		return Mth.clamp(blurIntensity, 0.25f, 3.0f);
	}

	public float blurRadius() {
		return Mth.clamp(blurRadius, 0.25f, 3.0f);
	}

	public float backgroundBlurGain() {
		return Mth.clamp(backgroundBlurGain, 0.5f, 3.0f);
	}

	public float focusTransitionSoftness() {
		return Mth.clamp(focusTransitionSoftness, 0.01f, 0.15f);
	}

	public float highlightBloom() {
		return Mth.clamp(highlightBloom, 0.0f, 1.0f);
	}

	public float highlightThreshold() {
		return Mth.clamp(highlightThreshold, 0.5f, 0.95f);
	}

	public float blurOnsetPixels() {
		return Mth.clamp(blurOnsetPixels, 1.0f, 15.0f);
	}

	public float grainAmount() {
		return Mth.clamp(grainAmount, 0.0f, 3.0f);
	}

	public float grainSize() {
		return Mth.clamp(grainSize, 0.5f, 3.0f);
	}

	public int grainOnsetIso() {
		return Mth.clamp(grainOnsetIso, 200, 25600);
	}

	public float maxZoom() {
		return Mth.clamp(maxZoom, 2.0f, 20.0f);
	}

	public float widestFov() {
		return Mth.clamp(widestFov, 90.0f, 150.0f);
	}

	public float baseFov() {
		return Mth.clamp(baseFov, 40.0f, 90.0f);
	}

	// ---- load / save ----

	// ---- panel wiring: each knob as a discrete option list, matching the settings panel's
	//      cell model. Editing one writes the live value; PhotoConfig is re-saved when the
	//      panel closes (see saveIfDirty). ----

	private static boolean dirty = false;

	/** One tunable value presented as an evenly-stepped list of options. */
	public static final class Knob {
		private final float min;
		private final float step;
		private final float def;
		private final Supplier<Float> get;
		private final Consumer<Float> set;
		public final String[] options;

		private Knob(float min, float max, float step, float def, String fmt,
				Supplier<Float> get, Consumer<Float> set) {
			this.min = min;
			this.step = step;
			this.def = def;
			this.get = get;
			this.set = set;
			int n = Math.max(2, Math.round((max - min) / step) + 1);
			this.options = new String[n];
			for (int i = 0; i < n; i++) {
				this.options[i] = String.format(Locale.ROOT, fmt, min + i * step);
			}
		}

		public int index() {
			return Mth.clamp(Math.round((get.get() - min) / step), 0, options.length - 1);
		}

		public void select(int i) {
			set.accept(min + Mth.clamp(i, 0, options.length - 1) * step);
			dirty = true;
		}

		public void step(int dir) {
			select(index() + dir);
		}

		public void reset() {
			set.accept(def);
			dirty = true;
		}
	}

	private static PhotoConfig c() {
		return instance;
	}

	public static final Knob BLUR_INTENSITY = new Knob(0.25f, 3.0f, 0.05f, 1.0f, "%.2fx",
			() -> c().blurIntensity, v -> c().blurIntensity = v);
	public static final Knob BLUR_RADIUS = new Knob(0.25f, 3.0f, 0.05f, 1.0f, "%.2fx",
			() -> c().blurRadius, v -> c().blurRadius = v);
	public static final Knob BACKGROUND_BLUR = new Knob(0.5f, 3.0f, 0.05f, 1.3f, "%.2fx",
			() -> c().backgroundBlurGain, v -> c().backgroundBlurGain = v);
	public static final Knob FOCUS_SOFTNESS = new Knob(0.01f, 0.15f, 0.005f, 0.045f, "%.3f",
			() -> c().focusTransitionSoftness, v -> c().focusTransitionSoftness = v);
	public static final Knob HIGHLIGHT_BLOOM = new Knob(0.0f, 1.0f, 0.05f, 0.25f, "%.2f",
			() -> c().highlightBloom, v -> c().highlightBloom = v);
	public static final Knob HIGHLIGHT_THRESHOLD = new Knob(0.5f, 0.95f, 0.01f, 0.80f, "%.2f",
			() -> c().highlightThreshold, v -> c().highlightThreshold = v);
	public static final Knob BLUR_ONSET = new Knob(1.0f, 15.0f, 0.5f, 5.5f, "%.1f px",
			() -> c().blurOnsetPixels, v -> c().blurOnsetPixels = v);
	public static final Knob GRAIN_AMOUNT = new Knob(0.0f, 3.0f, 0.05f, 1.0f, "%.2fx",
			() -> c().grainAmount, v -> c().grainAmount = v);
	public static final Knob GRAIN_SIZE = new Knob(0.5f, 3.0f, 0.05f, 1.0f, "%.2fx",
			() -> c().grainSize, v -> c().grainSize = v);
	public static final Knob MAX_ZOOM = new Knob(2.0f, 20.0f, 0.5f, 8.0f, "%.1fx",
			() -> c().maxZoom, v -> c().maxZoom = v);
	public static final Knob WIDEST_FOV = new Knob(90.0f, 150.0f, 1.0f, 124.0f, "%.0f deg",
			() -> c().widestFov, v -> c().widestFov = v);
	public static final Knob BASE_FOV = new Knob(40.0f, 90.0f, 1.0f, 70.0f, "%.0f deg",
			() -> c().baseFov, v -> c().baseFov = v);

	/** Grain-onset ISO, presented as full stops (ISO 200..25600). */
	public static final String[] GRAIN_ISO_OPTIONS = {
			"ISO 200", "ISO 400", "ISO 800", "ISO 1600", "ISO 3200", "ISO 6400", "ISO 12800", "ISO 25600"};

	public static int grainOnsetIsoIndex() {
		int stop = (int) Math.round(Math.log(c().grainOnsetIso / 100.0) / Math.log(2.0));
		return Mth.clamp(stop - 1, 0, GRAIN_ISO_OPTIONS.length - 1);
	}

	public static void setGrainOnsetIsoIndex(int i) {
		c().grainOnsetIso = 100 << (Mth.clamp(i, 0, GRAIN_ISO_OPTIONS.length - 1) + 1);
		dirty = true;
	}

	public static void stepGrainOnsetIso(int dir) {
		setGrainOnsetIsoIndex(grainOnsetIsoIndex() + dir);
	}

	public static void resetGrainOnsetIso() {
		c().grainOnsetIso = 3200;
		dirty = true;
	}

	/** Persist if a panel edit changed anything (called when the panel closes). */
	public static void saveIfDirty() {
		if (dirty) {
			dirty = false;
			save();
		}
	}

	public static void load() {
		if (Files.exists(PATH)) {
			try (Reader r = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
				PhotoConfig loaded = GSON.fromJson(r, PhotoConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (Exception e) {
				PhotoMode.LOGGER.warn("[Photo Mode] could not read {} — using defaults: {}",
						PATH.getFileName(), e.toString());
			}
		}
		save(); // normalise / create the file so it's there to hand-edit
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer w = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, w);
			}
		} catch (IOException e) {
			PhotoMode.LOGGER.error("[Photo Mode] could not write {}: {}",
					PATH.getFileName(), e.toString());
		}
	}
}
