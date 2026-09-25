package com.itsthejimjam.realcamera.client;

import com.itsthejimjam.realcamera.PhotoMode;
import com.itsthejimjam.realcamera.block.TripodBlock;
import com.itsthejimjam.realcamera.block.TripodBlockEntity;
import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The tripod is {@link net.minecraft.world.level.block.RenderShape#INVISIBLE} to the chunk
 * mesher (the only reliable way to keep it out of its own photo while Sodium meshes the
 * world) — so this block-entity renderer draws it every frame instead: the bare stand as
 * a block model, and the mounted camera as its real item model (so the installed lens
 * shows), rotated to the placement facing. Draws nothing while photo mode is shooting
 * from a tripod.
 *
 * <p>1.21.1 predates the render-state-extraction rendering rewrite (no
 * {@code SubmitNodeCollector}/{@code MovingBlockRenderState}/{@code ItemModelResolver}) —
 * this renders directly each frame via the classic
 * {@code BlockRenderDispatcher.renderSingleBlock}/{@code ItemRenderer.renderStatic}. The
 * camera sits on the plate by its item model's bounding box, like 26.2's
 * {@code getModelBoundingBox()} — computed here from the baked quads (see {@link #bounds}).
 */
public class TripodBlockEntityRenderer implements BlockEntityRenderer<TripodBlockEntity> {

	/** Top of the stand's mount plate, in blocks (matches the generated model: y23.6 / 16). */
	private static final float PLATE_TOP = 23.6f / 16.0f;
	private static final float CAM_SCALE = 0.5f;
	/** Item-model bounds per baked model (a mounted body resolves to one model per lens). */
	private static final Map<BakedModel, AABB> BOUNDS = new WeakHashMap<>();

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
		int seed = be.getBlockPos().hashCode();
		AABB bb = bounds(itemRenderer.getModel(cam, level, null, seed));
		pose.translate(0.0, -bb.minY, 0.0);   // lowest point (body base, or the lens' own foot) on the plate
		if (footLens) {
			// slide the rig so the lens' tripod collar (~half way along the model) is over
			// the plate — the lens ends up centred and the body hangs off the back.
			pose.translate(0.0, 0.0, -(bb.minZ + (bb.maxZ - bb.minZ) * 0.5));
		}
		itemRenderer.renderStatic(cam, ItemDisplayContext.NONE, packedLight, packedOverlay,
				pose, buffer, level, seed);
		pose.popPose();
	}

	/** The model's bounds in the space {@code ItemRenderer} draws it in for
	 *  {@link ItemDisplayContext#NONE}: raw quad positions shifted by its -0.5 centring. */
	private static AABB bounds(BakedModel model) {
		return BOUNDS.computeIfAbsent(model, m -> {
			double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE;
			double x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
			RandomSource random = RandomSource.create(42L);
			Direction[] sides = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
					Direction.WEST, Direction.EAST};
			for (Direction side : sides) {
				for (BakedQuad quad : m.getQuads(null, side, random)) {
					int[] v = quad.getVertices();
					int stride = v.length / 4;
					for (int i = 0; i < 4; i++) {
						float x = Float.intBitsToFloat(v[i * stride]);
						float y = Float.intBitsToFloat(v[i * stride + 1]);
						float z = Float.intBitsToFloat(v[i * stride + 2]);
						x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
						x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
					}
				}
			}
			if (x0 > x1) {
				return new AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
			}
			return new AABB(x0 - 0.5, y0 - 0.5, z0 - 0.5, x1 - 0.5, y1 - 0.5, z1 - 0.5);
		});
	}
}
