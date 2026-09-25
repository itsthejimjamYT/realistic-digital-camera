package com.itsthejimjam.realcamera.menu;

import java.util.function.Predicate;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.block.TripodBlock;
import com.itsthejimjam.realcamera.block.TripodBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The camera-body loadout: a lens slot and a filter slot, backed by the held body's
 * loadout NBT (see {@link PhotoMode#loadoutContents}). Contents write straight back to
 * the item whenever a slot changes and again when the screen closes, so the loadout
 * survives a crash. The tripod is a placeable block, not a loadout item.
 */
public class CameraBodyMenu extends AbstractContainerMenu {

	private static final int LENS_SLOT = 0;
	private static final int FILTER_SLOT = 1;
	private static final int GEAR_SLOTS = 2;

	/** Slot x positions, shared with {@code CameraBodyScreen}. */
	public static final int[] SLOT_X = {44, 116};
	public static final int SLOT_Y = 35;

	private final SimpleContainer gear = new SimpleContainer(GEAR_SLOTS);
	private final Player player;
	/** Non-null: edit the camera body mounted on this tripod instead of the held one. */
	private final BlockPos tripodPos;

	/** Client factory (empty; slots sync from the server). */
	public CameraBodyMenu(int id, Inventory playerInv) {
		this(id, playerInv, ItemStack.EMPTY, null);
	}

	/** Server factory — the held camera body. */
	public CameraBodyMenu(int id, Inventory playerInv, ItemStack cameraBody) {
		this(id, playerInv, cameraBody, null);
	}

	/** Server factory — the camera body on a placed tripod. */
	public CameraBodyMenu(int id, Inventory playerInv, BlockPos tripodPos) {
		this(id, playerInv, cameraOn(playerInv.player, tripodPos), tripodPos);
	}

	private CameraBodyMenu(int id, Inventory playerInv, ItemStack cameraBody, BlockPos tripodPos) {
		super(PhotoMode.CAMERA_BODY_MENU, id);
		this.player = playerInv.player;
		this.tripodPos = tripodPos;
		if (!cameraBody.isEmpty()) {
			var contents = PhotoMode.loadoutContents(cameraBody);
			gear.setItem(LENS_SLOT, contents.get(0));
			gear.setItem(FILTER_SLOT, contents.get(1));
		}

		addSlot(gearSlot(LENS_SLOT, PhotoMode::isLens));
		addSlot(gearSlot(FILTER_SLOT, PhotoMode::isFilter));

		// 1.21.1 has no addStandardInventorySlots() helper yet — the classic 3x9 + hotbar loop.
		int x = 8;
		int y = 84;
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new Slot(playerInv, col + row * 9 + 9, x + col * 18, y + row * 18));
			}
		}
		for (int col = 0; col < 9; col++) {
			addSlot(new Slot(playerInv, col, x + col * 18, y + 58));
		}
	}

	private static ItemStack cameraOn(Player p, BlockPos pos) {
		return p.level().getBlockEntity(pos) instanceof TripodBlockEntity be ? be.getCamera() : ItemStack.EMPTY;
	}

	private Slot gearSlot(int idx, Predicate<ItemStack> accepts) {
		return new Slot(gear, idx, SLOT_X[idx], SLOT_Y) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return accepts.test(stack);
			}

			@Override
			public int getMaxStackSize() {
				return 1;
			}
		};
	}

	@Override
	public void slotsChanged(Container container) {
		super.slotsChanged(container);
		saveToBody();
	}

	@Override
	public void removed(Player p) {
		super.removed(p);
		saveToBody();
	}

	private void saveToBody() {
		if (player == null || player.level().isClientSide()) {
			return;
		}
		ItemStack lens = gear.getItem(LENS_SLOT);
		ItemStack filter = gear.getItem(FILTER_SLOT);

		if (tripodPos != null) {
			if (player.level().getBlockEntity(tripodPos) instanceof TripodBlockEntity be
					&& PhotoMode.isCameraBody(be.getCamera())) {
				ItemStack cam = be.getCamera().copy();
				PhotoMode.setLoadoutContents(cam, lens, filter);
				PhotoMode.setLensModel(cam, lens);
				be.setCamera(cam);
				BlockState st = player.level().getBlockState(tripodPos);
				if (st.is(PhotoMode.TRIPOD)) {
					player.level().setBlock(tripodPos, st.setValue(TripodBlock.BARREL, TripodBlock.barrelFor(cam)), 3);
				}
			}
			return;
		}

		ItemStack body = player.getMainHandItem();
		if (PhotoMode.isCameraBody(body)) {
			PhotoMode.setLoadoutContents(body, lens, filter);
			PhotoMode.setLensModel(body, lens);
		}
	}

	@Override
	public boolean stillValid(Player p) {
		if (tripodPos != null) {
			BlockState st = p.level().getBlockState(tripodPos);
			return st.is(PhotoMode.TRIPOD) && st.getValue(TripodBlock.MOUNTED)
					&& p.distanceToSqr(Vec3.atCenterOf(tripodPos)) < 96.0;
		}
		return PhotoMode.isCameraBody(p.getMainHandItem());
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

		int invStart = GEAR_SLOTS;
		int invEnd = this.slots.size();
		if (index < GEAR_SLOTS) {
			if (!moveItemStackTo(inSlot, invStart, invEnd, true)) {
				return ItemStack.EMPTY;
			}
		} else {
			int dest = PhotoMode.isLens(inSlot) ? LENS_SLOT
					: PhotoMode.isFilter(inSlot) ? FILTER_SLOT : -1;
			if (dest < 0 || !moveItemStackTo(inSlot, dest, dest + 1, false)) {
				return ItemStack.EMPTY;
			}
		}

		if (inSlot.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		return moved;
	}
}
