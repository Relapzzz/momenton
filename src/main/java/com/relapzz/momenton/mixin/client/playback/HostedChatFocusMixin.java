package com.relapzz.momenton.mixin.client.playback;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.relapzz.momenton.ui.editor.EditSession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChatHud.class)
public class HostedChatFocusMixin {
	@Shadow
	@Final
	MinecraftClient client;

	@ModifyReturnValue(method = "isChatFocused", at = @At("RETURN"))
	private boolean momenton$hostedChatFocused(boolean focused) {
		return focused || EditSession.replayScreen(client) instanceof ChatScreen;
	}
}
