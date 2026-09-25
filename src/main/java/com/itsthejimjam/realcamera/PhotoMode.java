package com.itsthejimjam.realcamera;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import com.itsthejimjam.realcamera.block.CameraWorkbenchBlock;
import com.itsthejimjam.realcamera.block.TripodBlock;
import com.itsthejimjam.realcamera.block.TripodBlockEntity;
import com.itsthejimjam.realcamera.menu.CameraBodyMenu;
import com.itsthejimjam.realcamera.menu.WorkbenchMenu;
import com.itsthejimjam.realcamera.recipe.WorkbenchRecipe;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PhotoMode implements ModInitializer {
	public static final String MOD_ID = "realcamera";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Map<Item, LensSpec> LENS_SPECS = new LinkedHashMap<>();
	private static final Map<Item, FilterSpec> FILTER_SPECS = new LinkedHashMap<>();
	/** Stable per-lens index (1-based; 0 = no lens) for the camera body's integer
	 *  CustomModelData — 1.21.1's item models still pick variants through integer
	 *  {@code custom_model_data} overrides (the string-keyed form arrived in 1.21.4). */
	private static final Map<Item, Integer> LENS_MODEL_INDEX = new LinkedHashMap<>();

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
			BlockBehaviour.Properties.of().strength(2.5f).sound(SoundType.WOOD).noOcclusion(),
			CameraWorkbenchBlock::new);

	/** Survival camera stand. Mount a camera body on it (right-click) to shoot from a
	 *  dead-steady, movement-locked position; left-click a mounted stand to take it apart. */
	public static final Block TRIPOD = registerBlock("tripod",
			BlockBehaviour.Properties.of().strength(1.0f).sound(SoundType.METAL)
					.noOcclusion().noCollission(),
			TripodBlock::new);

	public static final BlockEntityType<TripodBlockEntity> TRIPOD_BE = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE, id("tripod"),
			BlockEntityType.Builder.of(TripodBlockEntity::new, TRIPOD).build(null));

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

	public static void setLoadoutContents(ItemStack cameraBody, ItemStack lens, ItemStack filter) {
		cameraBody.set(LOADOUT, ItemContainerContents.fromItems(java.util.List.of(lens, filter)));
	}

	@Override
	public void onInitialize() {
		// Note: no equivalent of 26.2's RecipeSynchronization.synchronizeRecipeSerializer
		// call is needed on 1.21.1 — the server still sends every recipe to clients.

		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
				.register(entries -> {
					entries.accept(CREATIVE_CAMERA);
					entries.accept(CAMERA_BODY);
					entries.accept(DRONE);
					entries.accept(CAMERA_WORKBENCH.asItem());
					entries.accept(TRIPOD.asItem());
					for (Item lens : LENS_SPECS.keySet()) {
						entries.accept(lens);
					}
					for (Item flt : FILTER_SPECS.keySet()) {
						entries.accept(flt);
					}
				});

		// Server side: Shift+right-click a camera body opens its loadout menu.
		UseItemCallback.EVENT.register((player, level, hand) -> {
			ItemStack held = player.getItemInHand(hand);
			if (!level.isClientSide() && isCameraBody(held) && player.isShiftKeyDown()) {
				openLoadout(player);
				return InteractionResultHolder.success(held);
			}
			return InteractionResultHolder.pass(held);
		});

		// ...and the client asks for it from the "ATTACH A LENS" prompt / the E key.
		PayloadTypeRegistry.playC2S().register(OpenLoadoutPayload.TYPE, OpenLoadoutPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(OpenLoadoutPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (payload.tripod().isPresent()) {
				BlockPos pos = payload.tripod().get();
				BlockState st = player.level().getBlockState(pos);
				if (st.is(TRIPOD) && st.getValue(TripodBlock.MOUNTED)
						&& player.distanceToSqr(Vec3.atCenterOf(pos)) < 96.0) {
					player.openMenu(new SimpleMenuProvider(
							(id, inv, pl) -> new CameraBodyMenu(id, inv, pos),
							Component.translatable("container.realcamera.camera_body")));
				}
			} else if (isCameraBody(player.getMainHandItem())) {
				openLoadout(player);
			}
		});

		LOGGER.info("[Photo Mode] registered {} lenses, {} filters, cameras + drone",
				LENS_SPECS.size(), FILTER_SPECS.size());
	}

	private static void openLoadout(Player player) {
		player.openMenu(new SimpleMenuProvider(
				(id, inv, p) -> new CameraBodyMenu(id, inv, p.getMainHandItem()),
				Component.translatable("container.realcamera.camera_body")));
	}

	/** Point a camera body's item model at the installed lens (or clear it). Shared by
	 *  the loadout menu and the tripod's hold-a-lens-and-right-click path. */
	public static void setLensModel(ItemStack cameraBody, ItemStack lens) {
		Integer index = lens.isEmpty() ? null : LENS_MODEL_INDEX.get(lens.getItem());
		if (index == null) {
			cameraBody.remove(DataComponents.CUSTOM_MODEL_DATA);
		} else {
			cameraBody.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(index));
		}
	}

	// --- registration helpers ---

	private static Item lens(String path, int min, int max, String aperture) {
		Item item = register(path, new Item.Properties().stacksTo(1));
		LENS_SPECS.put(item, new LensSpec(min, max, aperture));
		LENS_MODEL_INDEX.put(item, LENS_MODEL_INDEX.size() + 1);
		return item;
	}

	private static Item filter(String path, FilterSpec spec) {
		Item item = register(path, new Item.Properties().stacksTo(16));
		FILTER_SPECS.put(item, spec);
		return item;
	}

	private static Item register(String path, Item.Properties properties) {
		return Registry.register(BuiltInRegistries.ITEM, id(path), new Item(properties));
	}

	private static Block registerBlock(String path, BlockBehaviour.Properties props,
			Function<BlockBehaviour.Properties, Block> factory) {
		Block block = Registry.register(BuiltInRegistries.BLOCK, id(path), factory.apply(props));
		Registry.register(BuiltInRegistries.ITEM, id(path), new BlockItem(block, new Item.Properties()));
		return block;
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
