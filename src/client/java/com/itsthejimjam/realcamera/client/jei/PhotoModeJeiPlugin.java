package com.itsthejimjam.realcamera.client.jei;

import java.util.ArrayList;
import java.util.List;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.client.SyncedRecipes;
import com.itsthejimjam.realcamera.recipe.WorkbenchRecipe;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * JEI integration: adds the Camera Workbench recipe category, feeds it every
 * {@code realcamera:camera_workbench} recipe, and lists the workbench block as that
 * category's crafting station. Discovered via the {@code jei_mod_plugin} entrypoint in
 * fabric.mod.json (JEI on Fabric does NOT scan for {@code @JeiPlugin}).
 */
@JeiPlugin
public class PhotoModeJeiPlugin implements IModPlugin {

	public PhotoModeJeiPlugin() {
		PhotoMode.LOGGER.info("[Photo Mode][JEI] plugin instantiated");
	}

	@Override
	public ResourceLocation getPluginUid() {
		return PhotoMode.id("jei");
	}

	@Override
	public void registerCategories(IRecipeCategoryRegistration registration) {
		PhotoMode.LOGGER.info("[Photo Mode][JEI] registerCategories");
		registration.addRecipeCategories(
				new WorkbenchRecipeCategory(registration.getJeiHelpers().getGuiHelper()));
	}

	@Override
	public void registerRecipes(IRecipeRegistration registration) {
		// 1.21.1 still syncs every recipe to the client, so the client RecipeManager (read by
		// SyncedRecipes) is the whole set — no fabric-recipe-api / JEI-map fallback needed.
		List<RecipeHolder<WorkbenchRecipe>> recipes = new ArrayList<>(SyncedRecipes.workbenchRecipes());
		PhotoMode.LOGGER.info("[Photo Mode][JEI] registerRecipes: {} workbench recipes", recipes.size());
		registration.addRecipes(WorkbenchRecipeCategory.TYPE, recipes);
	}

	@Override
	public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
		registration.addRecipeCatalyst(new ItemStack(PhotoMode.CAMERA_WORKBENCH), WorkbenchRecipeCategory.TYPE);
	}

	@Override
	public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
		// The "+" button: fill the live 7×5 grid from the player's inventory.
		registration.addRecipeTransferHandler(new WorkbenchTransferInfo());
	}
}
