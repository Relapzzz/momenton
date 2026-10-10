package com.relapzz.momenton.playback;

import com.relapzz.momenton.config.MomentonKeybinds;
import com.relapzz.momenton.mixin.client.ChatScreenAccessor;
import com.relapzz.momenton.mixin.client.TextFieldWidgetAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;

public final class ReplayScreenInput {
	private ReplayScreenInput() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (ReplaySession.isPlaying() && isReplayScreen(screen)) {
				ignoreViewer(client, screen);
			}
		});
	}

	public static boolean isReplayScreen(Screen screen) {
		return screen instanceof HandledScreen<?> || screen instanceof ChatScreen;
	}

	private static void ignoreViewer(MinecraftClient client, Screen screen) {
		ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> false);
		ScreenMouseEvents.allowMouseRelease(screen).register((s, click) -> false);
		ScreenMouseEvents.allowMouseDrag(screen).register((s, click, dx, dy) -> false);
		ScreenMouseEvents.allowMouseScroll(screen).register((s, x, y, horizontal, vertical) -> false);
		ScreenKeyboardEvents.allowKeyPress(screen).register((s, key) -> {
			MomentonKeybinds.pressInScreen(client, key);
			return false;
		});

		if (screen instanceof ChatScreen chat) {
			TextFieldWidget field = ((ChatScreenAccessor) chat).getChatField();
			field.setUneditableColor(((TextFieldWidgetAccessor) field).getEditableColor());
			field.setEditable(false);
			ScreenEvents.remove(screen).register(s -> client.inGameHud.getChatHud().discardDraft());
		}
	}
}
