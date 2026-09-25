package com.itsthejimjam.realcamera.client;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.block.TripodBlock;
import com.itsthejimjam.realcamera.block.TripodBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The tripod is {@link net.minecraft.world.level.block.RenderShape#INVISIBLE} to the chunk
 * mesher (the only reliable way to keep it out of its own photo while Sodium meshes the
 * world) — so this block-entity renderer draws it every frame instead: the bare stand as
 * a block model, and the mounted camera as its real item model (so the installed lens
 * shows), rotated to the placement facing. Draws nothing while photo mode is shooting
 * from a tripod.
 *
 * <p>1.20.4 predates the render-state-extraction rendering rewrite (no
 * {@code SubmitNodeCollector}/{@code MovingBlockRenderState}/{@code ItemModelResolver}) —
 * this renders directly each frame via the classic
 * {@code BlockRenderDispatcher.renderSingleBlock}/{@code ItemRenderer.renderStatic}, and
 * uses fixed offsets for the camera-on-plate placement instead of 26.2's dynamic
 * item-model bounding box (that query isn't available the same way here).
 */
public class TripodBlockEntityRenderer implements BlockEntityRenderer<TripodBlockEntity> {

	/** Top of the stand's mount plate, in blocks (matches the generated model: y23.6 / 16). */
	private static final float PLATE_TOP = 23.6f / 16.0f;
	private static final float CAM_SCALE = 0.5f;
	/** Fixed vertical rest offset for a normal (dark) lens/body, in scaled item-model units. */
	private static final float REST_Y_NORMAL = 0.2f;
	/** A big white tele carries its own tripod foot partway along the barrel, so the rig
	 *  sits lower / further forward than a normal lens. */
	private static final float REST_Y_FOOTLENS = 0.35f;
	private static final float REST_Z_FOOTLENS = -0.15f;

	private final ItemRenderer itemRenderer;
	private final BlockRenderDispatcher blockRenderer;

	public TripodBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {
		this.itemRenderer = ctx.getItemRenderer();
		this.blockRenderer = ctx.getBlockRenderDispatcher();
	}

	@Override
	public void render(TripodBlockEntity be, float partialTick, PoseStack pose,
			MultiBufferSource buffer, int packedLight, int packedOverlay) {
		if (PhotoModeSession.shootingFromTripod()) {
			return;
		}

		Direction facing = be.getBlockState().getValue(TripodBlock.FACING);
		Level level = be.getLevel();

		// Turn the stand 45deg about its centre so the three legs splay toward the
		// block's corners (~0.71 away) instead of straight out through a face (0.5
		// away) — that keeps the feet inside the block's footprint, not hanging off.
		pose.pushPose();
		pose.translate(0.5, 0.0, 0.5);
		pose.mulPose(Axis.YP.rotationDegrees(45.0f));
		pose.translate(-0.5, 0.0, -0.5);
		// Straight to the model renderer: renderSingleBlock() skips RenderShape.INVISIBLE
		// blocks, which is exactly what the tripod is (see the class doc) — it drew nothing.
		BlockState stand = PhotoMode.TRIPOD.defaultBlockState();
		blockRenderer.getModelRenderer().renderModel(pose.last(),
				buffer.getBuffer(ItemBlockRenderTypes.getRenderType(stand, false)), stand,
				blockRenderer.getBlockModel(stand), 1.0f, 1.0f, 1.0f, packedLight, packedOverlay);
		pose.popPose();

		ItemStack cam = be.getCamera();
		boolean hasCamera = be.getBlockState().getValue(TripodBlock.MOUNTED) && !cam.isEmpty();
		if (!hasCamera || level == null) {
			return;
		}
		boolean footLens = TripodBlock.barrelFor(cam) == TripodBlock.Barrel.WHITE;

		pose.pushPose();
		pose.translate(0.5, PLATE_TOP, 0.5);
		pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
		pose.scale(CAM_SCALE, CAM_SCALE, CAM_SCALE);
		if (footLens) {
			pose.translate(0.0, REST_Y_FOOTLENS, REST_Z_FOOTLENS);
		} else {
			pose.translate(0.0, REST_Y_NORMAL, 0.0);
		}
		itemRenderer.renderStatic(cam, ItemDisplayContext.NONE, packedLight, packedOverlay,
				pose, buffer, level, be.getBlockPos().hashCode());
		pose.popPose();
	}
}
