package com.itsthejimjam.realcamera.client;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.lighting.LevelLightEngine;

/** Render data for {@link TripodBlockEntityRenderer}. */
public class TripodRenderState extends BlockEntityRenderState {
	public boolean hidden;
	public boolean hasCamera;
	/** A big white tele: it carries its own tripod foot on the barrel, so rest the rig
	 *  on the plate from there (lens over the head, body cantilevered back). */
	public boolean footLens;
	public Direction facing = Direction.NORTH;
	public final ItemStackRenderState camera = new ItemStackRenderState();
	public Holder<Biome> biome;
	public CardinalLighting cardinalLighting = CardinalLighting.DEFAULT;
	public LevelLightEngine lightEngine = LevelLightEngine.EMPTY;
}
