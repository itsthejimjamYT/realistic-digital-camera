package com.itsthejimjam.realcamera.client;

import java.util.List;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.recipe.WorkbenchRecipe;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Source of {@code realcamera:camera_workbench} recipes for the JEI plugin.
 *
 * <p>26.2 tracks these via {@code fabric-recipe-api-v1}'s client recipe-sync event,
 * which 1.21.1's Fabric API doesn't have. Not needed here either: 1.21.1 still
 * sends the full recipe set to every client on join (that's what powers the recipe
 * book), so the client's own {@code RecipeManager} is a perfectly reliable source —
 * just read it directly, no separate tracking class required.
 */
public final class SyncedRecipes {

	private SyncedRecipes() {
	}

	public static void init() {
		// No-op on 1.21.1 — see the class doc. Kept as a call site so PhotoModeClient
		// doesn't need a version-specific branch.
	}

	public static List<RecipeHolder<WorkbenchRecipe>> workbenchRecipes() {
		var level = Minecraft.getInstance().level;
		if (level == null) {
			return List.of();
		}
		return level.getRecipeManager().getAllRecipesFor(PhotoMode.WORKBENCH_RECIPE_TYPE);
	}
}
