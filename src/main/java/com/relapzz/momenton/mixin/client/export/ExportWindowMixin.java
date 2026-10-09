package com.relapzz.momenton.mixin.client.export;

import com.relapzz.momenton.export.ExportSession;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public class ExportWindowMixin {
	@Shadow
	private boolean zeroWidthOrHeight;

	@Inject(method = "onFramebufferSizeChanged", at = @At("HEAD"), cancellable = true)
	private void momenton$keepExportSize(long window, int width, int height, CallbackInfo info) {
		if (ExportSession.isExporting()) {
			zeroWidthOrHeight = width == 0 || height == 0;
			info.cancel();
		}
	}
}
