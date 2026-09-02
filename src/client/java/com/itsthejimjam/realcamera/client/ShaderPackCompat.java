package com.itsthejimjam.realcamera.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.itsthejimjam.realcamera.PhotoMode;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * Reflective bridge to a shader-pack rendering pipeline. The pipeline is not a compile
 * or runtime dependency — pack detection and scene-depth access both go through
 * reflection against its public API, so the mod runs fine with or without one.
 *
 * <p>The fully-qualified class names below are that public API, resolved by string at
 * runtime; there is no build- or load-time link to it.
 */
public final class ShaderPackCompat {
	private static Boolean present;
	private static Object apiInstance;
	private static Method isShaderPackInUse;

	private static Method getPipelineManager;
	private static Method getPipelineNullable;
	private static Class<?> pipelineClass;
	private static Field renderTargetsField;
	private static Method getDepthTexture;             // depthtex0: full, cleared each frame
	private static Method getDepthTextureNoTranslucents; // depthtex1: solid geometry, stable
	private static Method getDepthTextureNoHand;         // depthtex2: solid, no hand
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
			getDepthTexture = rt.getMethod("getDepthTexture");
			getDepthTextureNoTranslucents = rt.getMethod("getDepthTextureNoTranslucents");
			getDepthTextureNoHand = rt.getMethod("getDepthTextureNoHand");
			reflectReady = true;
			PhotoMode.LOGGER.info("[Photo Mode] shader-pack depth reflection ready");
		} catch (Throwable t) {
			reflectReady = false;
			PhotoMode.LOGGER.warn("[Photo Mode] shader-pack depth reflection failed: {}", t.toString());
		}
	}

	private static boolean activeLogged;

	public static void resetActiveLog() {
		activeLogged = false;
	}

	/** True only when a shader-pack renderer is installed AND a pack is currently loaded. */
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

	/** Which shader-pack depth buffer to sample. Tuned from testing. */
	public enum DepthKind { FULL, NO_TRANSLUCENTS, NO_HAND }

	public static DepthKind depthKind = DepthKind.NO_TRANSLUCENTS;

	/** The shader pack's current scene depth texture, or null if unavailable. */
	public static GpuTexture sceneDepthTexture() {
		init();
		if (!reflectReady) {
			return null;
		}
		try {
			Object manager = getPipelineManager.invoke(null);
			Object pipeline = getPipelineNullable.invoke(manager);
			if (pipeline == null || !pipelineClass.isInstance(pipeline)) {
				return null;
			}
			Object targets = renderTargetsField.get(pipeline);
			if (targets == null) {
				return null;
			}
			Method m = switch (depthKind) {
				case FULL -> getDepthTexture;
				case NO_TRANSLUCENTS -> getDepthTextureNoTranslucents;
				case NO_HAND -> getDepthTextureNoHand;
			};
			return (GpuTexture) m.invoke(targets);
		} catch (Throwable t) {
			return null;
		}
	}

	private static GpuTexture cachedTexture;
	private static GpuTextureView cachedView;
	private static boolean debugLogged;

	public static void resetDebug() {
		debugLogged = false;
	}

	/**
	 * A fresh texture view of the shader pack's current scene depth. Rebuilt every call
	 * rather than cached: the pack resizes its targets in place on a framebuffer change
	 * (e.g. when we inflate the window for a high-res capture), and a cached view then
	 * points at a stale binding — which showed up as smeared depth in captures.
	 */
	public static GpuTextureView sceneDepthView() {
		GpuTexture tex = sceneDepthTexture();
		if (!debugLogged) {
			debugLogged = true;
			if (tex == null) {
				PhotoMode.LOGGER.warn("[Photo Mode] sceneDepthTexture() == null (reflectReady={})", reflectReady);
			} else {
				PhotoMode.LOGGER.info("[Photo Mode] shader-pack depth texture {} {}x{} fmt={}",
						tex.getLabel(), tex.getWidth(0), tex.getHeight(0), tex.getFormat());
			}
		}
		if (tex == null) {
			return null;
		}
		try {
			if (cachedView != null && !cachedView.isClosed()) {
				cachedView.close();
			}
			cachedView = RenderSystem.getDevice().createTextureView(tex);
			cachedTexture = tex;
			return cachedView;
		} catch (Throwable t) {
			PhotoMode.LOGGER.warn("[Photo Mode] createTextureView on shader-pack depth failed: {}", t.toString());
			return null;
		}
	}

	/** Current shader-pack scene-depth dimensions as {width, height}, or null. */
	public static int[] sceneDepthSize() {
		GpuTexture tex = sceneDepthTexture();
		return tex == null ? null : new int[] {tex.getWidth(0), tex.getHeight(0)};
	}
}
