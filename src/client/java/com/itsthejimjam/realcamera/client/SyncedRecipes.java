package com.itsthejimjam.realcamera.client;

import java.util.Collection;
import java.util.List;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.recipe.WorkbenchRecipe;

import net.fabricmc.fabric.api.client.recipe.v1.sync.ClientRecipeSynchronizedEvent;
import net.fabricmc.fabric.api.recipe.v1.sync.SynchronizedRecipes;

import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Holds the recipe set the server synced to this client (via fabric-recipe-api-v1), so
 * the JEI plugin has a reliable source for {@code realcamera:camera_workbench} recipes
 * regardless of JEI's own internal recipe cache/timing.
 */
public final class SyncedRecipes {

	private static volatile SynchronizedRecipes synced;

	private SyncedRecipes() {
	}

	public static void init() {
		ClientRecipeSynchronizedEvent.EVENT.register((mc, recipes) -> {
			synced = recipes;
			PhotoMode.LOGGER.info("[Photo Mode] client synced {} lens-workbench recipes",
					workbenchRecipes().size());
		});
	}

	public static List<RecipeHolder<WorkbenchRecipe>> workbenchRecipes() {
		SynchronizedRecipes s = synced;
		if (s == null) {
			return List.of();
		}
		Collection<RecipeHolder<WorkbenchRecipe>> c = s.getAllOfType(PhotoMode.WORKBENCH_RECIPE_TYPE);
		return List.copyOf(c);
	}
}
