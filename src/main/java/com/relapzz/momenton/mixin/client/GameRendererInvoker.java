package com.relapzz.momenton.mixin.client;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Field of view is computed privately inside the renderer, but it changes with sprinting, speed
 * effects and item use, so a replay that ignores it does not match what was on screen.
 */
@Mixin(GameRenderer.class)
public interface GameRendererInvoker {
	@Invoker("getFov")
	float callGetFov(Camera camera, float tickProgress, boolean changingFov);
}
