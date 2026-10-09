package com.relapzz.momenton.config;

import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.playback.ReplaySession;
import com.relapzz.momenton.record.Recorder;
import com.relapzz.momenton.ui.ReplayBrowserScreen;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class MomentonKeybinds {
	private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Momenton.id("main"));

	/** Ten seconds: the granularity a scrubber would give, before there is a scrubber. */
	private static final int SEEK_TICKS = 200;

	private static KeyBinding toggleRecording;
	private static KeyBinding openReplays;
	private static KeyBinding togglePause;
	private static KeyBinding seekBack;
	private static KeyBinding seekForward;

	private MomentonKeybinds() {
	}

	public static void register() {
		toggleRecording = register("toggle_recording", GLFW.GLFW_KEY_F9);
		openReplays = register("open_replays", GLFW.GLFW_KEY_F10);
		togglePause = register("toggle_pause", GLFW.GLFW_KEY_P);
		seekBack = register("seek_back", GLFW.GLFW_KEY_LEFT_BRACKET);
		seekForward = register("seek_forward", GLFW.GLFW_KEY_RIGHT_BRACKET);
	}

	private static KeyBinding register(String name, int key) {
		return KeyBindingHelper.registerKeyBinding(
				new KeyBinding("key.momenton." + name, InputUtil.Type.KEYSYM, key, CATEGORY));
	}

	/** Client thread, start of tick. */
	public static void poll(MinecraftClient client) {
		while (toggleRecording.wasPressed()) {
			Recorder.toggle(client);
		}

		while (openReplays.wasPressed()) {
			if (ReplaySession.isPlaying()) {
				ReplaySession.stop();
			} else {
				client.setScreen(new ReplayBrowserScreen(client.currentScreen));
			}
		}

		while (togglePause.wasPressed()) {
			ReplaySession.togglePause();
		}

		while (seekBack.wasPressed()) {
			ReplaySession.seek(ReplaySession.currentTick() - SEEK_TICKS);
		}

		while (seekForward.wasPressed()) {
			ReplaySession.seek(ReplaySession.currentTick() + SEEK_TICKS);
		}
	}
}
