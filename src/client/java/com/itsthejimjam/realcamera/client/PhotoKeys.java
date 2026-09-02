package com.itsthejimjam.realcamera.client;

import com.itsthejimjam.realcamera.PhotoMode;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Photo-mode keybinds. They only do anything while a session is active; outside
 * photo mode they are inert so they won't clash with normal play.
 */
public final class PhotoKeys {
	private static final KeyMapping.Category CATEGORY =
			new KeyMapping.Category(PhotoMode.id("main"));

	private static KeyMapping panel;
	private static KeyMapping aspect;
	private static KeyMapping resolution;
	private static KeyMapping supersample;
	private static KeyMapping grid;
	private static KeyMapping aperture;
	private static KeyMapping focus;

	private PhotoKeys() {
	}

	public static void register() {
		panel = bind("panel", InputConstants.KEY_TAB);
		aspect = bind("aspect", InputConstants.KEY_RBRACKET);
		resolution = bind("resolution", InputConstants.KEY_LBRACKET);
		supersample = bind("supersample", InputConstants.KEY_BACKSLASH);
		grid = bind("grid", InputConstants.KEY_G);
		aperture = bind("aperture", InputConstants.KEY_SEMICOLON);
		focus = bind("focus", InputConstants.KEY_F);
	}

	private static KeyMapping bind(String name, int defaultKey) {
		return KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.realcamera." + name, InputConstants.Type.KEYSYM, defaultKey, CATEGORY));
	}

	/** Called every client tick. */
	public static void tick() {
		// Outside photo mode the binds are inert; with no lens on the mount the camera
		// is dead — nothing but "leave" or "open the loadout" (handled elsewhere) works.
		if (!PhotoModeSession.isActive() || PhotoModeSession.noLensAttached()) {
			drain(panel);
			drain(aspect);
			drain(resolution);
			drain(supersample);
			drain(grid);
			drain(aperture);
			drain(focus);
			return;
		}

		while (panel.consumeClick()) {
			// Keybinds aren't polled while a screen is open, so this only ever opens;
			// PhotoPanelScreen handles its own Tab/Esc to close.
			Minecraft.getInstance().gui.setScreen(new PhotoPanelScreen());
		}

		while (focus.consumeClick()) {
			if (PhotoModeSession.isFocusPicking()) {
				PhotoModeSession.cancelFocusPick();
			} else {
				PhotoModeSession.beginFocusPick();
			}
		}

		boolean changed = false;
		while (aspect.consumeClick()) {
			Framing.cycleAspect();
			changed = true;
		}
		while (resolution.consumeClick()) {
			Framing.cycleTier();
			changed = true;
		}
		while (supersample.consumeClick()) {
			Framing.cycleSupersample();
			changed = true;
		}
		while (aperture.consumeClick()) {
			PhotoModeSession.cycleAperture();
			changed = true;
		}
		while (grid.consumeClick()) {
			DisplayAids.stepGrid(1);
		}

		if (changed) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				mc.player.sendOverlayMessage(Component.literal(
						String.format("%s   ·   f/%s", Framing.label(), fstop())));
			}
		}
	}

	static String fstop() {
		float f = PhotoModeSession.getAperture();
		return f == Math.rint(f) ? String.valueOf((int) f) : String.valueOf(f);
	}

	private static void drain(KeyMapping k) {
		while (k.consumeClick()) {
		}
	}
}
