package com.itsthejimjam.realcamera.client.mixin;

import java.util.List;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code PostChain}'s private pass list, so {@link com.itsthejimjam.realcamera.client.DofParams}
 * / {@code ExposureParams} / {@code FilmParams} / {@code AidParams} can reach each pass's
 * {@code EffectInstance} and push live uniform values every frame.
 *
 * <p>1.20.4 has no {@code GpuBuffer}-backed custom uniform blocks to swap in (that's a
 * 26.2/Blaze3D concept) — each pass's {@code EffectInstance.getUniform(name).set(...)} is
 * called directly instead, so there's no {@code PostPassAccessor} equivalent needed here.
 */
@Mixin(PostChain.class)
public interface PostChainAccessor {
	@Accessor("passes")
	List<PostPass> realcamera$passes();
}
