package com.itsthejimjam.realcamera.client;

import java.util.List;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.client.mixin.PostChainAccessor;

import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;

/**
 * Film-recipe engine, modelled on modern in-camera film simulations. A recipe is a
 * {@linkplain FilmBase film-simulation base} plus the per-recipe knobs — Dynamic
 * Range, Highlight / Shadow tone, Color, Clarity, Color Chrome + FX Blue, WB Shift,
 * Grain, and a matte "Fade".
 *
 * <p>The built-in list holds a spread of classic photographic looks. Custom slots
 * ({@link CustomRecipes}) use the exact same model, so "Copy From" is a clean copy
 * rather than an approximation.
 *
 * <p>1.21.1 has no {@code GpuBuffer}/std140 uniform-buffer system — {@code apply(...)}
 * sets the {@code G0}..{@code G7} uniforms directly on the {@code blit} pass's
 * {@code EffectInstance} (via {@link PostChainAccessor}), instead of writing one shared
 * byte-layout block.
 */
public final class FilmParams {

	// ------------------------------------------------------------------ enums

	/** Film-simulation base: the colour science before the per-recipe knobs. */
	public enum FilmBase {
		STANDARD        ("Standard",     1.00f, 0.50f, 1.00f, false, 1.000f, 1.000f, 1.000f),
		SOFT         ("Soft",     0.94f, 0.48f, 0.92f, false, 1.010f, 1.000f, 0.996f),
		MUTED_CHROME("Muted Chrome", 0.90f, 0.44f, 0.74f, false, 0.990f, 1.000f, 1.030f),
		CLASSIC_NEG   ("Classic Negative",   0.98f, 0.46f, 0.90f, false, 1.025f, 0.995f, 0.985f),
		WARM_NEG ("Warm Negative", 0.90f, 0.46f, 0.86f, false, 1.045f, 1.000f, 0.930f),
		NATURAL_NEG     ("Natural Negative",      1.03f, 0.50f, 1.02f, false, 1.000f, 1.000f, 1.000f),
		PORTRAIT_NEG   ("Portrait Negative",   0.88f, 0.47f, 0.90f, false, 1.010f, 1.000f, 1.005f),
		CINE_FLAT        ("Cine Flat",         0.82f, 0.46f, 0.72f, false, 1.010f, 1.000f, 1.012f),
		VIVID_SLIDE        ("Vivid Slide",   1.16f, 0.50f, 1.35f, false, 1.000f, 1.000f, 1.010f),
		BW_FINE         ("Fine B&W",          1.08f, 0.50f, 0.00f, true,  1.000f, 1.000f, 1.000f),
		MONOCHROME    ("Monochrome",     1.00f, 0.50f, 0.00f, true,  1.000f, 1.000f, 1.000f);

		public final String label;
		final float contrast;
		final float pivot;
		final float sat;
		final boolean mono;
		final float tintR;
		final float tintG;
		final float tintB;

		FilmBase(String label, float contrast, float pivot, float sat, boolean mono,
				float tintR, float tintG, float tintB) {
			this.label = label;
			this.contrast = contrast;
			this.pivot = pivot;
			this.sat = sat;
			this.mono = mono;
			this.tintR = tintR;
			this.tintG = tintG;
			this.tintB = tintB;
		}

		public static final String[] LABELS = labels();

		private static String[] labels() {
			FilmBase[] v = values();
			String[] out = new String[v.length];
			for (int i = 0; i < v.length; i++) {
				out[i] = v[i].label;
			}
			return out;
		}
	}

	/** Off / Weak / Strong tri-state (Color Chrome, FX Blue, Grain roughness). */
	public enum Tri {
		OFF("Off", 0.0f), WEAK("Weak", 0.5f), STRONG("Strong", 1.0f);

		public final String label;
		final float v;

		Tri(String label, float v) {
			this.label = label;
			this.v = v;
		}

		public static final String[] LABELS = {"Off", "Weak", "Strong"};
	}

	/** Dynamic Range setting → highlight-compression strength. */
	public enum DR {
		DR_STD("Standard", 0.00f), DR_EXT("Extended", 0.35f), DR_WIDE("Wide", 0.70f),
		DR_PRI_SOFT("Priority (soft)", 0.85f), DR_PRI_HARD("Priority (strong)", 1.00f);

		public final String label;
		final float v;

		DR(String label, float v) {
			this.label = label;
			this.v = v;
		}

		public static final String[] LABELS = labels();

		private static String[] labels() {
			DR[] vv = values();
			String[] out = new String[vv.length];
			for (int i = 0; i < vv.length; i++) {
				out[i] = vv[i].label;
			}
			return out;
		}
	}

	/** Split tone: a small signed RGB push in the shadows and a complementary one in
	 *  the highlights (values in thousandths). A {@link Tri} on the recipe scales it. */
	public enum SplitTone {
		OFF         ("Off",              0,   0,   0,      0,   0,   0),
		TEAL_ORANGE ("Teal / Orange",  -76,  12,  74,    126,  34, -120),
		ORANGE_TEAL ("Orange / Teal",  120,  30, -120,   -95,  14,  92),
		COOL_WARM   ("Cool / Warm",    -50, -18, 100,     90,  34, -66),
		BLUE_GOLD   ("Blue / Gold",    -78, -34, 122,    128,  74, -96),
		SEPIA       ("Sepia",           82,  16, -66,    118,  74, -44);

		public final String label;
		final float sr;
		final float sg;
		final float sb;
		final float hr;
		final float hg;
		final float hb;

		SplitTone(String label, int sr, int sg, int sb, int hr, int hg, int hb) {
			this.label = label;
			this.sr = sr / 1000.0f;
			this.sg = sg / 1000.0f;
			this.sb = sb / 1000.0f;
			this.hr = hr / 1000.0f;
			this.hg = hg / 1000.0f;
			this.hb = hb / 1000.0f;
		}

		public static final String[] LABELS = labels();

		private static String[] labels() {
			SplitTone[] vv = values();
			String[] out = new String[vv.length];
			for (int i = 0; i < vv.length; i++) {
				out[i] = vv[i].label;
			}
			return out;
		}
	}

	// ------------------------------------------------------------------ recipe

	public record FilmRecipe(
			String name, FilmBase base, DR dr,
			float highlight, float shadow, int color, int clarity,
			Tri chrome, Tri fxBlue, int wbR, int wbB,
			Tri grain, boolean grainLarge, float fade, int monoToneWarm,
			SplitTone split, Tri splitAmt, float strength) {
	}

	private static FilmRecipe r(String name, FilmBase base, DR dr, float hi, float sh, int color,
			int clarity, Tri chrome, Tri fxBlue, int wbR, int wbB, Tri grain, boolean grainLarge,
			float fade, int monoTone) {
		return r(name, base, dr, hi, sh, color, clarity, chrome, fxBlue, wbR, wbB,
				grain, grainLarge, fade, monoTone, SplitTone.OFF, Tri.OFF);
	}

	private static FilmRecipe r(String name, FilmBase base, DR dr, float hi, float sh, int color,
			int clarity, Tri chrome, Tri fxBlue, int wbR, int wbB, Tri grain, boolean grainLarge,
			float fade, int monoTone, SplitTone split, Tri splitAmt) {
		return new FilmRecipe(name, base, dr, hi, sh, color, clarity, chrome, fxBlue, wbR, wbB,
				grain, grainLarge, fade, monoTone, split, splitAmt, 1.0f);
	}

	private static final FilmRecipe[] RECIPES = {
			// Standard = strength 0 → the clean camera render, no grade.
			new FilmRecipe("Standard", FilmBase.STANDARD, DR.DR_STD,
					0f, 0f, 0, 0, Tri.OFF, Tri.OFF, 0, 0, Tri.OFF, false, 0f, 0, SplitTone.OFF, Tri.OFF, 0.0f),

			// --- classic photographic looks ---
			r("Standard Slide", FilmBase.STANDARD, DR.DR_STD, 0f, 0f, 1, 0, Tri.WEAK, Tri.OFF, 0, 0, Tri.OFF, false, 0.00f, 0),
			r("Muted Chrome", FilmBase.MUTED_CHROME, DR.DR_EXT, -1f, 1f, -1, -1, Tri.OFF, Tri.WEAK, 0, 1, Tri.OFF, false, 0.02f, 0),
			r("Portrait Neg 400", FilmBase.WARM_NEG, DR.DR_WIDE, -1.5f, -0.5f, 1, -2, Tri.WEAK, Tri.OFF, 2, -2, Tri.WEAK, false, 0.015f, 0),
			r("Warm Gold", FilmBase.WARM_NEG, DR.DR_EXT, -1f, 0f, 2, -1, Tri.WEAK, Tri.OFF, 4, -4, Tri.WEAK, false, 0.010f, 0),
			r("Cool Slide", FilmBase.STANDARD, DR.DR_STD, 0f, 0.5f, 2, 0, Tri.WEAK, Tri.WEAK, -1, 1, Tri.OFF, false, 0.00f, 0),
			r("Vivid Slide", FilmBase.VIVID_SLIDE, DR.DR_STD, 0.5f, 1f, 3, 1, Tri.STRONG, Tri.WEAK, 0, 1, Tri.OFF, false, 0.00f, 0),
			r("Tungsten Night", FilmBase.CLASSIC_NEG, DR.DR_WIDE, -1f, -0.5f, 1, -1, Tri.WEAK, Tri.STRONG, -2, 3, Tri.WEAK, false, 0.020f, 0),
			r("Teal & Orange", FilmBase.MUTED_CHROME, DR.DR_EXT, -1f, 1f, -1, -1, Tri.WEAK, Tri.OFF, 0, 0, Tri.OFF, false, 0.015f, 0,
					SplitTone.TEAL_ORANGE, Tri.STRONG),
			r("Bleach Bypass", FilmBase.PORTRAIT_NEG, DR.DR_EXT, 2f, 2f, -3, 2, Tri.OFF, Tri.OFF, 0, 0, Tri.WEAK, false, 0.00f, 0),
			r("Faded", FilmBase.MUTED_CHROME, DR.DR_PRI_SOFT, -2f, -1f, -1, -3, Tri.OFF, Tri.OFF, 1, -1, Tri.WEAK, false, 0.080f, 0),
			r("Fine B&W", FilmBase.BW_FINE, DR.DR_EXT, 0f, 1f, 0, 1, Tri.OFF, Tri.OFF, 0, 0, Tri.WEAK, false, 0.020f, 0),
			r("Noir B&W", FilmBase.MONOCHROME, DR.DR_STD, 2f, 3f, 0, 2, Tri.OFF, Tri.OFF, 0, 0, Tri.OFF, false, 0.00f, -2),

			// --- extra community-style looks ---
			r("Portrait Neg 800", FilmBase.MUTED_CHROME, DR.DR_WIDE, -2f, -0.5f, 3, -3, Tri.STRONG, Tri.OFF, -1, -3, Tri.STRONG, true, 0.03f, 0),
			r("Punchy Gold", FilmBase.MUTED_CHROME, DR.DR_WIDE, -1.5f, 0.5f, 3, -2, Tri.WEAK, Tri.OFF, 4, -5, Tri.STRONG, false, 0.02f, 0),
			r("Symmetrical Pastel", FilmBase.MUTED_CHROME, DR.DR_PRI_HARD, 0f, 0f, 4, -3, Tri.OFF, Tri.WEAK, 6, -8, Tri.WEAK, false, 0.02f, 0),
			r("1970's Summer", FilmBase.WARM_NEG, DR.DR_WIDE, -2f, -0.5f, -2, -3, Tri.STRONG, Tri.STRONG, -1, -4, Tri.OFF, false, 0.03f, 0),
			r("Gentle Natural", FilmBase.NATURAL_NEG, DR.DR_WIDE, -1f, 0f, 0, 0, Tri.STRONG, Tri.WEAK, 0, 0, Tri.WEAK, false, 0.01f, 0),
	};

	/** Built-in recipes followed by the user's custom slots. */
	public static final int RECIPE_COUNT = RECIPES.length + CustomRecipes.SLOTS;

	/** Recipe names, for the settings panel's pick-a-recipe menu. */
	public static final String[] RECIPE_NAMES = buildNames();

	private static String[] buildNames() {
		String[] out = new String[RECIPE_COUNT];
		for (int i = 0; i < RECIPES.length; i++) {
			out[i] = RECIPES[i].name();
		}
		for (int i = 0; i < CustomRecipes.SLOTS; i++) {
			out[RECIPES.length + i] = CustomRecipes.SLOT_NAMES[i];
		}
		return out;
	}

	public static int builtinCount() {
		return RECIPES.length;
	}

	public static String builtinName(int i) {
		return RECIPES[Math.floorMod(i, RECIPES.length)].name();
	}

	private FilmParams() {
	}

	public static String recipeName(int i) {
		i = Math.floorMod(i, RECIPE_COUNT);
		return i < RECIPES.length ? RECIPES[i].name() : CustomRecipes.slotName(i - RECIPES.length);
	}

	/** Copy a built-in recipe's values into a custom slot (exact — same model). */
	static void seedSlotFromBuiltin(CustomRecipes.Slot s, int builtinIndex) {
		FilmRecipe r = RECIPES[Math.floorMod(builtinIndex, RECIPES.length)];
		s.base = r.base().ordinal();
		s.dr = r.dr().ordinal();
		s.highlight = r.highlight();
		s.shadow = r.shadow();
		s.color = r.color();
		s.clarity = r.clarity();
		s.chrome = r.chrome().ordinal();
		s.fxBlue = r.fxBlue().ordinal();
		s.wbR = r.wbR();
		s.wbB = r.wbB();
		s.grain = r.grain().ordinal();
		s.grainLarge = r.grainLarge();
		s.fade = r.fade();
		s.monoToneWarm = r.monoToneWarm();
		s.split = r.split().ordinal();
		s.splitAmt = r.splitAmt().ordinal();
	}

	private static FilmBase base(int i) {
		FilmBase[] v = FilmBase.values();
		return v[Math.floorMod(i, v.length)];
	}

	private static DR dr(int i) {
		DR[] v = DR.values();
		return v[Math.floorMod(i, v.length)];
	}

	private static Tri tri(int i) {
		Tri[] v = Tri.values();
		return v[Math.floorMod(i, v.length)];
	}

	private static SplitTone splitTone(int i) {
		SplitTone[] v = SplitTone.values();
		return v[Math.floorMod(i, v.length)];
	}

	/** Physical / panel filters, packed into G5.yz for {@code blit.fsh}. */
	private static float filterPolarizer = 0.0f;
	private static float filterMist = 0.0f;

	public static void apply(PostChain chain, int recipeIndex, float userStrength) {
		apply(chain, recipeIndex, userStrength, 0.0f, 0.0f);
	}

	public static void apply(PostChain chain, int recipeIndex, float userStrength,
			float polarizer, float mist) {
		List<PostPass> passes;
		try {
			passes = ((PostChainAccessor) chain).realcamera$passes();
		} catch (Throwable t) {
			PhotoMode.LOGGER.warn("[Photo Mode] Grade pass lookup failed: {}", t.toString());
			return;
		}
		if (passes == null || passes.size() < 4) {
			return;
		}
		EffectInstance blit = passes.get(3).getEffect();
		if (blit == null) {
			return;
		}

		filterPolarizer = Math.max(0.0f, Math.min(1.0f, polarizer));
		filterMist = Math.max(0.0f, Math.min(1.0f, mist));
		int idx = Math.floorMod(recipeIndex, RECIPE_COUNT);

		if (idx < RECIPES.length) {
			FilmRecipe r = RECIPES[idx];
			write(blit, r.base(), r.dr(), r.highlight(), r.shadow(), r.color(), r.clarity(),
					r.chrome(), r.fxBlue(), r.wbR(), r.wbB(), r.grain(), r.grainLarge(),
					r.fade(), r.monoToneWarm(), r.split(), r.splitAmt(),
					r.strength() * Math.max(0.0f, userStrength));
		} else {
			CustomRecipes.Slot s = CustomRecipes.slot(idx - RECIPES.length);
			write(blit, base(s.base), dr(s.dr), s.highlight, s.shadow, s.color, s.clarity,
					tri(s.chrome), tri(s.fxBlue), s.wbR, s.wbB, tri(s.grain), s.grainLarge,
					s.fade, s.monoToneWarm, splitTone(s.split), tri(s.splitAmt),
					Math.max(0.0f, userStrength));
		}
	}

	private static void write(EffectInstance blit, FilmBase b, DR dr, float highlight, float shadow,
			int color, int clarity, Tri chrome, Tri fxBlue, int wbR, int wbB, Tri grain,
			boolean grainLarge, float fade, int monoToneWarm, SplitTone split, Tri splitAmt,
			float strength) {
		if (b.mono) {
			strength = Math.min(strength, 1.0f);
		}
		float grainBoost = grain == Tri.OFF ? 0.0f : grain == Tri.WEAK ? 0.35f : 0.75f;
		float sAmt = split == SplitTone.OFF ? 0.0f : splitAmt.v;

		blit.safeGetUniform("G0").set(highlight, shadow, color, clarity);
		blit.safeGetUniform("G1").set(chrome.v, fxBlue.v, dr.v, fade);
		blit.safeGetUniform("G2").set(wbR, wbB, monoToneWarm, b.mono ? 1.0f : 0.0f);
		blit.safeGetUniform("G3").set(b.contrast, b.pivot, b.sat, strength);
		blit.safeGetUniform("G4").set(b.tintR, b.tintG, b.tintB, grainBoost);
		blit.safeGetUniform("G5").set(grainLarge ? 1.0f : 0.0f, filterPolarizer, filterMist,
				PhotoModeSession.captureShakeBlur01());
		blit.safeGetUniform("G6").set(split.sr, split.sg, split.sb, sAmt);
		blit.safeGetUniform("G7").set(split.hr, split.hg, split.hb, 0.0f);
	}
}
