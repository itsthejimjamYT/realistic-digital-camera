package com.itsthejimjam.realcamera.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.itsthejimjam.realcamera.PhotoMode;

/**
 * Reflective bridge to Iris. Iris is not a compile or runtime dependency — pack
 * detection and scene-depth access both go through reflection against its public (and,
 * for depth, internal) API, so the mod runs fine with or without it installed.
 *
 * <p>1.21.1-era Iris has no {@code GpuTexture}/{@code GpuTextureView} abstraction (that's
 * 26.2's newer rendering API) — {@code RenderTargets.getDepthTexture()} and
 * {@code DepthTexture.getTextureId()} hand back a raw GL texture id directly, which is
 * exactly what {@link net.minecraft.client.renderer.EffectInstance#setSampler} wants.
 *
 * <p>Iris's own depth textures use STANDARD (non-reversed) depth — 0 at the camera, 1 at
 * the far plane / sky — the same convention as vanilla 1.21.1's own main depth buffer, so
 * {@code realcamera_dof.fsh} and {@code realcamera_dof_shaderpack.fsh} read depth identically
 * and differ only in where the texture comes from.
 */
public final class ShaderPackCompat {
	private static Boolean present;
	private static Object apiInstance;
	private static Method isShaderPackInUse;

	private static Method getPipelineManager;
	private static Method getPipelineNullable;
	private static Class<?> pipelineClass;
	private static Field renderTargetsField;
	private static Method getDepthTextureNoTranslucents; // depthtex1: solid geometry, stable
	private static Method getCurrentWidth;
	private static Method getCurrentHeight;
	private static Method depthTexGetId;
	private static boolean reflectReady;

	private ShaderPackCompat() {
	}

	private static synchronized void init() {
		if (present != null) {
			return;
		}
		try {
			Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			apiInstance = api.getMethod("getInstance").invoke(null);
			isShaderPackInUse = api.getMethod("isShaderPackInUse");
			present = Boolean.TRUE;
			PhotoMode.LOGGER.info("[Photo Mode] shader-pack API found");
		} catch (Throwable t) {
			present = Boolean.FALSE;
			PhotoMode.LOGGER.warn("[Photo Mode] shader-pack API not found: {}", t.toString());
			return;
		}
		try {
			Class<?> loader = Class.forName("net.irisshaders.iris.Iris");
			getPipelineManager = loader.getMethod("getPipelineManager");
			Class<?> pm = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
			getPipelineNullable = pm.getMethod("getPipelineNullable");
			pipelineClass = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
			renderTargetsField = pipelineClass.getDeclaredField("renderTargets");
			renderTargetsField.setAccessible(true);
			Class<?> rt = Class.forName("net.irisshaders.iris.targets.RenderTargets");
			getDepthTextureNoTranslucents = rt.getMethod("getDepthTextureNoTranslucents");
			getCurrentWidth = rt.getMethod("getCurrentWidth");
			getCurrentHeight = rt.getMethod("getCurrentHeight");
			Class<?> depthTex = Class.forName("net.irisshaders.iris.targets.DepthTexture");
			depthTexGetId = depthTex.getMethod("getTextureId");
			reflectReady = true;
			PhotoMode.LOGGER.info("[Photo Mode] shader-pack depth reflection ready");
		} catch (Throwable t) {
			reflectReady = false;
			PhotoMode.LOGGER.warn("[Photo Mode] shader-pack depth reflection failed: {}", t.toString());
		}
	}

	private static boolean activeLogged;

	/** Re-arm the one-shot diagnostic log lines below for a fresh photo-mode session. */
	public static void resetActiveLog() {
		activeLogged = false;
	}

	public static void resetDebug() {
		debugLogged = false;
	}

	/** True only when Iris is installed AND a pack is currently loaded. */
	public static boolean shaderPackActive() {
		init();
		boolean result;
		if (!present) {
			result = false;
		} else {
			try {
				result = (Boolean) isShaderPackInUse.invoke(apiInstance);
			} catch (Throwable t) {
				result = false;
				if (!activeLogged) {
					PhotoMode.LOGGER.warn("[Photo Mode] isShaderPackInUse threw: {}", t.toString());
				}
			}
		}
		if (!activeLogged) {
			activeLogged = true;
			PhotoMode.LOGGER.info("[Photo Mode] shaderPackActive() = {} (apiPresent={})", result, present);
		}
		return result;
	}

	private static Object currentRenderTargets() {
		if (!reflectReady) {
			return null;
		}
		try {
			Object manager = getPipelineManager.invoke(null);
			Object pipeline = getPipelineNullable.invoke(manager);
			if (pipeline == null || !pipelineClass.isInstance(pipeline)) {
				return null;
			}
			return renderTargetsField.get(pipeline);
		} catch (Throwable t) {
			return null;
		}
	}

	private static boolean debugLogged;

	/** The shader pack's current scene-depth GL texture id (solid geometry, stable across
	 *  translucent passes — matches 26.2's default {@code NO_TRANSLUCENTS} pick), or -1 if
	 *  unavailable. Re-queried every call: the pack resizes its targets in place on a
	 *  framebuffer change (e.g. our high-res capture resize), so a cached id can go stale. */
	public static int sceneDepthTextureId() {
		init();
		Object targets = currentRenderTargets();
		if (targets == null) {
			if (!debugLogged) {
				debugLogged = true;
				PhotoMode.LOGGER.warn("[Photo Mode] shader-pack renderTargets == null (reflectReady={})", reflectReady);
			}
			return -1;
		}
		try {
			Object depthTex = getDepthTextureNoTranslucents.invoke(targets);
			int id = (Integer) depthTexGetId.invoke(depthTex);
			if (!debugLogged) {
				debugLogged = true;
				PhotoMode.LOGGER.info("[Photo Mode] shader-pack depth texture id={}", id);
			}
			return id;
		} catch (Throwable t) {
			if (!debugLogged) {
				debugLogged = true;
				PhotoMode.LOGGER.warn("[Photo Mode] shader-pack depth texture lookup failed: {}", t.toString());
			}
			return -1;
		}
	}

	/** Current shader-pack render-target dimensions as {width, height}, or null. */
	public static int[] sceneDepthSize() {
		Object targets = currentRenderTargets();
		if (targets == null) {
			return null;
		}
		try {
			int w = (Integer) getCurrentWidth.invoke(targets);
			int h = (Integer) getCurrentHeight.invoke(targets);
			return new int[] {w, h};
		} catch (Throwable t) {
			return null;
		}
	}
}
