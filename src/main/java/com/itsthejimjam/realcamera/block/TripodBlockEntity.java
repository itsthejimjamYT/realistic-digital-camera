package com.itsthejimjam.realcamera.block;

import com.itsthejimjam.realcamera.PhotoMode;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Holds the camera body mounted on a tripod (its full item + tags), synced to the
 *  client so photo mode can read the loadout when you climb behind it. */
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
	public void load(CompoundTag tag) {
		super.load(tag);
		this.camera = tag.contains("Camera") ? ItemStack.of(tag.getCompound("Camera")) : ItemStack.EMPTY;
	}

	@Override
	protected void saveAdditional(CompoundTag tag) {
		super.saveAdditional(tag);
		if (!camera.isEmpty()) {
			tag.put("Camera", camera.save(new CompoundTag()));
		}
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag() {
		CompoundTag tag = new CompoundTag();
		saveAdditional(tag);
		return tag;
	}
}
