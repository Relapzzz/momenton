package com.relapzz.momenton.mixin.client.export;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.relapzz.momenton.export.ExportSession;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.toast.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public class ExportGameRendererMixin {
	@ModifyExpressionValue(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;isWindowFocused()Z"))
	private boolean momenton$noPauseMenuWhileExporting(boolean focused) {
		return focused || ExportSession.isExporting();
	}

	@WrapWithCondition(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/toast/ToastManager;draw(Lnet/minecraft/client/gui/DrawContext;)V"))
	private boolean momenton$noToastsWhileExporting(ToastManager toasts, DrawContext context) {
		return !ExportSession.isExporting();
	}

	@WrapOperation(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/Screen;renderWithTooltip(Lnet/minecraft/client/gui/DrawContext;IIF)V"))
	private void momenton$drawExportCursor(Screen screen, DrawContext context, int mouseX, int mouseY, float deltaTicks,
			Operation<Void> original) {
		original.call(screen, context, mouseX, mouseY, deltaTicks);
		if (ExportSession.drawsCursor()) {
			ExportSession.drawCursor(context, mouseX, mouseY);
		}
	}
}
