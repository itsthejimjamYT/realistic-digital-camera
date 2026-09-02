package com.itsthejimjam.realcamera.client.jei;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.menu.WorkbenchMenu;
import com.itsthejimjam.realcamera.recipe.WorkbenchRecipe;

import mezz.jei.api.recipe.transfer.IRecipeTransferInfo;
import mezz.jei.api.recipe.types.IRecipeType;

import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Powers the JEI "+" transfer button on the Camera Workbench category — one click lays a
 * recipe out in the live crafting grid from the player's inventory.
 *
 * <p>The recipe grid is trimmed to its own bounds (a tripod is 5×5, a lens 7×5), so each
 * recipe cell is mapped to the top-left of the menu's fixed 7×5 grid rather than assuming
 * a single contiguous slot range like JEI's basic transfer handler does.
 */
public final class WorkbenchTransferInfo
		implements IRecipeTransferInfo<WorkbenchMenu, RecipeHolder<WorkbenchRecipe>> {

	/** Menu slot 0 is the result; the 7×5 craft grid is slots 1..35; inventory follows. */
	private static final int CRAFT_START = 1;
	private static final int INV_START = CRAFT_START + WorkbenchMenu.GRID_W * WorkbenchMenu.GRID_H;

	@Override
	public Class<? extends WorkbenchMenu> getContainerClass() {
		return WorkbenchMenu.class;
	}

	@Override
	public Optional<MenuType<WorkbenchMenu>> getMenuType() {
		return Optional.of(PhotoMode.WORKBENCH_MENU);
	}

	@Override
	public IRecipeType<RecipeHolder<WorkbenchRecipe>> getRecipeType() {
		return WorkbenchRecipeCategory.TYPE;
	}

	@Override
	public boolean canHandle(WorkbenchMenu menu, RecipeHolder<WorkbenchRecipe> holder) {
		WorkbenchRecipe r = holder.value();
		return r.gridWidth() <= WorkbenchMenu.GRID_W && r.gridHeight() <= WorkbenchMenu.GRID_H;
	}

	@Override
	public List<Slot> getRecipeSlots(WorkbenchMenu menu, RecipeHolder<WorkbenchRecipe> holder) {
		WorkbenchRecipe r = holder.value();
		int w = r.gridWidth();
		int h = r.gridHeight();
		List<Slot> out = new ArrayList<>(w * h);
		for (int row = 0; row < h; row++) {
			for (int col = 0; col < w; col++) {
				out.add(menu.getSlot(CRAFT_START + row * WorkbenchMenu.GRID_W + col));
			}
		}
		return out;
	}

	@Override
	public List<Slot> getInventorySlots(WorkbenchMenu menu, RecipeHolder<WorkbenchRecipe> holder) {
		List<Slot> out = new ArrayList<>(menu.slots.size() - INV_START);
		for (int i = INV_START; i < menu.slots.size(); i++) {
			out.add(menu.getSlot(i));
		}
		return out;
	}
}
