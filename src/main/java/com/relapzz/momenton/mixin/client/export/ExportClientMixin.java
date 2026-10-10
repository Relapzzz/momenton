package com.relapzz.momenton.mixin.client.export;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.relapzz.momenton.export.EncoderProbe;
import com.relapzz.momenton.export.ExportSession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.RunArgs;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class ExportClientMixin {
	@Shadow
	private volatile boolean paused;

	@ModifyExpressionValue(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/render/RenderTickCounter$Dynamic;beginRenderTick(JZ)I"))
	private int momenton$exportTicks(int ticks) {
		return ExportSession.ticks(ticks);
	}

	@ModifyArg(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/render/GameRenderer;render(Lnet/minecraft/client/render/RenderTickCounter;Z)V"))
	private RenderTickCounter momenton$exportCounter(RenderTickCounter counter) {
		return ExportSession.counter(counter);
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void momenton$probeEncoders(RunArgs args, CallbackInfo info) {
		EncoderProbe.start();
	}

	@Inject(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/render/GameRenderer;render(Lnet/minecraft/client/render/RenderTickCounter;Z)V"))
	private void momenton$exportRenderStart(boolean tick, CallbackInfo info) {
		ExportSession.beforeRender();
	}

	@Inject(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/render/GameRenderer;render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
			shift = At.Shift.AFTER))
	private void momenton$exportFrame(boolean tick, CallbackInfo info) {
		ExportSession.afterRender();
	}

	@Inject(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/render/RenderTickCounter$Dynamic;tick(Z)V"))
	private void momenton$tickWhileSettling(boolean tick, CallbackInfo info) {
		if (ExportSession.isSettling()) {
			this.paused = false;
		}
	}

	@ModifyExpressionValue(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/option/InactivityFpsLimiter;update()I"))
	private int momenton$unlimitedWhileExporting(int fps) {
		return ExportSession.isExporting() ? Integer.MAX_VALUE : fps;
	}

	@ModifyReturnValue(method = "getRenderTickCounter", at = @At("RETURN"))
	private RenderTickCounter momenton$exportCounterGetter(RenderTickCounter counter) {
		return ExportSession.counter(counter);
	}

	@ModifyArg(method = "onResolutionChanged", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/util/Window;calculateScaleFactor(IZ)I"), index = 0)
	private int momenton$exportGuiScale(int guiScale) {
		return ExportSession.guiScale(guiScale);
	}
}
