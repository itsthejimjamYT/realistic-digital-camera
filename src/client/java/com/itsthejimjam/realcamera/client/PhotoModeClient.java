package com.itsthejimjam.realcamera.client;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.block.TripodBlockEntity;
import com.itsthejimjam.realcamera.client.config.PhotoConfig;
import com.itsthejimjam.realcamera.client.menu.CameraBodyScreen;
import com.itsthejimjam.realcamera.client.menu.CameraWorkbenchScreen;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import com.itsthejimjam.realcamera.block.TripodBlock;

public class PhotoModeClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		PhotoConfig.load();
		CustomRecipes.load();

		MenuScreens.register(PhotoMode.CAMERA_BODY_MENU, CameraBodyScreen::new);
		MenuScreens.register(PhotoMode.WORKBENCH_MENU, CameraWorkbenchScreen::new);

		net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(
				PhotoMode.TRIPOD_BE, TripodBlockEntityRenderer::new);

		// Right-clicking a camera enters photo mode. Client-only; the server never sees
		// it. Shift+right-click on the camera body opens its loadout menu instead — that
		// is handled server-side in PhotoMode.onInitialize (this side just returns
		// SUCCESS so the vanilla use is consumed without entering photo mode).
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!level.isClientSide()) {
				return InteractionResult.PASS;
			}
			if (PhotoModeSession.isActive()) {
				return InteractionResult.SUCCESS; // exit is edge-detected in onEndClientTick
			}
			ItemStack held = player.getItemInHand(hand);

			if (PhotoMode.isDrone(held)) {
				PhotoModeSession.toggle(PhotoModeSession.Mode.DRONE, held.getItem(), null, null, false);
				return InteractionResult.SUCCESS;
			}
			if (PhotoMode.isCameraBody(held)) {
				if (player.isShiftKeyDown()) {
					return InteractionResult.SUCCESS; // server opens the loadout menu
				}
				enterWithBody(held); // handheld; a mounted tripod is a placed block
				return InteractionResult.SUCCESS;
			}
			if (PhotoMode.isCreativeCamera(held)) {
				PhotoModeSession.toggle(PhotoModeSession.Mode.CAMERA, held.getItem(), null, null, false);
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});

		// A placed tripod. Right-clicking a MOUNTED stand (empty hand or the camera) takes
		// you into photo mode from the stand's position, movement locked. Mounting a
		// camera body onto a bare stand is left to the server (TripodBlock.useItemOn).
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
			if (!level.isClientSide() || PhotoModeSession.isActive() || hand != net.minecraft.world.InteractionHand.MAIN_HAND) {
				return InteractionResult.PASS;
			}
			BlockState clicked = level.getBlockState(hitResult.getBlockPos());
			if (!clicked.is(PhotoMode.TRIPOD)) {
				return InteractionResult.PASS;
			}
			BlockPos pos = TripodBlock.basePos(clicked, hitResult.getBlockPos());   // the LOWER half
			BlockState tState = level.getBlockState(pos);
			if (!tState.getValue(TripodBlock.MOUNTED)) {
				return InteractionResult.PASS;
			}
			ItemStack held = player.getItemInHand(hand);
			if (!held.isEmpty() && !PhotoMode.isCameraBody(held)) {
				return InteractionResult.PASS;
			}
			if (level.getBlockEntity(pos) instanceof TripodBlockEntity be && !be.getCamera().isEmpty()) {
				ItemStack cam = be.getCamera();
				ItemStack lens = PhotoMode.loadoutLens(cam);
				ItemStack filter = PhotoMode.loadoutFilter(cam);
				// Start the view pointing the way the lens points.
				Direction face = tState.getValue(TripodBlock.FACING);
				player.setYRot(face.toYRot());
				player.setXRot(0.0f);
				PhotoModeSession.setTripodAnchor(new Vec3(pos.getX() + 0.5, pos.getY() + 1.6, pos.getZ() + 0.5));
				PhotoModeSession.setTripodPos(pos);
				PhotoModeSession.toggle(PhotoModeSession.Mode.CAMERA, PhotoMode.CAMERA_BODY,
						lens.isEmpty() ? null : PhotoMode.lensSpec(lens.getItem()),
						filter.isEmpty() ? com.itsthejimjam.realcamera.FilterSpec.NONE
								: PhotoMode.filterSpec(filter.getItem()),
						true);
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});

		PhotoKeys.register();

		ClientTickEvents.START_CLIENT_TICK.register(PhotoModeSession::onStartClientTick);
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			PhotoModeSession.onEndClientTick(mc);
			PhotoKeys.tick();
		});

		HudElementRegistry.addLast(PhotoOverlay.ID, new PhotoOverlay());

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			if (PhotoModeSession.isActive()) {
				PhotoModeSession.forceReset();
			}
		});

		// Capture the server-synced recipe set so JEI can list our custom bench recipes.
		SyncedRecipes.init();

		PhotoMode.LOGGER.info("[Photo Mode] client ready");
	}

	private static void enterWithBody(ItemStack body) {
		ItemStack lensStack = PhotoMode.loadoutLens(body);
		ItemStack filterStack = PhotoMode.loadoutFilter(body);
		PhotoModeSession.toggle(PhotoModeSession.Mode.CAMERA, body.getItem(),
				lensStack.isEmpty() ? null : PhotoMode.lensSpec(lensStack.getItem()),
				filterStack.isEmpty() ? com.itsthejimjam.realcamera.FilterSpec.NONE
						: PhotoMode.filterSpec(filterStack.getItem()),
				false);
	}
}
