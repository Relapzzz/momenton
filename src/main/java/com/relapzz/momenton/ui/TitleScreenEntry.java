package com.relapzz.momenton.ui;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Puts the replay browser on the title screen.
 *
 * <p>Playback never needed a world to already exist - it builds its own from a snapshot - but F10
 * only fires when no screen has focus, and the title screen always has focus. This is the way in
 * without joining a world first.
 *
 * <p>An event rather than a mixin: adding to {@code Screens.getButtons} registers the widget on a
 * screen this mod does not own, and survives a vanilla title screen layout change.
 */
public final class TitleScreenEntry {
	private TitleScreenEntry() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof TitleScreen) {
				Screens.getButtons(screen).add(ButtonWidget.builder(
								Text.translatable("screen.momenton.open"),
								button -> client.setScreen(new ReplayBrowserScreen(screen)))
						.dimensions(4, height - 34, 90, 20)
						.build());
			}
		});
	}
}
