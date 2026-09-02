package com.itsthejimjam.realcamera.menu;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.recipe.WorkbenchRecipe;

import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * The Camera Workbench: a wide 7×5 shaped-crafting grid + result slot over the player
 * inventory. Matches only {@link WorkbenchRecipe}s (its own recipe type).
 */
public class WorkbenchMenu extends AbstractContainerMenu {

	public static final int GRID_W = 7;
	public static final int GRID_H = 5;
	private static final int GRID = GRID_W * GRID_H;
	private static final int RESULT = 0;

	/** Layout constants shared with {@code CameraWorkbenchScreen}. */
	public static final int GRID_X = 8;
	public static final int GRID_Y = 18;
	public static final int RESULT_X = GRID_X + GRID_W * 18 + 24;
	public static final int RESULT_Y = GRID_Y + 2 * 18;
	public static final int INV_X = 13;
	public static final int INV_Y = GRID_Y + GRID_H * 18 + 14;

	private final TransientCraftingContainer craft = new TransientCraftingContainer(this, GRID_W, GRID_H);
	private final ResultContainer result = new ResultContainer();
	private final ContainerLevelAccess access;
	private final Player player;

	public WorkbenchMenu(int id, Inventory inv) {
		this(id, inv, ContainerLevelAccess.NULL);
	}

	public WorkbenchMenu(int id, Inventory inv, ContainerLevelAccess access) {
		super(PhotoMode.WORKBENCH_MENU, id);
		this.access = access;
		this.player = inv.player;

		addSlot(new Slot(result, 0, RESULT_X, RESULT_Y) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return false;
			}

			@Override
			public void onTake(Player p, ItemStack taken) {
				onResultTaken();
			}
		});

		for (int r = 0; r < GRID_H; r++) {
			for (int c = 0; c < GRID_W; c++) {
				addSlot(new Slot(craft, c + r * GRID_W, GRID_X + c * 18, GRID_Y + r * 18));
			}
		}

		addStandardInventorySlots(inv, INV_X, INV_Y);
	}

	@Override
	public void slotsChanged(Container container) {
		super.slotsChanged(container);
		access.execute((level, pos) -> {
			if (!(level instanceof ServerLevel serverLevel)) {
				return;
			}
			CraftingInput input = craft.asCraftInput();
			java.util.Optional<RecipeHolder<WorkbenchRecipe>> match =
					serverLevel.recipeAccess().getRecipeFor(PhotoMode.WORKBENCH_RECIPE_TYPE, input, serverLevel);
			ItemStack out = match.map(h -> h.value().assemble(input)).orElse(ItemStack.EMPTY);
			result.setItem(0, out);
			result.setRecipeUsed(match.orElse(null));
			if (player instanceof ServerPlayer sp) {
				sp.connection.send(new ClientboundContainerSetSlotPacket(containerId, incrementStateId(), RESULT, out));
			}
		});
	}

	private void onResultTaken() {
		for (int i = 0; i < GRID; i++) {
			ItemStack s = craft.getItem(i);
			if (!s.isEmpty()) {
				s.shrink(1);
			}
		}
		slotsChanged(craft);
	}

	@Override
	public void removed(Player p) {
		super.removed(p);
		access.execute((level, pos) -> clearContainer(p, craft));
	}

	@Override
	public boolean stillValid(Player p) {
		return stillValid(access, p, PhotoMode.CAMERA_WORKBENCH);
	}

	@Override
	public ItemStack quickMoveStack(Player p, int index) {
		ItemStack moved = ItemStack.EMPTY;
		Slot slot = this.slots.get(index);
		if (slot == null || !slot.hasItem()) {
			return moved;
		}
		ItemStack inSlot = slot.getItem();
		moved = inSlot.copy();

		int gridStart = 1;
		int gridEnd = 1 + GRID;
		int invStart = gridEnd;
		int invEnd = this.slots.size();

		if (index == RESULT) {
			if (!moveItemStackTo(inSlot, invStart, invEnd, true)) {
				return ItemStack.EMPTY;
			}
			slot.onQuickCraft(inSlot, moved);
		} else if (index < gridEnd) {
			// grid -> inventory
			if (!moveItemStackTo(inSlot, invStart, invEnd, false)) {
				return ItemStack.EMPTY;
			}
		} else {
			// inventory -> grid
			if (!moveItemStackTo(inSlot, gridStart, gridEnd, false)) {
				// then shuffle within the inventory / hotbar
				int hotbarStart = invEnd - 9;
				if (index < hotbarStart) {
					if (!moveItemStackTo(inSlot, hotbarStart, invEnd, false)) {
						return ItemStack.EMPTY;
					}
				} else if (!moveItemStackTo(inSlot, invStart, hotbarStart, false)) {
					return ItemStack.EMPTY;
				}
			}
		}

		if (inSlot.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		if (inSlot.getCount() == moved.getCount()) {
			return ItemStack.EMPTY;
		}
		slot.onTake(p, inSlot);
		return moved;
	}
}
