package com.itsthejimjam.realcamera.client;

import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.itsthejimjam.realcamera.client.config.PhotoConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The photo-mode settings panel. Opened with Tab while a session is active; it sits
 * along the bottom of the screen (no dimming — you keep watching the shot) with a row
 * of tabs and, under it, the controls for the active tab.
 *
 * <p>Click a control to pop up the full list of its options and pick one; scroll or
 * the arrow keys nudge it one step; middle-click resets that one setting. {@code Q}/
 * {@code E} or {@code 1}-{@code 3} switch tabs, {@code Left}/{@code Right} move the
 * selection, left-click the scene sets focus, {@code Esc} closes.
 */
public final class PhotoPanelScreen extends Screen {

	/** One adjustable setting, backed by a list of options. */
	private interface Cell {
		String label();

		String value();

		String[] options();

		int index();

		void select(int i);

		void step(int dir);

		void reset();
	}

	private record Tab(String name, Cell[] cells) {
	}

	private static final int TABBAR_H = 16;
	private static final int ROW_H = 46;
	private static final int PANEL_H = TABBAR_H + ROW_H;

	private static final int POPUP_ITEM_H = 12;
	private static final int POPUP_MAX_ROWS = 10;
	private static final int POPUP_MIN_W = 84;

	private static final int BG = 0xD8000000;
	private static final int POPUP_BG = 0xF2000000;
	private static final int BORDER = 0x33FFFFFF;
	private static final int DIVIDER = 0x22FFFFFF;
	private static final int LABEL_COLOR = 0xFF9A9A9A;
	private static final int VALUE_COLOR = 0xFFFFFFFF;
	private static final int ACCENT = 0xFFFFE24D;
	private static final int TAB_DIM = 0xFF808080;
	private static final int HOVER_FILL = 0x26FFFFFF;
	private static final int SELECT_FILL = 0x1AFFE24D;
	private static final int HINT_COLOR = 0xFF808080;
	private static final int SUMMARY_COLOR = 0xFF6E6E6E;

	private static final String RESET_LABEL = "RESET ALL SETTINGS";

	/** Remembered across opens within a session. */
	private static int activeTab = 0;
	/** No persistent "current control" — settings change only by hovering + scrolling.
	 *  Kept only so the draw code has a never-matching index (-1). */
	private static final int selected = -1;

	/** Tabs depend on the active device: a survival camera body has no "Rig" (its
	 *  filter is physical) and no "Mod Settings" (config is a creative-only concern). */
	private final Tab[] tabs = buildTabs();

	private static final int MOD_PER_PAGE = 7;
	/** Which page of the (long) Mod Settings tab is showing. Remembered across opens. */
	private static int modPage = 0;

	private static Tab[] buildTabs() {
		// Rig (Movement + panel filters) is Creative-Camera-only: a camera body uses a
		// physical filter, and the drone is a bare aerial camera.
		boolean creative = PhotoModeSession.deviceItem()
				== com.itsthejimjam.realcamera.PhotoMode.CREATIVE_CAMERA;
		java.util.List<Tab> t = new java.util.ArrayList<>();

		t.add(new Tab("Exposure", new Cell[] {
				listCell("Mode", PhotoModeSession.SHOOT_MODE_OPTIONS,
						PhotoModeSession::shootModeIndex, PhotoModeSession::setShootModeIndex,
						PhotoModeSession::stepShootMode, PhotoModeSession::resetShootMode),
				listCell("Aperture", PhotoModeSession.APERTURE_OPTIONS,
						PhotoModeSession::apertureIndex,
						i -> { if (!PhotoModeSession.autoAperture()) PhotoModeSession.setApertureIndex(i); },
						dir -> { if (!PhotoModeSession.autoAperture()) PhotoModeSession.stepAperture(dir); },
						PhotoModeSession::resetAperture),
				listCell("Shutter", PhotoModeSession.SHUTTER_OPTIONS,
						PhotoModeSession::shutterIndex,
						i -> { if (!PhotoModeSession.autoShutter()) PhotoModeSession.setShutterIndex(i); },
						dir -> { if (!PhotoModeSession.autoShutter()) PhotoModeSession.stepShutter(dir); },
						PhotoModeSession::resetShutter),
				listCell("ISO", PhotoModeSession.ISO_MODE_OPTIONS,
						PhotoModeSession::isoModeIndex, PhotoModeSession::setIsoModeIndex,
						PhotoModeSession::stepIsoMode, PhotoModeSession::resetIso),
				listCell("Exposure", PhotoModeSession.EXPOSURE_OPTIONS,
						PhotoModeSession::exposureIndex, PhotoModeSession::setExposureIndex,
						PhotoModeSession::stepExposureComp, PhotoModeSession::resetExposureComp),
				listCell("White Bal", PhotoModeSession.WB_OPTIONS,
						PhotoModeSession::whiteBalanceIndex, PhotoModeSession::setWhiteBalanceIndex,
						PhotoModeSession::stepWhiteBalance, PhotoModeSession::resetWhiteBalance),
				listCell("Bracket", Bracket.FRAMES,
						Bracket::framesIndex, Bracket::setFramesIndex, Bracket::stepFrames, Bracket::resetFrames),
				listCell("EV Step", Bracket.EV_STEPS,
						Bracket::stepIndex, Bracket::setStepIndex, Bracket::stepStep, Bracket::resetStep),
		}));
		t.add(new Tab("Recipes", new Cell[] {
				listCell("Recipe", FilmParams.RECIPE_NAMES,
						PhotoModeSession::getRecipeIndex, PhotoModeSession::setRecipeIndex,
						PhotoModeSession::stepRecipe, PhotoModeSession::resetRecipe),
				listCell("Strength", PhotoModeSession.STRENGTH_OPTIONS,
						PhotoModeSession::strengthIndex, PhotoModeSession::setStrengthIndex,
						PhotoModeSession::stepRecipeStrength, PhotoModeSession::resetRecipeStrength),
		}));

		if (creative) {
			t.add(new Tab("Rig", new Cell[] {
					listCell("Movement", PhotoModeSession.MOVEMENT_OPTIONS,
							PhotoModeSession::movementIndex, PhotoModeSession::setMovementIndex,
							PhotoModeSession::stepMovement, PhotoModeSession::resetMovement),
					listCell("ND Filter", PhotoModeSession.FILTER_ND_OPTIONS,
							PhotoModeSession::filterNdIndex, PhotoModeSession::setFilterNdIndex,
							PhotoModeSession::stepFilterNd, PhotoModeSession::resetFilterNd),
					listCell("Polarizer", PhotoModeSession.PERCENT_OPTIONS,
							PhotoModeSession::filterPolarIndex, PhotoModeSession::setFilterPolarIndex,
							PhotoModeSession::stepFilterPolar, PhotoModeSession::resetFilterPolar),
					listCell("Mist", PhotoModeSession.PERCENT_OPTIONS,
							PhotoModeSession::filterMistIndex, PhotoModeSession::setFilterMistIndex,
							PhotoModeSession::stepFilterMist, PhotoModeSession::resetFilterMist),
			}));
		}

		t.add(new Tab("Frame", new Cell[] {
				listCell("Aspect", Framing.ASPECT_OPTIONS,
						Framing::getAspectIndex, Framing::setAspectIndex,
						Framing::stepAspect, Framing::resetAspect),
				cell("Resolution", Framing::tierLabel, Framing.TIER_OPTIONS,
						Framing::getTierIndex, Framing::setTierIndex,
						Framing::stepTier, Framing::resetTier),
				listCell("Supersample", Framing.SS_OPTIONS,
						Framing::getSsIndex, Framing::setSsIndex,
						Framing::stepSupersample, Framing::resetSupersample),
		}));
		t.add(new Tab("Display", new Cell[] {
				listCell("Grid", DisplayAids.GRID_TYPES,
						DisplayAids::gridIndex, DisplayAids::setGridIndex,
						DisplayAids::stepGrid, DisplayAids::resetGrid),
				listCell("Histogram", DisplayAids.HISTOGRAM_MODES,
						DisplayAids::histogramIndex, DisplayAids::setHistogramIndex,
						DisplayAids::stepHistogram, DisplayAids::resetHistogram),
				listCell("Meter", DisplayAids.TOGGLE,
						DisplayAids::meterIndex, DisplayAids::setMeterIndex,
						DisplayAids::stepMeter, DisplayAids::resetMeter),
				listCell("Metering", PhotoModeSession.METERING_OPTIONS,
						PhotoModeSession::meteringIndex, PhotoModeSession::setMeteringIndex,
						PhotoModeSession::stepMetering, PhotoModeSession::resetMetering),
				listCell("Clip Warning", DisplayAids.TOGGLE,
						DisplayAids::zebrasIndex, DisplayAids::setZebrasIndex,
						DisplayAids::stepZebras, DisplayAids::resetZebras),
				listCell("Focus Peaking", DisplayAids.TOGGLE,
						DisplayAids::peakingIndex, DisplayAids::setPeakingIndex,
						DisplayAids::stepPeaking, DisplayAids::resetPeaking),
				listCell("Overlay", DisplayAids.HUD_ANCHORS,
						DisplayAids::hudAnchorIndex, DisplayAids::setHudAnchorIndex,
						DisplayAids::stepHudAnchor, DisplayAids::resetHudAnchor),
				listCell("Opacity", DisplayAids.HUD_OPACITY,
						DisplayAids::hudOpacityIndex, DisplayAids::setHudOpacityIndex,
						DisplayAids::stepHudOpacity, DisplayAids::resetHudOpacity),
		}));

		if (creative) {
			// The old "Tuning" + "Advanced" tabs, merged. Rarely touched — it's
			// configuration — so it lives behind a page arrow on one tab.
			t.add(new Tab("Mod Settings", new Cell[] {
					knobCell("Blur", PhotoConfig.BLUR_INTENSITY),
					knobCell("Blur Radius", PhotoConfig.BLUR_RADIUS),
					knobCell("BG Blur", PhotoConfig.BACKGROUND_BLUR),
					knobCell("Grain", PhotoConfig.GRAIN_AMOUNT),
					knobCell("Grain Size", PhotoConfig.GRAIN_SIZE),
					listCell("Grain ISO", PhotoConfig.GRAIN_ISO_OPTIONS,
							PhotoConfig::grainOnsetIsoIndex, PhotoConfig::setGrainOnsetIsoIndex,
							PhotoConfig::stepGrainOnsetIso, PhotoConfig::resetGrainOnsetIso),
					knobCell("Focus Soft", PhotoConfig.FOCUS_SOFTNESS),
					knobCell("HL Bloom", PhotoConfig.HIGHLIGHT_BLOOM),
					knobCell("HL Thresh", PhotoConfig.HIGHLIGHT_THRESHOLD),
					knobCell("Blur Onset", PhotoConfig.BLUR_ONSET),
					knobCell("Max Zoom", PhotoConfig.MAX_ZOOM),
					knobCell("Wide FOV", PhotoConfig.WIDEST_FOV),
					knobCell("Base FOV", PhotoConfig.BASE_FOV),
			}));
		}
		return t.toArray(new Tab[0]);
	}

	/**
	 * The Recipes tab when a Custom slot is selected: the first {@link #SIDEBAR_FIRST}
	 * cells stay in the normal bottom row (Recipe, Strength — unchanged from the plain
	 * tab); the rest are the grade controls, drawn as a right-edge sidebar so choosing
	 * a Custom slot doesn't reshuffle the whole row.
	 */
	private static final int SIDEBAR_FIRST = 2;

	private final Cell[] recipeEditorCells = {
			listCell("Recipe", FilmParams.RECIPE_NAMES,
					PhotoModeSession::getRecipeIndex, PhotoModeSession::setRecipeIndex,
					PhotoModeSession::stepRecipe, PhotoModeSession::resetRecipe),
			listCell("Strength", PhotoModeSession.STRENGTH_OPTIONS,
					PhotoModeSession::strengthIndex, PhotoModeSession::setStrengthIndex,
					PhotoModeSession::stepRecipeStrength, PhotoModeSession::resetRecipeStrength),
			// --- sidebar: film-recipe controls ---
			listCell("Copy From", CustomRecipes.copyOptions(),
					() -> 0, CustomRecipes::applyCopyFrom,
					dir -> { }, CustomRecipes::resetActiveSlot),
			listCell("Film Base", FilmParams.FilmBase.LABELS,
					CustomRecipes::baseIndex, CustomRecipes::setBaseIndex,
					CustomRecipes::stepBase, CustomRecipes::resetBase),
			listCell("Dynamic Range", FilmParams.DR.LABELS,
					CustomRecipes::drIndex, CustomRecipes::setDrIndex,
					CustomRecipes::stepDr, CustomRecipes::resetDr),
			paramCell("Highlight", CustomRecipes.Param.HIGHLIGHT),
			paramCell("Shadow", CustomRecipes.Param.SHADOW),
			paramCell("Color", CustomRecipes.Param.COLOR),
			paramCell("Clarity", CustomRecipes.Param.CLARITY),
			listCell("Color Chrome", FilmParams.Tri.LABELS,
					CustomRecipes::chromeIndex, CustomRecipes::setChromeIndex,
					CustomRecipes::stepChrome, CustomRecipes::resetChrome),
			listCell("FX Blue", FilmParams.Tri.LABELS,
					CustomRecipes::fxBlueIndex, CustomRecipes::setFxBlueIndex,
					CustomRecipes::stepFxBlue, CustomRecipes::resetFxBlue),
			listCell("Split Tone", FilmParams.SplitTone.LABELS,
					CustomRecipes::splitIndex, CustomRecipes::setSplitIndex,
					CustomRecipes::stepSplit, CustomRecipes::resetSplit),
			listCell("Split Amount", FilmParams.Tri.LABELS,
					CustomRecipes::splitAmtIndex, CustomRecipes::setSplitAmtIndex,
					CustomRecipes::stepSplitAmt, CustomRecipes::resetSplitAmt),
			paramCell("WB Shift R", CustomRecipes.Param.WB_R),
			paramCell("WB Shift B", CustomRecipes.Param.WB_B),
			listCell("Grain", CustomRecipes.GRAIN_OPTIONS,
					CustomRecipes::grainIndex, CustomRecipes::setGrainIndex,
					CustomRecipes::stepGrain, CustomRecipes::resetGrain),
			paramCell("B&W Tone", CustomRecipes.Param.MONO_TONE),
			paramCell("Fade", CustomRecipes.Param.FADE),
	};

	private static final int SIDEBAR_W = 156;
	private static final int SIDEBAR_ROW_H = 13;
	private static final int SIDEBAR_HEAD_H = 15;
	private static final int SIDEBAR_PAD = 6;
	private static final String SB_RESET_LABEL = "RESET";

	/** "RESET" button bounds in the sidebar header — set by {@link #drawSidebar}. */
	private int sbResetX0;
	private int sbResetX1 = -1;
	private int sbHeadY0;
	private int sbHeadY1 = -1;

	private Cell hoveredCell = null;
	private final int[] tabX = new int[tabs.length * 2];
	private int resetBtnX0 = 0;
	private int resetBtnX1 = -1;

	/** Open pick-a-value list, or null. */
	private Cell openCell = null;
	private int openHover = 0;
	private int openScroll = 0;
	private final int[] popupBounds = new int[4];
	private int lastMouseX = -1;
	private int lastMouseY = -1;

	public PhotoPanelScreen() {
		super(Component.literal("Photo Settings"));
		activeTab = Math.min(activeTab, tabs.length - 1);
	}

	/** {@code {x0, rowsY0, rowCount, bottomY}} for the grade sidebar. */
	private int[] sidebarGeom() {
		int count = currentCells().length - SIDEBAR_FIRST;
		int bottomY = (this.height - PANEL_H) - 4;
		int h = SIDEBAR_HEAD_H + count * SIDEBAR_ROW_H + SIDEBAR_PAD;
		return new int[] {this.width - SIDEBAR_W, bottomY - h + SIDEBAR_HEAD_H, count, bottomY};
	}

	public static boolean isOpen() {
		return Minecraft.getInstance().gui.screen() instanceof PhotoPanelScreen;
	}

	/** Scroll wheel — the only way to change a setting: nudge the control the pointer
	 *  is over by one step (or scroll an open list if the pointer is over it). */
	public static boolean scrollHovered(double dir) {
		if (dir == 0 || !(Minecraft.getInstance().gui.screen() instanceof PhotoPanelScreen panel)) {
			return false;
		}
		if (panel.openCell != null && panel.inPopup(panel.lastMouseX, panel.lastMouseY)) {
			panel.openScroll -= dir > 0 ? 1 : -1;
			CameraSounds.dialTick(dir > 0 ? 0.72f : 0.28f);
			return true;
		}
		Cell c = panel.hoveredCell;
		if (c == null) {
			return false;
		}
		c.step(dir > 0 ? 1 : -1);
		CameraSounds.dialTick(dir > 0 ? 0.72f : 0.28f); // quiet detent; up ticks higher
		return true;
	}

	private Cell[] currentCells() {
		if (isRecipeEditor()) {
			return recipeEditorCells;
		}
		Cell[] all = tabs[activeTab].cells();
		if (isModSettings()) {
			int start = Math.min(modPage, modPageCount() - 1) * MOD_PER_PAGE;
			return java.util.Arrays.copyOfRange(all, start, Math.min(all.length, start + MOD_PER_PAGE));
		}
		return all;
	}

	private boolean isTab(String name) {
		return activeTab < tabs.length && tabs[activeTab].name().equals(name);
	}

	/** True when the Recipes tab is showing a Custom slot, so the grade sidebar is up. */
	private boolean isRecipeEditor() {
		return isTab("Recipes")
				&& CustomRecipes.editingSlot(PhotoModeSession.getRecipeIndex()) >= 0;
	}

	/** The (long, paged) Mod Settings tab — creative camera only. */
	private boolean isModSettings() {
		return isTab("Mod Settings");
	}

	private int modPageCount() {
		int n = tabs[activeTab].cells().length;
		return Math.max(1, (n + MOD_PER_PAGE - 1) / MOD_PER_PAGE);
	}

	/** Right edge of the control row — Mod Settings reserves a strip for the page arrow. */
	private int rowRightEdge() {
		return isModSettings() ? this.width - 28 : this.width;
	}

	/** Aperture / Shutter are camera-driven in P/A/S — shown but not adjustable. */
	private boolean cellLocked(Cell c) {
		if (c == null) {
			return false;
		}
		return ("Aperture".equals(c.label()) && PhotoModeSession.autoAperture())
				|| ("Shutter".equals(c.label()) && PhotoModeSession.autoShutter());
	}

	/** How many of {@link #currentCells()} sit in the bottom row (the rest are sidebar). */
	private int rowCellCount() {
		return isRecipeEditor() ? SIDEBAR_FIRST : currentCells().length;
	}

	private Cell selectedCell() {
		Cell[] cs = currentCells();
		return cs[Math.min(selected, cs.length - 1)];
	}

	private int visibleRows(Cell c) {
		return Math.min(c.options().length, POPUP_MAX_ROWS);
	}

	private void openPopup(Cell c) {
		openCell = c;
		openHover = Mth.clamp(c.index(), 0, c.options().length - 1);
		int rows = visibleRows(c);
		openScroll = Mth.clamp(openHover - rows / 2, 0, Math.max(0, c.options().length - rows));
	}

	private void closePopup() {
		openCell = null;
	}

	private boolean inPopup(double x, double y) {
		return openCell != null && x >= popupBounds[0] && x < popupBounds[2]
				&& y >= popupBounds[1] && y < popupBounds[3];
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		CustomRecipes.save();
		PhotoConfig.saveIfDirty();
		super.onClose();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// Deliberately empty: keep the live scene visible.
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (openCell != null) {
			if (event.isEscape()) {
				closePopup();
				return true;
			}
			int n = openCell.options().length;
			if (event.isUp()) {
				openHover = Mth.clamp(openHover - 1, 0, n - 1);
				followHover();
				return true;
			}
			if (event.isDown()) {
				openHover = Mth.clamp(openHover + 1, 0, n - 1);
				followHover();
				return true;
			}
			if (event.isConfirmation()) {
				openCell.select(openHover);
				closePopup();
				return true;
			}
			return true; // swallow everything else while the list is up
		}

		if (event.isCycleFocus()) { // Tab
			this.onClose();
			return true;
		}
		// Settings change by scrolling the control under the pointer — no keyboard
		// value nudging, no selected-cell concept. Tabs are switched by clicking them.
		return super.keyPressed(event);
	}

	private void followHover() {
		int rows = visibleRows(openCell);
		openScroll = Mth.clamp(openScroll, openHover - rows + 1, openHover);
		openScroll = Mth.clamp(openScroll, 0, Math.max(0, openCell.options().length - rows));
	}

	private void switchTab(int dir) {
		activeTab = Math.floorMod(activeTab + dir, tabs.length);
		modPage = 0;
		closePopup();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		int top = this.height - PANEL_H;
		boolean left = event.button() == 0;
		boolean mid = event.button() == 2;

		Cell wasOpen = openCell;
		if (openCell != null) {
			if (inPopup(mx, my)) {
				if (left) {
					int r = (int) ((my - popupBounds[1] - 1) / POPUP_ITEM_H);
					int oi = openScroll + r;
					if (oi >= 0 && oi < openCell.options().length) {
						openCell.select(oi);
					}
				}
				closePopup();
				return true;
			}
			closePopup();
		}

		if (left && isRecipeEditor()
				&& mx >= sbResetX0 && mx < sbResetX1
				&& my >= sbHeadY0 && my < sbHeadY1) {
			CustomRecipes.resetActiveSlot();
			announce(CustomRecipes.slotName(
					CustomRecipes.editingSlot(PhotoModeSession.getRecipeIndex())) + " reset");
			return true;
		}

		if (mid) {
			Cell hit = cellAt(mx, my);
			if (hit != null) {
				hit.reset();
				CameraSounds.resetClick();
			}
			return true;
		}

		if (my >= top && my < top + TABBAR_H) {
			if (left && mx >= resetBtnX0 && mx < resetBtnX1) {
				PhotoModeSession.resetPhotoSettings();
				Framing.resetOutput();
				DisplayAids.resetAll();
				CameraSounds.resetClick();
				announce("All settings reset");
				return true;
			}
			for (int i = 0; i < tabs.length; i++) {
				if (mx >= tabX[i * 2] && mx < tabX[i * 2 + 1]) {
					if (activeTab != i) {
						modPage = 0;
					}
					activeTab = i;
					break;
				}
			}
			return true;
		}

		// Mod Settings page arrow (right strip of the control row).
		if (left && isModSettings() && my >= top + TABBAR_H && mx >= rowRightEdge()) {
			modPage = (modPage + 1) % modPageCount();
			closePopup();
			return true;
		}

		Cell hit = cellAt(mx, my);
		if (hit != null) {
			// Scroll changes a value; a left click just opens the full list for a
			// long option set (still picked by click or scroll inside it). Camera-driven
			// cells (Aperture/Shutter in P/A/S) don't open.
			if (left && hit != wasOpen && hit.options().length > 2 && !cellLocked(hit)) {
				openPopup(hit);
			}
			return true;
		}

		if (left && my < top) {
			PhotoModeSession.setFocus((float) (mx / this.width), 1.0f - (float) (my / this.height));
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		Font font = this.font;
		this.lastMouseX = mouseX;
		this.lastMouseY = mouseY;
		int top = this.height - PANEL_H;
		int rowTop = top + TABBAR_H;

		graphics.fill(0, top, this.width, this.height, BG);
		graphics.fill(0, top, this.width, top + 1, BORDER);
		graphics.fill(0, rowTop, this.width, rowTop + 1, DIVIDER);

		// --- tab bar ---
		int tx = 10;
		for (int i = 0; i < tabs.length; i++) {
			String name = tabs[i].name().toUpperCase(Locale.ROOT);
			int w = font.width(name);
			boolean hot = i == activeTab;
			graphics.text(font, name, tx, top + 5, hot ? ACCENT : TAB_DIM, false);
			if (hot) {
				graphics.fill(tx, top + TABBAR_H - 2, tx + w, top + TABBAR_H - 1, ACCENT);
			}
			tabX[i * 2] = tx - 4;
			tabX[i * 2 + 1] = tx + w + 4;
			tx += w + 18;
		}

		int rlw = font.width(RESET_LABEL);
		int rlx = this.width - rlw - 10;
		boolean resetHot = mouseX >= rlx - 4 && mouseX < this.width - 6
				&& mouseY >= top && mouseY < top + TABBAR_H;
		graphics.text(font, RESET_LABEL, rlx, top + 5, resetHot ? ACCENT : TAB_DIM, false);
		resetBtnX0 = rlx - 4;
		resetBtnX1 = this.width - 6;

		// --- at-a-glance settings, on its own line just above the panel ---
		String summary = summaryLine();
		graphics.text(font, summary, (this.width - font.width(summary)) / 2, top - 11, SUMMARY_COLOR, true);

		// --- controls for the active tab ---
		hoveredCell = cellAt(mouseX, mouseY);
		Cell[] cs = currentCells();
		int n = cs.length;
		int sel = Math.min(selected, n - 1);
		int rowN = rowCellCount();
		int rowRight = rowRightEdge();
		for (int i = 0; i < rowN; i++) {
			int x0 = Math.round(i * rowRight / (float) rowN);
			int x1 = Math.round((i + 1) * rowRight / (float) rowN);
			int cx = (x0 + x1) / 2;
			Cell c = cs[i];
			boolean hov = c == hoveredCell;
			boolean isSel = i == sel;
			boolean isOpen = c == openCell;

			if (hov || isOpen) {
				graphics.fill(x0, rowTop + 1, x1, this.height, HOVER_FILL);
			} else if (isSel) {
				graphics.fill(x0, rowTop + 1, x1, this.height, SELECT_FILL);
			}
			if (i > 0) {
				graphics.fill(x0, rowTop + 6, x0 + 1, this.height - 6, DIVIDER);
			}

			String label = c.label().toUpperCase(Locale.ROOT);
			boolean locked = cellLocked(c);
			boolean active = (hov || isSel || isOpen) && !locked;
			String value = locked ? c.value() + " · A" : (active ? "‹ " + c.value() + " ›" : c.value());
			graphics.text(font, label, cx - font.width(label) / 2, rowTop + 9, LABEL_COLOR, false);
			graphics.text(font, value, cx - font.width(value) / 2, rowTop + 22,
					locked ? HINT_COLOR : (active ? ACCENT : VALUE_COLOR), true);
		}

		// --- Mod Settings: page arrow on the right ---
		if (isModSettings()) {
			boolean aHot = mouseX >= rowRight && mouseY >= rowTop;
			graphics.fill(rowRight, rowTop + 1, this.width, this.height, aHot ? HOVER_FILL : SELECT_FILL);
			graphics.fill(rowRight, rowTop + 6, rowRight + 1, this.height - 6, DIVIDER);
			int acx = (rowRight + this.width) / 2;
			String arrow = modPage == 0 ? "›" : "‹";      // flips so it reads "go back"
			graphics.text(font, arrow, acx - font.width(arrow) / 2, rowTop + 12,
					aHot ? ACCENT : VALUE_COLOR, false);
			String pg = (modPage + 1) + "/" + modPageCount();
			graphics.text(font, pg, acx - font.width(pg) / 2, rowTop + 26, LABEL_COLOR, false);
		}

		if (isRecipeEditor()) {
			drawSidebar(graphics, font, cs, sel);
		}

		if (openCell != null) {
			drawPopup(graphics, font, top, mouseX, mouseY);
		}
	}

	private void drawSidebar(GuiGraphicsExtractor graphics, Font font, Cell[] cs, int sel) {
		int[] g = sidebarGeom();
		int x0 = g[0];
		int rowsY0 = g[1];
		int count = g[2];
		int bottomY = g[3];
		int y0 = rowsY0 - SIDEBAR_HEAD_H;

		graphics.fill(x0, y0, this.width, bottomY, POPUP_BG);
		graphics.fill(x0, y0, this.width, y0 + 1, BORDER);
		graphics.fill(x0, bottomY - 1, this.width, bottomY, BORDER);
		graphics.fill(x0, y0, x0 + 1, bottomY, BORDER);

		int slot = CustomRecipes.editingSlot(PhotoModeSession.getRecipeIndex());
		graphics.text(font, CustomRecipes.slotName(slot).toUpperCase(Locale.ROOT),
				x0 + SIDEBAR_PAD, y0 + 5, ACCENT, false);

		int rlw = font.width(SB_RESET_LABEL);
		sbResetX1 = this.width - SIDEBAR_PAD;
		sbResetX0 = sbResetX1 - rlw - 4;
		sbHeadY0 = y0;
		sbHeadY1 = rowsY0;
		boolean resetHot = lastMouseX >= sbResetX0 && lastMouseX < sbResetX1
				&& lastMouseY >= sbHeadY0 && lastMouseY < sbHeadY1;
		graphics.text(font, SB_RESET_LABEL, sbResetX0 + 2, y0 + 5,
				resetHot ? ACCENT : TAB_DIM, false);

		for (int r = 0; r < count; r++) {
			int ci = SIDEBAR_FIRST + r;
			Cell c = cs[ci];
			int ry = rowsY0 + r * SIDEBAR_ROW_H;
			boolean active = c == hoveredCell || ci == sel || c == openCell;
			if (c == hoveredCell || c == openCell) {
				graphics.fill(x0 + 1, ry, this.width, ry + SIDEBAR_ROW_H, HOVER_FILL);
			} else if (ci == sel) {
				graphics.fill(x0 + 1, ry, this.width, ry + SIDEBAR_ROW_H, SELECT_FILL);
			}
			String label = c.label().toUpperCase(Locale.ROOT);
			String value = c.value();
			graphics.text(font, label, x0 + SIDEBAR_PAD, ry + 3, LABEL_COLOR, false);
			graphics.text(font, value, this.width - SIDEBAR_PAD - font.width(value), ry + 3,
					active ? ACCENT : VALUE_COLOR, false);
		}
	}

	/** Sidebar row under the pointer, or null. */
	private Cell sidebarCellAt(double mx, double my) {
		if (!isRecipeEditor()) {
			return null;
		}
		int[] g = sidebarGeom();
		if (mx < g[0] || mx >= this.width || my < g[1] || my >= g[3]) {
			return null;
		}
		int r = (int) Math.floor((my - g[1]) / (double) SIDEBAR_ROW_H);
		if (r < 0 || r >= g[2]) {
			return null;
		}
		return currentCells()[SIDEBAR_FIRST + r];
	}

	private void drawPopup(GuiGraphicsExtractor graphics, Font font, int panelTop, int mouseX, int mouseY) {
		Cell[] cs = currentCells();
		int idx = -1;
		for (int i = 0; i < cs.length; i++) {
			if (cs[i] == openCell) {
				idx = i;
			}
		}
		if (idx < 0) {
			closePopup();
			return;
		}

		String[] opts = openCell.options();
		int rows = Math.min(opts.length, POPUP_MAX_ROWS);
		openScroll = Mth.clamp(openScroll, 0, Math.max(0, opts.length - rows));
		int ph = rows * POPUP_ITEM_H + 2;

		int pw;
		int px;
		int py0;
		int py1;
		if (isRecipeEditor() && idx >= SIDEBAR_FIRST) {
			// alongside the sidebar row, opening to its left
			int[] g = sidebarGeom();
			int rowY = g[1] + (idx - SIDEBAR_FIRST) * SIDEBAR_ROW_H;
			pw = Math.max(POPUP_MIN_W, SIDEBAR_W - 12);
			px = Math.max(2, this.width - SIDEBAR_W - pw - 2);
			py0 = Mth.clamp(rowY - ph / 2, 2, panelTop - 2 - ph);
			py1 = py0 + ph;
		} else {
			int rn = rowCellCount();
			int rowRight = rowRightEdge();
			int cx0 = Math.round(idx * rowRight / (float) rn);
			int cx1 = Math.round((idx + 1) * rowRight / (float) rn);
			pw = Math.max(cx1 - cx0, POPUP_MIN_W);
			px = Math.min(cx0, this.width - pw);
			py1 = panelTop - 2;
			py0 = py1 - ph;
		}

		graphics.fill(px, py0, px + pw, py1, POPUP_BG);
		graphics.fill(px, py0, px + pw, py0 + 1, BORDER);
		graphics.fill(px, py1 - 1, px + pw, py1, BORDER);

		for (int r = 0; r < rows; r++) {
			int oi = openScroll + r;
			int iy = py0 + 1 + r * POPUP_ITEM_H;
			boolean cur = oi == openCell.index();
			boolean khov = oi == openHover;
			boolean mhov = mouseX >= px && mouseX < px + pw && mouseY >= iy && mouseY < iy + POPUP_ITEM_H;
			if (mhov || khov) {
				graphics.fill(px, iy, px + pw, iy + POPUP_ITEM_H, HOVER_FILL);
			} else if (cur) {
				graphics.fill(px, iy, px + pw, iy + POPUP_ITEM_H, SELECT_FILL);
			}
			graphics.text(font, opts[oi], px + 6, iy + 2, cur ? ACCENT : VALUE_COLOR, false);
		}

		// more-above / more-below ticks
		if (openScroll > 0) {
			graphics.fill(px + pw - 4, py0 + 2, px + pw - 2, py0 + 4, VALUE_COLOR);
		}
		if (openScroll + rows < opts.length) {
			graphics.fill(px + pw - 4, py1 - 4, px + pw - 2, py1 - 2, VALUE_COLOR);
		}

		popupBounds[0] = px;
		popupBounds[1] = py0;
		popupBounds[2] = px + pw;
		popupBounds[3] = py1;
	}

	private void announce(String text) {
		if (this.minecraft.player != null) {
			this.minecraft.player.sendOverlayMessage(Component.literal(text));
		}
	}

	private String summaryLine() {
		int wbK = PhotoModeSession.getWhiteBalanceKelvin();
		String wb = wbK == 5500 ? "" : " · " + wbK + "K";
		String leStr = Bracket.on()
				? " · BRKT " + Bracket.frames() + "x" + Bracket.evStep() + "EV"
				: PhotoModeSession.willLongExpose() ? " · LONG" : "";
		String rec = PhotoModeSession.getRecipeIndex() == 0 ? "" : "  ·  " + PhotoModeSession.getRecipeName();
		String mode = PhotoModeSession.shootModeIndex() == 3 ? "" : PhotoModeSession.shootModeLabel() + "  ·  ";
		return String.format(Locale.ROOT, "%sf/%s · %s · ISO %s%s%s%s  ·  %s · %s",
				mode, PhotoKeys.fstop(), PhotoModeSession.getShutterLabel(), PhotoModeSession.isoDisplay(),
				wb, leStr, rec, Framing.aspect().label(), Framing.tierLabel());
	}

	/** Cell under the given screen coords — grade sidebar first, then the bottom row. */
	private Cell cellAt(double mx, double my) {
		Cell sb = sidebarCellAt(mx, my);
		if (sb != null) {
			return sb;
		}
		int rowTop = this.height - PANEL_H + TABBAR_H;
		int rowRight = rowRightEdge();
		if (my < rowTop || my > this.height || mx < 0 || mx >= rowRight) {
			return null;                       // beyond rowRight is the page-arrow strip
		}
		Cell[] cs = currentCells();
		int rn = rowCellCount();
		int i = (int) (mx / (rowRight / (double) rn));
		return cs[Math.min(Math.max(i, 0), rn - 1)];
	}

	private static Cell listCell(String label, String[] options, IntSupplier index,
			IntConsumer select, IntConsumer step, Runnable reset) {
		return cell(label, () -> options[Mth.clamp(index.getAsInt(), 0, options.length - 1)],
				options, index, select, step, reset);
	}

	/** A cell backed by a {@link PhotoConfig.Knob} (live tuning value). */
	private static Cell knobCell(String label, PhotoConfig.Knob k) {
		return listCell(label, k.options, k::index, k::select, k::step, k::reset);
	}

	/** A custom-recipe grade control, backed by a {@link CustomRecipes.Param}. */
	private static Cell paramCell(String label, CustomRecipes.Param p) {
		return listCell(label, p.options(),
				() -> CustomRecipes.paramIndex(p),
				i -> CustomRecipes.setParamIndex(p, i),
				dir -> CustomRecipes.stepParam(p, dir),
				() -> CustomRecipes.resetParam(p));
	}

	private static Cell cell(String label, Supplier<String> value, String[] options,
			IntSupplier index, IntConsumer select, IntConsumer step, Runnable reset) {
		return new Cell() {
			@Override
			public String label() {
				return label;
			}

			@Override
			public String value() {
				return value.get();
			}

			@Override
			public String[] options() {
				return options;
			}

			@Override
			public int index() {
				return Mth.clamp(index.getAsInt(), 0, options.length - 1);
			}

			@Override
			public void select(int i) {
				select.accept(i);
			}

			@Override
			public void step(int dir) {
				step.accept(dir);
			}

			@Override
			public void reset() {
				reset.run();
			}
		};
	}
}
