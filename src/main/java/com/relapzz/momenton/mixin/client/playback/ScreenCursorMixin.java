package com.relapzz.momenton.mixin.client.playback;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.relapzz.momenton.format.FrameTrack;
import com.relapzz.momenton.playback.PovDriver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public class ScreenCursorMixin {
	@Shadow
	@Final
	private MinecraftClient client;

	@ModifyExpressionValue(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Mouse;getScaledX(Lnet/minecraft/client/util/Window;)D"))
	private double momenton$recordedCursorX(double x, @Local(argsOnly = true) RenderTickCounter counter) {
		FrameTrack.Pose pose = PovDriver.screenCursor(counter.getTickProgress(true));
		return pose == null ? x : pose.mouseX() * client.getWindow().getScaledWidth();
	}

	@ModifyExpressionValue(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Mouse;getScaledY(Lnet/minecraft/client/util/Window;)D"))
	private double momenton$recordedCursorY(double y, @Local(argsOnly = true) RenderTickCounter counter) {
		FrameTrack.Pose pose = PovDriver.screenCursor(counter.getTickProgress(true));
		return pose == null ? y : pose.mouseY() * client.getWindow().getScaledHeight();
	}
}
