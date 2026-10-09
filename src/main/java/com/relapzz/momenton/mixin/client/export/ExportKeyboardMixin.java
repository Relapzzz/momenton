package com.relapzz.momenton.mixin.client.export;

import com.relapzz.momenton.export.ExportSession;
import net.minecraft.client.Keyboard;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public class ExportKeyboardMixin {
	@Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
	private void momenton$escCancelsExport(long window, int action, KeyInput input, CallbackInfo info) {
		if (ExportSession.isExporting()) {
			if (action == GLFW.GLFW_PRESS && input.key() == GLFW.GLFW_KEY_ESCAPE) {
				ExportSession.cancel();
			}
			info.cancel();
		}
	}
}
