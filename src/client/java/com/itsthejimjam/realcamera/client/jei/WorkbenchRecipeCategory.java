package com.itsthejimjam.realcamera.client.jei;

import java.util.List;
import java.util.Optional;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.recipe.WorkbenchRecipe;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

/** JEI category for {@link WorkbenchRecipe} — a wide 7×5 grid + result. */
public class WorkbenchRecipeCategory extends AbstractRecipeCategory<RecipeHolder<WorkbenchRecipe>> {

	public static final RecipeType<RecipeHolder<WorkbenchRecipe>> TYPE =
			RecipeType.createFromVanilla(PhotoMode.WORKBENCH_RECIPE_TYPE);

	private static final int CELL = 18;
	private static final int GRID_W = 7;
	private static final int GRID_H = 5;
	private static final int GRID_X = 1;
	private static final int GRID_Y = 1;
	private static final int OUT_X = GRID_X + GRID_W * CELL + 24;
	private static final int OUT_Y = GRID_Y + 2 * CELL;

	public WorkbenchRecipeCategory(IGuiHelper gui) {
		super(TYPE,
				Component.translatable("block.realcamera.camera_workbench"),
				gui.createDrawableItemStack(new ItemStack(PhotoMode.CAMERA_WORKBENCH)),
				OUT_X + CELL + 2, GRID_H * CELL + 2);
	}

	@Override
	public void setRecipe(IRecipeLayoutBuilder builder, RecipeHolder<WorkbenchRecipe> holder, IFocusGroup focuses) {
		WorkbenchRecipe recipe = holder.value();
		int w = recipe.gridWidth();
		int h = recipe.gridHeight();
		List<Optional<Ingredient>> ing = recipe.ingredients();
		for (int row = 0; row < h; row++) {
			for (int col = 0; col < w; col++) {
				int idx = row * w + col;
				IRecipeSlotBuilder slot = builder
						.addInputSlot(GRID_X + col * CELL, GRID_Y + row * CELL)
						.setStandardSlotBackground();
				if (idx < ing.size()) {
					ing.get(idx).ifPresent(slot::add);
				}
			}
		}
		builder.addOutputSlot(OUT_X, OUT_Y)
				.setOutputSlotBackground()
				.add(recipe.resultStack());
	}
}
