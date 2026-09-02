package com.itsthejimjam.realcamera.client;

import com.itsthejimjam.realcamera.PhotoMode;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;

/**
 * Client-local photo-mode sounds, built from vanilla button / lever / note clicks
 * layered and pitched to suggest each camera's shutter character. Played only for the
 * photographer.
 */
public final class CameraSounds {

	private static final SoundEvent CLICK = SoundEvents.STONE_BUTTON_CLICK_ON;
	private static final SoundEvent SNAP = SoundEvents.LEVER_CLICK;
	private static final SoundEvent BLIP = SoundEvents.NOTE_BLOCK_BIT.value();
	/** A subtle mechanical click — the settings-dial detent. */
	private static final SoundEvent TICK = SoundEvents.COMPARATOR_CLICK;

	private static long lastZoomSoundMs = 0L;

	private CameraSounds() {
	}

	/** A soft, tactile detent tick for turning a settings dial. {@code pos01} (0..1) is
	 *  how far along its travel the dial now sits — it nudges the pitch so a run toward
	 *  one end rises and a run toward the other falls. */
	public static void dialTick(float pos01) {
		play(TICK, 0.085f, 1.12f + 0.32f * Mth.clamp(pos01, 0.0f, 1.0f));
	}

	/** A single firm click for a discrete action — resetting a setting to default. */
	public static void resetClick() {
		play(TICK, 0.13f, 0.95f);
	}

	/** Zoom ring — the spyglass "telescoping" sample, quiet, pitch rising as you zoom in.
	 *  Debounced because the sample is ~1 s long: a fast scroll sweep becomes a smooth
	 *  whir instead of a stack of overlapping clacks. */
	public static void zoomTick(float pos01) {
		long now = Util.getMillis();
		if (now - lastZoomSoundMs < 140L) {
			return;
		}
		lastZoomSoundMs = now;
		play(SoundEvents.SPYGLASS_USE, 0.22f, 1.15f + 0.45f * Mth.clamp(pos01, 0.0f, 1.0f));
	}

	/** AF-confirm blip when a focus point is committed. */
	public static void focusBeep() {
		play(BLIP, 0.30f, 1.95f);
		play(BLIP, 0.18f, 2.65f);
	}

	/** Shutter, coloured by the device. */
	public static void shutter(Item device) {
		if (device == PhotoMode.DRONE) {
			play(CLICK, 0.4f, 1.7f);
			play(BLIP, 0.22f, 2.2f);                            // electronic
		} else {
			play(CLICK, 0.55f, 1.12f);
			play(SNAP, 0.30f, 1.5f);                            // crisp mirrorless snap
		}
	}

	/** Non-positional UI playback: these are operator feedback, not world sounds, so they
	 *  must not attenuate or pan when the free camera / drone flies away from the player. */
	private static void play(SoundEvent sound, float volume, float pitch) {
		Minecraft.getInstance().getSoundManager()
				.play(SimpleSoundInstance.forUI(sound, pitch, volume));
	}
}
