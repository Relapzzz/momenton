package com.relapzz.momenton.mixin.client.playback;

import com.relapzz.momenton.playback.ReplaySession;
import com.relapzz.momenton.ui.ReplayBrowserScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(MinecraftClient.class)
public class ReplayExitScreenMixin {
	@ModifyArg(method = "disconnect(Lnet/minecraft/text/Text;)V",
			slice = @Slice(from = @At(value = "NEW", target = "net/minecraft/client/gui/screen/multiplayer/MultiplayerScreen")),
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;setScreen(Lnet/minecraft/client/gui/screen/Screen;)V"))
	private Screen momenton$backToReplays(Screen screen) {
		return ReplaySession.isPlaying() ? new ReplayBrowserScreen(new TitleScreen()) : screen;
	}
}
