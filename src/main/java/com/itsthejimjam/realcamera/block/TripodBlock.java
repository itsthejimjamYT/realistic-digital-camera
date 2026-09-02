package com.itsthejimjam.realcamera.block;

import com.itsthejimjam.realcamera.PhotoMode;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A two-block-tall camera stand (like a door: a {@code LOWER} + {@code UPPER} half, so
 * you can click the camera up top as well as the legs). Right-click with a camera body
 * to mount it; right-click the mounted stand to shoot from it; left-click a mounted
 * stand to drop the camera + tripod. Only the LOWER half carries the block entity and
 * the {@link com.itsthejimjam.realcamera.client.TripodBlockEntityRenderer}, which draws the
 * whole thing.
 */
public class TripodBlock extends BaseEntityBlock {

	public static final MapCodec<TripodBlock> CODEC = simpleCodec(TripodBlock::new);
	public static final BooleanProperty MOUNTED = BooleanProperty.create("mounted");
	public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

	/** Which lens the mounted camera wears, so the model can show a matching barrel
	 *  instead of a generic one: {@code none} (no lens), {@code dark} (any small/black
	 *  lens) or {@code white} (the big white 70-200 / 100-400 / 200-600 teles). */
	public enum Barrel implements StringRepresentable {
		NONE("none"), DARK("dark"), WHITE("white");

		private final String name;

		Barrel(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	public static final EnumProperty<Barrel> BARREL = EnumProperty.create("barrel", Barrel.class);

	/** The way the mounted camera's lens points — set from the player's facing when the
	 *  camera is placed on the stand. */
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

	private static final VoxelShape LOWER_SHAPE = Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
	private static final VoxelShape UPPER_SHAPE = Shapes.box(0.20, 0.0, 0.24, 0.80, 1.0, 0.76);

	public TripodBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any()
				.setValue(MOUNTED, false).setValue(BARREL, Barrel.NONE)
				.setValue(FACING, Direction.NORTH).setValue(HALF, DoubleBlockHalf.LOWER));
	}

	@Override
	protected MapCodec<TripodBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(MOUNTED, BARREL, FACING, HALF);
	}

	/** The LOWER half's position — the one that carries the block entity and the state. */
	public static BlockPos basePos(BlockState state, BlockPos pos) {
		return state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
	}

	/** The stand is drawn by {@link com.itsthejimjam.realcamera.client.TripodBlockEntityRenderer}
	 *  (a block-entity renderer), never the chunk mesh — that's the only way to reliably
	 *  keep it out of its own photo when Sodium is meshing terrain. */
	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.INVISIBLE;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return state.getValue(HALF) == DoubleBlockHalf.UPPER ? UPPER_SHAPE : LOWER_SHAPE;
	}

	/** Lens class of a camera body's installed lens, for the {@link #BARREL} model prop. */
	public static Barrel barrelFor(ItemStack cameraBody) {
		ItemStack lens = PhotoMode.loadoutLens(cameraBody);
		if (lens.isEmpty()) {
			return Barrel.NONE;
		}
		String id = BuiltInRegistries.ITEM.getKey(lens.getItem()).getPath();
		return switch (id) {
			case "lens_70_200mm", "lens_100_400mm", "lens_200_600mm" -> Barrel.WHITE;
			default -> Barrel.DARK;
		};
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return state.getValue(HALF) == DoubleBlockHalf.LOWER ? new TripodBlockEntity(pos, state) : null;
	}

	// --- two-tall placement + linkage (door pattern) --------------------------

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		BlockPos pos = ctx.getClickedPos();
		Level level = ctx.getLevel();
		if (pos.getY() < level.getMaxY() - 1 && level.getBlockState(pos.above()).canBeReplaced(ctx)) {
			return defaultBlockState().setValue(HALF, DoubleBlockHalf.LOWER);
		}
		return null;
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		level.setBlock(pos.above(), defaultBlockState().setValue(HALF, DoubleBlockHalf.UPPER), 3);
	}

	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
			Direction dir, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
		DoubleBlockHalf half = state.getValue(HALF);
		if (dir.getAxis() == Direction.Axis.Y && (half == DoubleBlockHalf.LOWER) == (dir == Direction.UP)) {
			return neighborState.is(this) && neighborState.getValue(HALF) != half
					? state
					: Blocks.AIR.defaultBlockState();
		}
		return super.updateShape(state, level, ticks, pos, dir, neighborPos, neighborState, random);
	}

	// --- interactions: always act on the LOWER half --------------------------

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
			Player player, InteractionHand hand, BlockHitResult hit) {
		BlockPos bp = basePos(state, pos);
		BlockState bs = level.getBlockState(bp);

		// Mounted stand + a lens/filter in hand: slot it straight into the camera.
		if (bs.getValue(MOUNTED) && (PhotoMode.isLens(stack) || PhotoMode.isFilter(stack))) {
			if (!level.isClientSide() && level.getBlockEntity(bp) instanceof TripodBlockEntity be
					&& PhotoMode.isCameraBody(be.getCamera())) {
				ItemStack cam = be.getCamera().copy();
				var gear = PhotoMode.loadoutContents(cam);          // [lens, filter]
				int slot = PhotoMode.isLens(stack) ? 0 : 1;
				ItemStack removed = gear.get(slot);
				gear.set(slot, stack.copyWithCount(1));
				cam.set(PhotoMode.LOADOUT, net.minecraft.world.item.component.ItemContainerContents
						.fromItems(java.util.List.of(gear.get(0), gear.get(1))));
				PhotoMode.setLensModel(cam, gear.get(0));
				be.setCamera(cam);
				level.setBlock(bp, bs.setValue(BARREL, barrelFor(cam)), 3);
				stack.shrink(1);
				if (!removed.isEmpty()) {
					Block.popResource(level, bp, removed);
				}
				level.playSound(null, bp, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.7f, 1.5f);
			}
			return InteractionResult.SUCCESS;
		}

		if (bs.getValue(MOUNTED) || !PhotoMode.isCameraBody(stack)) {
			return InteractionResult.PASS;   // fall through (client callback may enter photo mode)
		}
		if (!level.isClientSide() && level.getBlockEntity(bp) instanceof TripodBlockEntity be) {
			ItemStack mounted = stack.copyWithCount(1);
			be.setCamera(mounted);
			// Lens points the way the photographer was facing when they set the camera down.
			level.setBlock(bp, bs.setValue(MOUNTED, true)
					.setValue(BARREL, barrelFor(mounted))
					.setValue(FACING, player.getDirection()), 3);
			stack.shrink(1);
			level.playSound(null, bp, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.7f, 1.4f);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
			Player player, BlockHitResult hit) {
		// A mounted stand: consume the click so vanilla does nothing; the client-side
		// use-block callback runs first and takes you into photo mode.
		return level.getBlockState(basePos(state, pos)).getValue(MOUNTED)
				? InteractionResult.SUCCESS : InteractionResult.PASS;
	}

	@Override
	protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
		if (level.isClientSide()) {
			return;
		}
		BlockPos bp = basePos(state, pos);
		BlockState bs = level.getBlockState(bp);
		if (!bs.is(this) || !bs.getValue(MOUNTED)) {
			return;   // bare stand: normal (timed) mining, handled in playerWillDestroy
		}
		// Mounted: one punch pops the camera and takes the whole two-tall stand,
		// dropping exactly one tripod (never in creative). Airing the lower half lets
		// updateShape clear the upper half; the loot table (gated to half=lower) keeps
		// that cascade from dropping a second tripod.
		popCamera(level, bp);
		level.levelEvent(2001, bp, Block.getId(bs));
		level.setBlock(bp, Blocks.AIR.defaultBlockState(), 35);
		if (!player.isCreative()) {
			Block.popResource(level, bp, new ItemStack(this));
		}
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		if (!level.isClientSide()) {
			BlockPos bp = basePos(state, pos);
			popCamera(level, bp);
			// Survival: the loot table is gated to half=lower, so exactly one half
			// yields a tripod and the updateShape cascade that clears the other half
			// drops nothing. Creative never runs playerDestroy/loot, but the cascade
			// still would when breaking the UPPER half (it clears the LOWER, which
			// IS half=lower) — so pre-clear the lower half silently in that one case.
			if (player.isCreative() && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
				BlockState below = level.getBlockState(bp);
				if (below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER) {
					level.setBlock(bp, Blocks.AIR.defaultBlockState(), 35);
					level.levelEvent(player, 2001, bp, Block.getId(below));
				}
			}
		}
		return super.playerWillDestroy(level, pos, state, player);
	}

	private static void popCamera(Level level, BlockPos basePos) {
		if (!level.isClientSide() && level.getBlockEntity(basePos) instanceof TripodBlockEntity be
				&& !be.getCamera().isEmpty()) {
			Block.popResource(level, basePos, be.getCamera());
			be.setCamera(ItemStack.EMPTY);
		}
	}
}
