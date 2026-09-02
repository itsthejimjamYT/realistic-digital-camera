package com.itsthejimjam.realcamera.client;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.itsthejimjam.realcamera.PhotoMode;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

/**
 * User-authored film recipes, three slots ("Custom 1..3"). Each slot mirrors a
 * modern camera's film-simulation settings — a {@link FilmParams.FilmBase film-sim base},
 * Dynamic Range, Highlight / Shadow tone, Color, Clarity, Color Chrome + FX Blue,
 * WB Shift (Red / Blue), Grain, mono toning, and a matte "Fade". {@link FilmParams}
 * turns the active slot into the finishing pass's {@code GradeConfig}; the panel
 * sidebar edits it live; it persists to {@code config/realcamera-recipes.json}.
 */
public final class CustomRecipes {

	public static final int SLOTS = 3;
	public static final String[] SLOT_NAMES = {"Custom 1", "Custom 2", "Custom 3"};

	/** Grain control options → (roughness index 0..2, large flag). */
	public static final String[] GRAIN_OPTIONS = {"Off", "Weak", "Weak Large", "Strong", "Strong Large"};

	/** One editable numeric grade control. Range + granularity + neutral default. */
	public enum Param {
		HIGHLIGHT (-2.0f, 4.0f, 0.5f, 0.0f, 1),
		SHADOW    (-2.0f, 4.0f, 0.5f, 0.0f, 1),
		COLOR     (-4.0f, 4.0f, 1.0f, 0.0f, 0),
		CLARITY   (-5.0f, 5.0f, 1.0f, 0.0f, 0),
		WB_R      (-9.0f, 9.0f, 1.0f, 0.0f, 0),
		WB_B      (-9.0f, 9.0f, 1.0f, 0.0f, 0),
		MONO_TONE (-9.0f, 9.0f, 1.0f, 0.0f, 0),
		FADE      ( 0.0f, 0.30f, 0.01f, 0.0f, 2);

		final float min;
		final float max;
		final float step;
		final float def;
		/** decimal places to show; 0 → signed integer. */
		final int places;
		private String[] opts;

		Param(float min, float max, float step, float def, int places) {
			this.min = min;
			this.max = max;
			this.step = step;
			this.def = def;
			this.places = places;
		}

		int steps() {
			return Math.round((max - min) / step) + 1;
		}

		float value(int index) {
			return min + Mth.clamp(index, 0, steps() - 1) * step;
		}

		int index(float v) {
			return Mth.clamp(Math.round((v - min) / step), 0, steps() - 1);
		}

		String format(float v) {
			if (places == 0) {
				int u = Math.round(v);
				return u == 0 ? "0" : String.format(Locale.ROOT, "%+d", u);
			}
			if (Math.abs(v) < 1.0e-4f) {
				v = 0.0f;
			}
			return String.format(Locale.ROOT, "%." + places + "f", v);
		}

		String[] options() {
			if (opts == null) {
				opts = new String[steps()];
				for (int i = 0; i < opts.length; i++) {
					opts[i] = format(value(i));
				}
			}
			return opts;
		}
	}

	/** One slot's values. Fields package-private so {@link FilmParams} can read them. */
	public static final class Slot {
		String name = "";
		int base = 0;              // FilmParams.FilmBase ordinal
		int dr = 0;               // FilmParams.DR ordinal
		float highlight = 0.0f;   // -2 .. +4
		float shadow = 0.0f;      // -2 .. +4
		int color = 0;           // -4 .. +4
		int clarity = 0;         // -5 .. +5
		int chrome = 0;          // FilmParams.Tri ordinal
		int fxBlue = 0;          // FilmParams.Tri ordinal
		int wbR = 0;             // -9 .. +9
		int wbB = 0;             // -9 .. +9
		int grain = 0;           // FilmParams.Tri ordinal (roughness)
		boolean grainLarge = false;
		int monoToneWarm = 0;    // -9 .. +9
		float fade = 0.0f;       // 0 .. 0.30
		int split = 0;           // FilmParams.SplitTone ordinal
		int splitAmt = 0;        // FilmParams.Tri ordinal

		void resetToNeutral() {
			base = 0;
			dr = 0;
			highlight = 0.0f;
			shadow = 0.0f;
			color = 0;
			clarity = 0;
			chrome = 0;
			fxBlue = 0;
			wbR = 0;
			wbB = 0;
			grain = 0;
			grainLarge = false;
			monoToneWarm = 0;
			fade = 0.0f;
			split = 0;
			splitAmt = 0;
		}

		void adoptSanitised(Slot o) {
			base = Mth.clamp(o.base, 0, FilmParams.FilmBase.values().length - 1);
			dr = Mth.clamp(o.dr, 0, FilmParams.DR.values().length - 1);
			highlight = Mth.clamp(o.highlight, Param.HIGHLIGHT.min, Param.HIGHLIGHT.max);
			shadow = Mth.clamp(o.shadow, Param.SHADOW.min, Param.SHADOW.max);
			color = Mth.clamp(o.color, -4, 4);
			clarity = Mth.clamp(o.clarity, -5, 5);
			chrome = Mth.clamp(o.chrome, 0, 2);
			fxBlue = Mth.clamp(o.fxBlue, 0, 2);
			wbR = Mth.clamp(o.wbR, -9, 9);
			wbB = Mth.clamp(o.wbB, -9, 9);
			grain = Mth.clamp(o.grain, 0, 2);
			grainLarge = o.grainLarge;
			monoToneWarm = Mth.clamp(o.monoToneWarm, -9, 9);
			fade = Mth.clamp(o.fade, Param.FADE.min, Param.FADE.max);
			split = Mth.clamp(o.split, 0, FilmParams.SplitTone.values().length - 1);
			splitAmt = Mth.clamp(o.splitAmt, 0, 2);
		}
	}

	private static final class Data {
		int version = 2;
		Slot[] slots;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Slot[] slots = new Slot[SLOTS];
	private static String[] copyOptions;
	private static boolean dirty = false;

	static {
		for (int i = 0; i < SLOTS; i++) {
			slots[i] = new Slot();
			slots[i].name = SLOT_NAMES[i];
		}
	}

	private CustomRecipes() {
	}

	// --- slot access ---

	public static Slot slot(int i) {
		return slots[Mth.clamp(i, 0, SLOTS - 1)];
	}

	public static String slotName(int i) {
		return SLOT_NAMES[Mth.clamp(i, 0, SLOTS - 1)];
	}

	public static int editingSlot(int recipeIndex) {
		int k = recipeIndex - FilmParams.builtinCount();
		return (k >= 0 && k < SLOTS) ? k : -1;
	}

	private static Slot activeSlot() {
		int k = editingSlot(PhotoModeSession.getRecipeIndex());
		return slots[k < 0 ? 0 : k];
	}

	// --- numeric params (operate on the active slot) ---

	static float get(Slot s, Param p) {
		return switch (p) {
			case HIGHLIGHT -> s.highlight;
			case SHADOW -> s.shadow;
			case COLOR -> s.color;
			case CLARITY -> s.clarity;
			case WB_R -> s.wbR;
			case WB_B -> s.wbB;
			case MONO_TONE -> s.monoToneWarm;
			case FADE -> s.fade;
		};
	}

	private static void set(Slot s, Param p, float v) {
		v = Mth.clamp(v, p.min, p.max);
		switch (p) {
			case HIGHLIGHT -> s.highlight = v;
			case SHADOW -> s.shadow = v;
			case COLOR -> s.color = Math.round(v);
			case CLARITY -> s.clarity = Math.round(v);
			case WB_R -> s.wbR = Math.round(v);
			case WB_B -> s.wbB = Math.round(v);
			case MONO_TONE -> s.monoToneWarm = Math.round(v);
			case FADE -> s.fade = v;
		}
		markDirty();
	}

	public static int paramIndex(Param p) {
		return p.index(get(activeSlot(), p));
	}

	public static void setParamIndex(Param p, int i) {
		set(activeSlot(), p, p.value(i));
	}

	public static void stepParam(Param p, int dir) {
		Slot s = activeSlot();
		set(s, p, p.value(p.index(get(s, p)) + dir));
	}

	public static void resetParam(Param p) {
		set(activeSlot(), p, p.def);
	}

	// --- enum-list params ---

	public static int baseIndex() {
		return activeSlot().base;
	}

	public static void setBaseIndex(int i) {
		activeSlot().base = Mth.clamp(i, 0, FilmParams.FilmBase.values().length - 1);
		markDirty();
	}

	public static void stepBase(int dir) {
		Slot s = activeSlot();
		s.base = Math.floorMod(s.base + dir, FilmParams.FilmBase.values().length);
		markDirty();
	}

	public static void resetBase() {
		activeSlot().base = 0;
		markDirty();
	}

	public static int drIndex() {
		return activeSlot().dr;
	}

	public static void setDrIndex(int i) {
		activeSlot().dr = Mth.clamp(i, 0, FilmParams.DR.values().length - 1);
		markDirty();
	}

	public static void stepDr(int dir) {
		Slot s = activeSlot();
		s.dr = Math.floorMod(s.dr + dir, FilmParams.DR.values().length);
		markDirty();
	}

	public static void resetDr() {
		activeSlot().dr = 0;
		markDirty();
	}

	public static int chromeIndex() {
		return activeSlot().chrome;
	}

	public static void setChromeIndex(int i) {
		activeSlot().chrome = Mth.clamp(i, 0, 2);
		markDirty();
	}

	public static void stepChrome(int dir) {
		Slot s = activeSlot();
		s.chrome = Math.floorMod(s.chrome + dir, 3);
		markDirty();
	}

	public static void resetChrome() {
		activeSlot().chrome = 0;
		markDirty();
	}

	public static int fxBlueIndex() {
		return activeSlot().fxBlue;
	}

	public static void setFxBlueIndex(int i) {
		activeSlot().fxBlue = Mth.clamp(i, 0, 2);
		markDirty();
	}

	public static void stepFxBlue(int dir) {
		Slot s = activeSlot();
		s.fxBlue = Math.floorMod(s.fxBlue + dir, 3);
		markDirty();
	}

	public static void resetFxBlue() {
		activeSlot().fxBlue = 0;
		markDirty();
	}

	public static int splitIndex() {
		return activeSlot().split;
	}

	public static void setSplitIndex(int i) {
		activeSlot().split = Mth.clamp(i, 0, FilmParams.SplitTone.LABELS.length - 1);
		markDirty();
	}

	public static void stepSplit(int dir) {
		Slot s = activeSlot();
		s.split = Math.floorMod(s.split + dir, FilmParams.SplitTone.LABELS.length);
		markDirty();
	}

	public static void resetSplit() {
		activeSlot().split = 0;
		markDirty();
	}

	public static int splitAmtIndex() {
		return activeSlot().splitAmt;
	}

	public static void setSplitAmtIndex(int i) {
		activeSlot().splitAmt = Mth.clamp(i, 0, 2);
		markDirty();
	}

	public static void stepSplitAmt(int dir) {
		Slot s = activeSlot();
		s.splitAmt = Math.floorMod(s.splitAmt + dir, 3);
		markDirty();
	}

	public static void resetSplitAmt() {
		activeSlot().splitAmt = 0;
		markDirty();
	}

	/** Grain: one control folding roughness (Off/Weak/Strong) and size (Large). */
	public static int grainIndex() {
		Slot s = activeSlot();
		if (s.grain == 0) {
			return 0;
		}
		return (s.grain - 1) * 2 + 1 + (s.grainLarge ? 1 : 0);
	}

	public static void setGrainIndex(int i) {
		Slot s = activeSlot();
		i = Mth.clamp(i, 0, GRAIN_OPTIONS.length - 1);
		if (i == 0) {
			s.grain = 0;
			s.grainLarge = false;
		} else {
			s.grain = (i - 1) / 2 + 1;
			s.grainLarge = ((i - 1) % 2) == 1;
		}
		markDirty();
	}

	public static void stepGrain(int dir) {
		setGrainIndex(Math.floorMod(grainIndex() + dir, GRAIN_OPTIONS.length));
	}

	public static void resetGrain() {
		activeSlot().grain = 0;
		activeSlot().grainLarge = false;
		markDirty();
	}

	// --- copy from / whole-slot reset ---

	public static String[] copyOptions() {
		if (copyOptions == null) {
			int n = FilmParams.builtinCount();
			copyOptions = new String[n + 1];
			copyOptions[0] = "—";
			for (int i = 0; i < n; i++) {
				copyOptions[i + 1] = FilmParams.builtinName(i);
			}
		}
		return copyOptions;
	}

	public static void applyCopyFrom(int optionIndex) {
		if (optionIndex > 0) {
			FilmParams.seedSlotFromBuiltin(activeSlot(), optionIndex - 1);
			markDirty();
		}
	}

	public static void resetActiveSlot() {
		activeSlot().resetToNeutral();
		markDirty();
	}

	// --- persistence ---

	public static void markDirty() {
		dirty = true;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("realcamera-recipes.json");
	}

	public static void load() {
		try {
			Path f = file();
			if (Files.exists(f)) {
				try (Reader r = Files.newBufferedReader(f)) {
					Data d = GSON.fromJson(r, Data.class);
					if (d != null && d.slots != null) {
						for (int i = 0; i < SLOTS && i < d.slots.length; i++) {
							if (d.slots[i] != null) {
								slots[i].adoptSanitised(d.slots[i]);
							}
						}
					}
				}
				PhotoMode.LOGGER.info("[Photo Mode] loaded custom recipes from {}", f);
			}
		} catch (Exception e) {
			PhotoMode.LOGGER.warn("[Photo Mode] couldn't read custom recipes: {}", e.toString());
		}
		dirty = false;
	}

	public static void save() {
		if (!dirty) {
			return;
		}
		try {
			Path f = file();
			Files.createDirectories(f.getParent());
			Data d = new Data();
			d.slots = slots;
			try (Writer w = Files.newBufferedWriter(f)) {
				GSON.toJson(d, w);
			}
			dirty = false;
		} catch (Exception e) {
			PhotoMode.LOGGER.warn("[Photo Mode] couldn't save custom recipes: {}", e.toString());
		}
	}
}
