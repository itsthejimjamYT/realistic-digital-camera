package com.itsthejimjam.realcamera.client;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.block.TripodBlock;
import com.itsthejimjam.realcamera.block.TripodBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The tripod is {@link net.minecraft.world.level.block.RenderShape#INVISIBLE} to the chunk
 * mesher (the only reliable way to keep it out of its own photo while Sodium meshes the
 * world) — so this block-entity renderer draws it every frame instead: the bare stand as
 * a block model, and the mounted camera as its real item model (so the installed lens
 * shows), rotated to the placement facing. Draws nothing while photo mode is shooting
 * from a tripod.
 */
public class TripodBlockEntityRenderer implements BlockEntityRenderer<TripodBlockEntity, TripodRenderState> {

	/** Top of the stand's mount plate, in blocks (matches the generated model: y23.6 / 16). */
	private static final float PLATE_TOP = 23.6f / 16.0f;
	private static final float CAM_SCALE = 0.5f;

	private final ItemModelResolver itemModels;

	public TripodBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {
		this.itemModels = ctx.itemModelResolver();
	}

	@Override
	public TripodRenderState createRenderState() {
		return new TripodRenderState();
	}

	@Override
	public void extractRenderState(TripodBlockEntity be, TripodRenderState s, float partialTick,
			Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay breakProgress) {
		BlockEntityRenderer.super.extractRenderState(be, s, partialTick, cameraPos, breakProgress);
		s.hidden = PhotoModeSession.shootingFromTripod();
		s.facing = be.getBlockState().getValue(TripodBlock.FACING);

		Level level = be.getLevel();
		if (level instanceof ClientLevel cl) {
			s.biome = cl.getBiome(be.getBlockPos());
			s.cardinalLighting = cl.cardinalLighting();
			s.lightEngine = cl.getLightEngine();
		}

		ItemStack cam = be.getCamera();
		s.hasCamera = be.getBlockState().getValue(TripodBlock.MOUNTED) && !cam.isEmpty();
		if (s.hasCamera) {
			this.itemModels.updateForTopItem(s.camera, cam, ItemDisplayContext.NONE, level, null,
					be.getBlockPos().hashCode());
			s.footLens = TripodBlock.barrelFor(cam) == TripodBlock.Barrel.WHITE;
		} else {
			s.camera.clear();
			s.footLens = false;
		}
	}

	@Override
	public void submit(TripodRenderState s, PoseStack pose, SubmitNodeCollector col, CameraRenderState cam) {
		if (s.hidden) {
			return;
		}

		MovingBlockRenderState stand = new MovingBlockRenderState();
		stand.blockPos = s.blockPos;
		stand.randomSeedPos = s.blockPos;
		stand.blockState = PhotoMode.TRIPOD.defaultBlockState();
		stand.biome = s.biome;
		stand.cardinalLighting = s.cardinalLighting;
		stand.lightEngine = s.lightEngine;
		// Turn the stand 45deg about its centre so the three legs splay toward the
		// block's corners (~0.71 away) instead of straight out through a face (0.5
		// away) — that keeps the feet inside the block's footprint, not hanging off.
		pose.pushPose();
		pose.translate(0.5f, 0.0f, 0.5f);
		pose.mulPose(Axis.YP.rotationDegrees(45.0f));
		pose.translate(-0.5f, 0.0f, -0.5f);
		col.submitMovingBlock(pose, stand, 0);
		pose.popPose();

		if (s.hasCamera) {
			pose.pushPose();
			pose.translate(0.5f, PLATE_TOP, 0.5f);
			pose.mulPose(Axis.YP.rotationDegrees(-s.facing.toYRot()));
			pose.scale(CAM_SCALE, CAM_SCALE, CAM_SCALE);
			AABB bb = s.camera.getModelBoundingBox();
			pose.translate(0.0, -bb.minY, 0.0);   // lowest point (body base, or the lens' own foot) on the plate
			if (s.footLens) {
				// slide the rig so the lens' tripod collar (~half way along the model) is
				// over the plate — the lens ends up centred and the body hangs off the back.
				pose.translate(0.0, 0.0, -(bb.minZ + (bb.maxZ - bb.minZ) * 0.5));
			}
			s.camera.submit(pose, col, s.lightCoords, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
		}
	}
}
