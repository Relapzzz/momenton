package com.relapzz.momenton.mixin.client.playback;

import com.relapzz.momenton.ui.editor.EditSession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class EditorHostMixin {
	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
	private void momenton$hostReplayScreen(@Nullable Screen screen, CallbackInfo info) {
		if (EditSession.host((MinecraftClient) (Object) this, screen)) info.cancel();
	}
}
