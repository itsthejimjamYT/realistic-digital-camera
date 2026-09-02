package com.itsthejimjam.realcamera.recipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.itsthejimjam.realcamera.PhotoMode;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * A shaped recipe for the Camera Workbench — a grid up to {@value #MAX} wide / tall (vanilla
 * {@link net.minecraft.world.item.crafting.ShapedRecipePattern} is still 3×3-capped, so
 * this parses and matches its own pattern).
 */
public class WorkbenchRecipe implements Recipe<CraftingInput> {

	public static final int MAX = 7;

	private final List<String> patternRows;
	private final Map<String, Ingredient> key;
	private final Item resultItem;
	private final int resultCount;

	private final int width;
	private final int height;
	/** width*height, row-major. Absent = must be empty. */
	private final List<Optional<Ingredient>> cells;
	private final PlacementInfo placementInfo;

	public WorkbenchRecipe(List<String> patternRows, Map<String, Ingredient> key, Item resultItem, int resultCount) {
		List<String> shrunk = shrink(patternRows);
		this.patternRows = List.copyOf(shrunk);
		this.key = Map.copyOf(key);
		this.resultItem = resultItem;
		this.resultCount = Math.max(1, resultCount);
		this.height = shrunk.size();
		this.width = shrunk.stream().mapToInt(String::length).max().orElse(0);
		this.cells = new ArrayList<>(width * height);
		for (String row : shrunk) {
			for (int c = 0; c < width; c++) {
				char ch = c < row.length() ? row.charAt(c) : ' ';
				if (ch == ' ') {
					cells.add(Optional.empty());
				} else {
					cells.add(Optional.ofNullable(key.get(String.valueOf(ch))));
				}
			}
		}
		this.placementInfo = PlacementInfo.createFromOptionals(this.cells);
	}

	/**
	 * Drop fully-blank border rows and columns so the pattern's own width/height match
	 * the trimmed {@link CraftingInput} the menu passes in. Vanilla
	 * {@link net.minecraft.world.item.crafting.ShapedRecipePattern} does the same — without
	 * it a padded pattern like {@code "   B   "} counts as 7 wide and never matches the
	 * 5-wide cropped grid the player actually filled.
	 */
	private static List<String> shrink(List<String> rows) {
		int left = Integer.MAX_VALUE;
		int right = -1;
		int top = -1;
		int bottom = -1;
		for (int r = 0; r < rows.size(); r++) {
			String row = rows.get(r);
			int first = -1;
			int last = -1;
			for (int c = 0; c < row.length(); c++) {
				if (row.charAt(c) != ' ') {
					if (first < 0) {
						first = c;
					}
					last = c;
				}
			}
			if (first < 0) {
				continue;
			}
			if (top < 0) {
				top = r;
			}
			bottom = r;
			left = Math.min(left, first);
			right = Math.max(right, last);
		}
		if (right < 0) {
			return List.of();
		}
		List<String> out = new ArrayList<>(bottom - top + 1);
		for (int r = top; r <= bottom; r++) {
			String row = rows.get(r);
			StringBuilder sb = new StringBuilder(right - left + 1);
			for (int c = left; c <= right; c++) {
				sb.append(c < row.length() ? row.charAt(c) : ' ');
			}
			out.add(sb.toString());
		}
		return out;
	}

	public int gridWidth() {
		return width;
	}

	public int gridHeight() {
		return height;
	}

	/** Row-major, width*height. */
	public List<Optional<Ingredient>> ingredients() {
		return cells;
	}

	public ItemStack resultStack() {
		return new ItemStack(resultItem, resultCount);
	}

	@Override
	public boolean matches(CraftingInput input, Level level) {
		int iw = input.width();
		int ih = input.height();
		if (width > iw || height > ih) {
			return false;
		}
		for (int ox = 0; ox <= iw - width; ox++) {
			for (int oy = 0; oy <= ih - height; oy++) {
				if (matchesAt(input, ox, oy, false) || matchesAt(input, ox, oy, true)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean matchesAt(CraftingInput input, int ox, int oy, boolean mirror) {
		for (int y = 0; y < input.height(); y++) {
			for (int x = 0; x < input.width(); x++) {
				int px = x - ox;
				int py = y - oy;
				Optional<Ingredient> want = Optional.empty();
				if (px >= 0 && py >= 0 && px < width && py < height) {
					int cx = mirror ? width - 1 - px : px;
					want = cells.get(py * width + cx);
				}
				ItemStack got = input.getItem(x + y * input.width());
				if (want.isEmpty()) {
					if (!got.isEmpty()) {
						return false;
					}
				} else if (!want.get().test(got)) {
					return false;
				}
			}
		}
		return true;
	}

	@Override
	public ItemStack assemble(CraftingInput input) {
		return new ItemStack(resultItem, resultCount);
	}

	@Override
	public boolean showNotification() {
		return false;
	}

	@Override
	public String group() {
		return "";
	}

	@Override
	public RecipeSerializer<? extends Recipe<CraftingInput>> getSerializer() {
		return SERIALIZER;
	}

	@Override
	public RecipeType<? extends Recipe<CraftingInput>> getType() {
		return PhotoMode.WORKBENCH_RECIPE_TYPE;
	}

	@Override
	public PlacementInfo placementInfo() {
		return placementInfo;
	}

	@Override
	public RecipeBookCategory recipeBookCategory() {
		return RecipeBookCategories.CRAFTING_MISC;
	}

	public static final MapCodec<WorkbenchRecipe> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Codec.STRING.listOf().fieldOf("pattern").forGetter(r -> r.patternRows),
			Codec.unboundedMap(Codec.STRING, Ingredient.CODEC).fieldOf("key").forGetter(r -> r.key),
			BuiltInRegistries.ITEM.byNameCodec().fieldOf("result").forGetter(r -> r.resultItem),
			Codec.INT.optionalFieldOf("count", 1).forGetter(r -> r.resultCount)
	).apply(i, WorkbenchRecipe::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, WorkbenchRecipe> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), r -> r.patternRows,
			ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, Ingredient.CONTENTS_STREAM_CODEC), r -> r.key,
			ByteBufCodecs.registry(Registries.ITEM), r -> r.resultItem,
			ByteBufCodecs.VAR_INT, r -> r.resultCount,
			WorkbenchRecipe::new);

	public static final RecipeSerializer<WorkbenchRecipe> SERIALIZER =
			new RecipeSerializer<>(CODEC, STREAM_CODEC);
}
