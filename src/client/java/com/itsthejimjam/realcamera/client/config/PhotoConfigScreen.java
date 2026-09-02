package com.itsthejimjam.realcamera.client.config;

import java.util.Locale;
import java.util.function.Consumer;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Builds the Cloth Config settings screen shown from Mod Menu. Every slider writes
 * straight into the live {@link PhotoConfig} singleton; Save then persists it to
 * {@code config/realcamera.json}. Changes apply on the next rendered frame — no restart.
 *
 * <p>Only referenced from {@link ModMenuIntegration}, and only after it has checked that
 * Cloth Config is actually installed, so this class never loads without its dependency.
 */
public final class PhotoConfigScreen {

	private PhotoConfigScreen() {
	}

	public static Screen create(Screen parent) {
		PhotoConfig c = PhotoConfig.get();

		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.literal("Realistic Digital Camera"))
				.setSavingRunnable(PhotoConfig::save);
		ConfigEntryBuilder eb = builder.entryBuilder();

		ConfigCategory dof = builder.getOrCreateCategory(Component.literal("Depth of Field"));
		slider(dof, eb, "Blur intensity",
				"Overall blur strength. 1.00 = default.",
				c.blurIntensity, 1.0f, 0.25f, 3.0f, 0.05f, v -> c.blurIntensity = v);
		slider(dof, eb, "Blur radius",
				"Size of the bokeh discs. 1.00 = default.",
				c.blurRadius, 1.0f, 0.25f, 3.0f, 0.05f, v -> c.blurRadius = v);
		slider(dof, eb, "Background blur gain",
				"Extra blur on far terrain / sky that has no depth data (e.g. with a mod like Voxy).",
				c.backgroundBlurGain, 1.3f, 0.5f, 3.0f, 0.05f, v -> c.backgroundBlurGain = v);

		ConfigCategory adv = builder.getOrCreateCategory(Component.literal("Depth of Field - Advanced"));
		slider(adv, eb, "Focus transition softness",
				"How gently blur eases in around the focus plane. Lower = snappier edge.",
				c.focusTransitionSoftness, 0.045f, 0.01f, 0.15f, 0.005f, v -> c.focusTransitionSoftness = v);
		slider(adv, eb, "Highlight bloom",
				"How much out-of-focus highlights bloom into bright discs. 0 = off.",
				c.highlightBloom, 0.25f, 0.0f, 1.0f, 0.05f, v -> c.highlightBloom = v);
		slider(adv, eb, "Highlight threshold",
				"Brightness above which a pixel counts as a highlight for the bloom.",
				c.highlightThreshold, 0.80f, 0.5f, 0.95f, 0.01f, v -> c.highlightThreshold = v);
		slider(adv, eb, "Blur onset distance",
				"Blur radius, in pixels, at which the effect is fully faded in.",
				c.blurOnsetPixels, 5.5f, 1.0f, 15.0f, 0.5f, v -> c.blurOnsetPixels = v);

		ConfigCategory grain = builder.getOrCreateCategory(Component.literal("Grain"));
		slider(grain, eb, "Grain amount",
				"Multiplies ISO-driven grain. 0 = never any grain, 1 = default.",
				c.grainAmount, 1.0f, 0.0f, 3.0f, 0.05f, v -> c.grainAmount = v);
		slider(grain, eb, "Grain size",
				"Coarseness of the grain. 1 = default, higher = chunkier.",
				c.grainSize, 1.0f, 0.5f, 3.0f, 0.05f, v -> c.grainSize = v);
		int isoStop = Math.max(1, Math.min(8, Math.round(
				(float) (Math.log(c.grainOnsetIso / 100.0) / Math.log(2.0)))));
		grain.addEntry(eb.startIntSlider(Component.literal("Grain onset ISO"), isoStop, 1, 8)
				.setDefaultValue(5)
				.setTextGetter(s -> Component.literal("ISO " + (100 << s)))
				.setTooltip(Component.literal("The ISO at which film grain starts to appear."))
				.setSaveConsumer(s -> c.grainOnsetIso = 100 << s)
				.build());

		ConfigCategory lens = builder.getOrCreateCategory(Component.literal("Lens & Zoom"));
		slider(lens, eb, "Max zoom",
				"Longest zoom the scroll wheel reaches, as a multiple of the base lens.",
				c.maxZoom, 8.0f, 2.0f, 20.0f, 0.5f, v -> c.maxZoom = v);
		slider(lens, eb, "Widest FOV",
				"Widest field of view the lens opens to, in degrees.",
				c.widestFov, 124.0f, 90.0f, 150.0f, 1.0f, v -> c.widestFov = v);
		slider(lens, eb, "Base FOV",
				"Field of view of the normal lens (zoom 1x), in degrees.",
				c.baseFov, 70.0f, 40.0f, 90.0f, 1.0f, v -> c.baseFov = v);

		return builder.build();
	}

	/** A float value shown as a slider, backed by an int step count. */
	private static void slider(ConfigCategory cat, ConfigEntryBuilder eb, String label, String tip,
			float current, float def, float min, float max, float step, Consumer<Float> save) {
		int scale = Math.round(1.0f / step);
		int cur = Math.round(current * scale);
		int lo = Math.round(min * scale);
		int hi = Math.round(max * scale);
		cat.addEntry(eb.startIntSlider(Component.literal(label),
						Math.max(lo, Math.min(hi, cur)), lo, hi)
				.setDefaultValue(Math.round(def * scale))
				.setTextGetter(i -> Component.literal(fmt(i / (float) scale)))
				.setTooltip(Component.literal(tip))
				.setSaveConsumer(i -> save.accept(i / (float) scale))
				.build());
	}

	private static String fmt(float v) {
		return v == Math.rint(v)
				? String.format(Locale.ROOT, "%.0f", v)
				: String.format(Locale.ROOT, "%.2f", v);
	}
}
