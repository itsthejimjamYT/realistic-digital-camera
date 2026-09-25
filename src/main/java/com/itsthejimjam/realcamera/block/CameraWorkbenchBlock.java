package com.itsthejimjam.realcamera.block;

import com.itsthejimjam.realcamera.menu.WorkbenchMenu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A crafting station with a 5×5 grid (see {@link WorkbenchMenu}). No block entity — the
 * grid is transient, like a vanilla crafting table.
 */
public class CameraWorkbenchBlock extends Block {

	private static final Component TITLE = Component.translatable("container.realcamera.camera_workbench");

	public CameraWorkbenchBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
			Player player, BlockHitResult hit) {
		if (!level.isClientSide()) {
			player.openMenu(new SimpleMenuProvider(
					(id, inv, p) -> new WorkbenchMenu(id, inv, ContainerLevelAccess.create(level, pos)),
					TITLE));
		}
		return InteractionResult.SUCCESS;
	}
}
