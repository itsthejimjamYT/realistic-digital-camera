package com.itsthejimjam.realcamera;

import java.util.LinkedHashMap;
import java.util.Map;

import com.itsthejimjam.realcamera.block.CameraWorkbenchBlock;
import com.itsthejimjam.realcamera.block.TripodBlock;
import com.itsthejimjam.realcamera.block.TripodBlockEntity;
import com.itsthejimjam.realcamera.menu.CameraBodyMenu;
import com.itsthejimjam.realcamera.menu.WorkbenchMenu;
import com.itsthejimjam.realcamera.recipe.WorkbenchRecipe;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PhotoMode implements ModInitializer {
	public static final String MOD_ID = "realcamera";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Map<Item, LensSpec> LENS_SPECS = new LinkedHashMap<>();
	private static final Map<Item, FilterSpec> FILTER_SPECS = new LinkedHashMap<>();

	// --- data component: the camera body's installed lens (slot 0) + filter (slot 1) ---
	public static final DataComponentType<ItemContainerContents> LOADOUT = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE, id("loadout"),
			DataComponentType.<ItemContainerContents>builder()
					.persistent(ItemContainerContents.CODEC)
					.networkSynchronized(ItemContainerContents.STREAM_CODEC)
					.build());

	// --- cameras ---
	/** Everything built in: walk + flight, continuous zoom, filters as panel toggles. */
	public static final Item CREATIVE_CAMERA = register("creative_camera", new Item.Properties().stacksTo(1));
	/** Survival body: takes one lens + one filter (Shift+right-click to load). */
	public static final Item CAMERA_BODY = register("camera_body",
			new Item.Properties().stacksTo(1).component(LOADOUT, ItemContainerContents.EMPTY));
	/** Same photo interface, flight movement — for aerial shots. */
	public static final Item DRONE = register("drone", new Item.Properties().stacksTo(1));

	// --- lenses (primes) ---
	public static final Item LENS_14MM = lens("lens_14mm", 14, 14, "f/1.8");
	public static final Item LENS_24MM = lens("lens_24mm", 24, 24, "f/1.4");
	public static final Item LENS_35MM = lens("lens_35mm", 35, 35, "f/1.4");
	public static final Item LENS_50MM = lens("lens_50mm", 50, 50, "f/1.4");
	public static final Item LENS_85MM = lens("lens_85mm", 85, 85, "f/1.4");
	public static final Item LENS_135MM = lens("lens_135mm", 135, 135, "f/1.8");
	// --- lenses (zooms) ---
	public static final Item LENS_16_35MM = lens("lens_16_35mm", 16, 35, "f/2.8");
	public static final Item LENS_24_70MM = lens("lens_24_70mm", 24, 70, "f/2.8");
	public static final Item LENS_70_200MM = lens("lens_70_200mm", 70, 200, "f/2.8");
	// Variable-aperture zooms: the widest f-stop shifts with focal length (see
	// LensSpec.widestApertureAt / PhotoModeSession.refreshApertureFloor).
	public static final Item LENS_100_400MM = lens("lens_100_400mm", 100, 400, "f/4.5-5.6");
	public static final Item LENS_200_600MM = lens("lens_200_600mm", 200, 600, "f/5.6-6.3");

	// --- filters ---
	/** A ~6-stop ND — enough to force slow shutters (and the auto long exposure) in daylight. */
	public static final Item FILTER_ND8 = filter("filter_nd8", new FilterSpec(6, 0, 0));
	/** Circular polarizer — deepens sky, cuts glare and reflections. */
	public static final Item FILTER_POLARIZER = filter("filter_polarizer", new FilterSpec(0, 1, 0));
	public static final Item FILTER_MIST = filter("filter_mist", new FilterSpec(0, 0, 0.6f));

	// --- camera workbench (5x5 crafting) ---
	public static final Block CAMERA_WORKBENCH = registerBlock("camera_workbench",
			BlockBehaviour.Properties.of().strength(2.5f).sound(SoundType.WOOD).noOcclusion());

	/** Survival camera stand. Mount a camera body on it (right-click) to shoot from a
	 *  dead-steady, movement-locked position; left-click a mounted stand to take it apart. */
	public static final Block TRIPOD = registerBlock("tripod",
			BlockBehaviour.Properties.of().strength(1.0f).sound(SoundType.METAL)
					.noOcclusion().noCollision(),
			TripodBlock::new);

	public static final BlockEntityType<TripodBlockEntity> TRIPOD_BE = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE, id("tripod"),
			new BlockEntityType<>(TripodBlockEntity::new, java.util.Set.of(TRIPOD)));

	public static final RecipeType<WorkbenchRecipe> WORKBENCH_RECIPE_TYPE = Registry.register(
			BuiltInRegistries.RECIPE_TYPE, id("camera_workbench"),
			new RecipeType<>() {
				@Override
				public String toString() {
					return "realcamera:camera_workbench";
				}
			});

	public static final RecipeSerializer<WorkbenchRecipe> WORKBENCH_SERIALIZER = Registry.register(
			BuiltInRegistries.RECIPE_SERIALIZER, id("camera_workbench"), WorkbenchRecipe.SERIALIZER);

	// --- menus ---
	public static final MenuType<CameraBodyMenu> CAMERA_BODY_MENU = Registry.register(
			BuiltInRegistries.MENU, id("camera_body"),
			new MenuType<>(CameraBodyMenu::new, FeatureFlags.VANILLA_SET));

	public static final MenuType<WorkbenchMenu> WORKBENCH_MENU = Registry.register(
			BuiltInRegistries.MENU, id("camera_workbench"),
			new MenuType<>(WorkbenchMenu::new, FeatureFlags.VANILLA_SET));

	// --- classification ---

	public static boolean isCreativeCamera(ItemStack stack) {
		return stack.is(CREATIVE_CAMERA);
	}

	public static boolean isCameraBody(ItemStack stack) {
		return stack.is(CAMERA_BODY);
	}

	/** Any handheld photo camera (creative or the survival body). */
	public static boolean isCamera(ItemStack stack) {
		return isCreativeCamera(stack) || isCameraBody(stack);
	}

	public static boolean isDrone(ItemStack stack) {
		return stack.is(DRONE);
	}

	public static boolean isLens(ItemStack stack) {
		return LENS_SPECS.containsKey(stack.getItem());
	}

	public static boolean isFilter(ItemStack stack) {
		return FILTER_SPECS.containsKey(stack.getItem());
	}

	public static boolean isTripod(ItemStack stack) {
		return stack.is(TRIPOD.asItem());
	}

	/** Lens spec for an item, or {@code null} if it isn't a lens. */
	public static LensSpec lensSpec(Item item) {
		return LENS_SPECS.get(item);
	}

	/** Filter spec for an item, or {@link FilterSpec#NONE}. */
	public static FilterSpec filterSpec(Item item) {
		return FILTER_SPECS.getOrDefault(item, FilterSpec.NONE);
	}

	/** Contents of a camera body's loadout as a fixed 2-slot list: [0] lens, [1] filter. */
	public static NonNullList<ItemStack> loadoutContents(ItemStack cameraBody) {
		NonNullList<ItemStack> gear = NonNullList.withSize(2, ItemStack.EMPTY);
		cameraBody.getOrDefault(LOADOUT, ItemContainerContents.EMPTY).copyInto(gear);
		return gear;
	}

	public static ItemStack loadoutLens(ItemStack cameraBody) {
		return loadoutContents(cameraBody).get(0);
	}

	public static ItemStack loadoutFilter(ItemStack cameraBody) {
		return loadoutContents(cameraBody).get(1);
	}

	@Override
	public void onInitialize() {
		// The Camera Workbench recipe type is custom, so its serializer must be opted in
		// or the server never syncs those recipes to clients (JEI / recipe book see none).
		net.fabricmc.fabric.api.recipe.v1.sync.RecipeSynchronization
				.synchronizeRecipeSerializer(WORKBENCH_SERIALIZER);

		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
				.register(output -> {
					output.accept(CREATIVE_CAMERA);
					output.accept(CAMERA_BODY);
					output.accept(DRONE);
					output.accept(CAMERA_WORKBENCH.asItem());
					output.accept(TRIPOD.asItem());
					for (Item lens : LENS_SPECS.keySet()) {
						output.accept(lens);
					}
					for (Item flt : FILTER_SPECS.keySet()) {
						output.accept(flt);
					}
				});

		// Server side: Shift+right-click a camera body opens its loadout menu.
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide()) {
				return InteractionResult.PASS;
			}
			ItemStack held = player.getItemInHand(hand);
			if (isCameraBody(held) && player.isShiftKeyDown()) {
				openLoadout(player);
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});

		// ...and the client asks for it from the "ATTACH A LENS" prompt / the E key.
		PayloadTypeRegistry.serverboundPlay().register(OpenLoadoutPayload.TYPE, OpenLoadoutPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(OpenLoadoutPayload.TYPE, (payload, context) -> {
			net.minecraft.server.level.ServerPlayer p = context.player();
			if (payload.tripod().isPresent()) {
				net.minecraft.core.BlockPos pos = payload.tripod().get();
				net.minecraft.world.level.block.state.BlockState st = p.level().getBlockState(pos);
				if (st.is(TRIPOD) && st.getValue(TripodBlock.MOUNTED)
						&& p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) < 96.0) {
					p.openMenu(new SimpleMenuProvider(
							(id, inv, pl) -> new CameraBodyMenu(id, inv, pos),
							Component.translatable("container.realcamera.camera_body")));
				}
			} else if (isCameraBody(p.getMainHandItem())) {
				openLoadout(p);
			}
		});

		LOGGER.info("[Photo Mode] registered {} lenses, {} filters, cameras + drone",
				LENS_SPECS.size(), FILTER_SPECS.size());
	}

	private static void openLoadout(net.minecraft.world.entity.player.Player player) {
		player.openMenu(new SimpleMenuProvider(
				(id, inv, p) -> new CameraBodyMenu(id, inv, p.getMainHandItem()),
				Component.translatable("container.realcamera.camera_body")));
	}

	/** Point a camera body's item model at the installed lens (or clear it). Shared by
	 *  the loadout menu and the tripod's hold-a-lens-and-right-click path. */
	public static void setLensModel(ItemStack cameraBody, ItemStack lens) {
		if (lens.isEmpty()) {
			cameraBody.remove(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA);
		} else {
			String lensId = BuiltInRegistries.ITEM.getKey(lens.getItem()).getPath();
			cameraBody.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA,
					new net.minecraft.world.item.component.CustomModelData(
							java.util.List.of(), java.util.List.of(), java.util.List.of(lensId), java.util.List.of()));
		}
	}

	// --- registration helpers ---

	private static Item lens(String path, int min, int max, String aperture) {
		Item item = register(path, new Item.Properties().stacksTo(1));
		LENS_SPECS.put(item, new LensSpec(min, max, aperture));
		return item;
	}

	private static Item filter(String path, FilterSpec spec) {
		Item item = register(path, new Item.Properties().stacksTo(16));
		FILTER_SPECS.put(item, spec);
		return item;
	}

	private static Item register(String path, Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(path));
		return Registry.register(BuiltInRegistries.ITEM, key, new Item(properties.setId(key)));
	}

	private static Block registerBlock(String path, BlockBehaviour.Properties props) {
		return registerBlock(path, props, CameraWorkbenchBlock::new);
	}

	private static Block registerBlock(String path, BlockBehaviour.Properties props,
			java.util.function.Function<BlockBehaviour.Properties, Block> factory) {
		ResourceKey<Block> bkey = ResourceKey.create(Registries.BLOCK, id(path));
		Block block = Registry.register(BuiltInRegistries.BLOCK, bkey, factory.apply(props.setId(bkey)));
		ResourceKey<Item> ikey = ResourceKey.create(Registries.ITEM, id(path));
		Registry.register(BuiltInRegistries.ITEM, ikey,
				new BlockItem(block, new Item.Properties().setId(ikey)));
		return block;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
