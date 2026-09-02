package com.itsthejimjam.realcamera.block;

import com.itsthejimjam.realcamera.PhotoMode;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Holds the camera body mounted on a tripod (its full item + components), synced to
 *  the client so photo mode can read the loadout when you climb behind it. */
public class TripodBlockEntity extends BlockEntity {

	private ItemStack camera = ItemStack.EMPTY;

	public TripodBlockEntity(BlockPos pos, BlockState state) {
		super(PhotoMode.TRIPOD_BE, pos, state);
	}

	public ItemStack getCamera() {
		return camera;
	}

	public void setCamera(ItemStack stack) {
		this.camera = stack;
		setChanged();
		if (level != null) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.camera = input.read("Camera", ItemStack.CODEC).orElse(ItemStack.EMPTY);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (!camera.isEmpty()) {
			output.store("Camera", ItemStack.CODEC, camera);
		}
	}

	@Override
	public ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveCustomOnly(registries);
	}
}
