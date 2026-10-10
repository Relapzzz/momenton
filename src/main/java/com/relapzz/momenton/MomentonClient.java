package com.relapzz.momenton;

import com.relapzz.momenton.config.MomentonKeybinds;
import com.relapzz.momenton.playback.ReplayScreenInput;
import com.relapzz.momenton.playback.ReplaySession;
import com.relapzz.momenton.record.Recorder;
import com.relapzz.momenton.record.RecordingHud;
import com.relapzz.momenton.record.ScreenTracker;
import com.relapzz.momenton.ui.TitleScreenEntry;
import com.relapzz.momenton.ui.editor.EditSession;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

/**
 * Client entrypoint. Recording, playback, the editor and export all hang off this.
 */
public class MomentonClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		MomentonKeybinds.register();
		TitleScreenEntry.register();
		ReplayScreenInput.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			MomentonKeybinds.poll(client);
			ScreenTracker.onClientTick(client);
			Recorder.onClientTick(client);
			ReplaySession.onClientTick(client);
			EditSession.onClientTick(client);
		});

		HudElementRegistry.addLast(Momenton.id("recording_indicator"), new RecordingHud());
	}
}
