package com.itsthejimjam.realcamera.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Wires the "Configure" button in Mod Menu to the Photo Mode settings screen. This class
 * is only loaded when Mod Menu itself is present (it's the {@code modmenu} entrypoint), and
 * it hands back a real screen only when Cloth Config is also installed — otherwise Mod Menu
 * simply shows no Configure button and the JSON file is still the way to change settings.
 */
public final class ModMenuIntegration implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
			return parent -> null;
		}
		return PhotoConfigScreen::create;
	}
}
