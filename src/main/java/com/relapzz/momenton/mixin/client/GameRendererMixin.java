package com.relapzz.momenton.mixin.client;

import com.relapzz.momenton.record.Recorder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Samples the camera once per rendered frame.
 *
 * <p>At TAIL the camera holds the pose this frame was drawn with, which is the whole point of the
 * frame track: mouse-look happens at frame rate and the network only carries rotation at 20 Hz.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("TAIL"))
	private void momenton$recordFrame(RenderTickCounter counter, boolean tick, CallbackInfo info) {
		Recorder.onFrame(MinecraftClient.getInstance(), counter);
	}
}
