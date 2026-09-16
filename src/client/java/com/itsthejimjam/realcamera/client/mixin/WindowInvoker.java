package com.itsthejimjam.realcamera.client.mixin;

import com.mojang.blaze3d.platform.Window;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches {@code Window.setWindowSizeAndPosition} — a pure resize+reposition (SDL
 * {@code SDL_SetWindowSize}/{@code SDL_SetWindowPosition}, confirmed via bytecode: it
 * touches only x/y/width/height, never {@code fullscreenRequested} or any other
 * display-mode field). {@code Window.setWindowed(int, int)}, the public alternative,
 * unconditionally sets {@code fullscreenRequested = false} before resizing — fine for a
 * genuine "switch to windowed" action, but it silently knocked a borderless-fullscreen
 * session into plain windowed mode every time a capture resized the window, since
 * {@link com.itsthejimjam.realcamera.client.PhotoCapture} only ever wants a resize, not
 * a mode change.
 */
@Mixin(Window.class)
public interface WindowInvoker {
	@Invoker("setWindowSizeAndPosition")
	boolean realcamera$setWindowSizeAndPosition(int x, int y, int width, int height);
}
