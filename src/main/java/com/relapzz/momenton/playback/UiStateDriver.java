package com.relapzz.momenton.playback;

import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.format.UiStateCodec;
import com.relapzz.momenton.mixin.client.ChatHudAccessor;
import com.relapzz.momenton.mixin.client.ChatScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Hand;

import java.io.IOException;

/**
 * Applies the {@code UI_STATE} track: the client-side state a packet recorder cannot see.
 *
 * <p>Two of these are driven as *events* rather than values, and that is the point rather than a
 * shortcut. Hand swing and attack cooldown are both animations vanilla runs itself, from a start it is
 * told about: {@code tickHandSwing} advances {@code handSwingTicks} every tick and
 * {@code getAttackCooldownProgress} ramps off {@code ticksSinceLastAttack}. Writing the recorded
 * numbers in at the head of the tick would fight both - vanilla would advance them again on the same
 * tick, and the swing would run at double speed. Feeding it the start instead, with
 * {@code swingHand} and {@code resetTicksSinceLastAttack}, makes vanilla produce the same animation it
 * produced live, from the same inputs.
 *
 * <p>So the starts are what matter, and a swing has two kinds. {@code LivingEntity.swingHand} restarts
 * a swing that is already running - {@code handSwingTicks = -1} while {@code handSwinging} stays set -
 * whenever the running one is at least half done. A rising edge of {@code handSwinging} therefore sees
 * only the first hit of a burst; the rest show up as the recorded tick counter dropping. Both are
 * starts and both are replayed, and {@code swingHand}'s own half-done guard then accepts or rejects
 * each one exactly as it did live, because the replayed counter is vanilla's own and runs in step.
 *
 * <p>The cooldown resets whenever the recorded progress *drops*, which only happens when the player
 * attacked. All of it survives a seek, because the recorder rewrites the whole state at each chunk
 * boundary.
 *
 * <p>The open screen and the chat draft are applied when they change, and only over a screen the replay
 * put up: a screen the viewer opened is never closed or replaced, and the change waits until it is gone.
 */
public final class UiStateDriver {
	private static UiStateCodec.State pending;
	private static boolean swinging;
	private static int swingTicks;
	private static float cooldown;
	private static int screen;
	private static String chatDraft = "";
	private static boolean screenChanged;

	private UiStateDriver() {
	}

	static void reset() {
		pending = null;
		swinging = false;
		swingTicks = 0;
		cooldown = 0.0f;
		screen = UiStateCodec.SCREEN_NONE;
		chatDraft = "";
		screenChanged = false;
	}

	static void accept(byte[] payload) {
		try {
			pending = merge(pending, UiStateCodec.decode(payload));
		} catch (IOException e) {
			Momenton.LOGGER.error("Could not decode a UI state action", e);
		}
	}

	static void settle() {
		UiStateCodec.State state = pending;
		if (state == null) {
			return;
		}
		if (state.has(UiStateCodec.FLAG_SWING)) {
			swinging = state.swinging();
			swingTicks = state.swingTicks();
		}
		if (state.has(UiStateCodec.FLAG_COOLDOWN)) {
			cooldown = state.cooldown();
		}
		takeScreen(state);
		pending = new UiStateCodec.State(state.flags() & ~(UiStateCodec.FLAG_SWING | UiStateCodec.FLAG_COOLDOWN
				| UiStateCodec.FLAG_SCREEN | UiStateCodec.FLAG_CHAT_DRAFT),
				state.selectedSlot(), state.screen(), state.chatScroll(), state.swing(), state.swingTicks(), state.cooldown(),
				state.chatDraft());
		showScreen(MinecraftClient.getInstance());
	}

	private static UiStateCodec.State merge(UiStateCodec.State earlier, UiStateCodec.State later) {
		if (earlier == null) {
			return later;
		}
		boolean swing = later.has(UiStateCodec.FLAG_SWING);
		return new UiStateCodec.State(earlier.flags() | later.flags(),
				later.has(UiStateCodec.FLAG_SELECTED_SLOT) ? later.selectedSlot() : earlier.selectedSlot(),
				later.has(UiStateCodec.FLAG_SCREEN) ? later.screen() : earlier.screen(),
				later.has(UiStateCodec.FLAG_CHAT_SCROLL) ? later.chatScroll() : earlier.chatScroll(),
				swing ? later.swing() : earlier.swing(),
				swing ? later.swingTicks() : earlier.swingTicks(),
				later.has(UiStateCodec.FLAG_COOLDOWN) ? later.cooldown() : earlier.cooldown(),
				later.has(UiStateCodec.FLAG_CHAT_DRAFT) ? later.chatDraft() : earlier.chatDraft());
	}

	private static void takeScreen(UiStateCodec.State state) {
		if (state.has(UiStateCodec.FLAG_SCREEN)) {
			screen = state.screen();
			screenChanged = true;
		}
		if (state.has(UiStateCodec.FLAG_CHAT_DRAFT)) {
			chatDraft = state.chatDraft();
			screenChanged = true;
		}
	}

	private static void showScreen(MinecraftClient client) {
		Screen current = client.currentScreen;
		if (!screenChanged || current != null && !ReplayScreenInput.isReplayScreen(current)) {
			return;
		}
		screenChanged = false;

		switch (screen) {
			case UiStateCodec.SCREEN_NONE -> close(client, current);
			case UiStateCodec.SCREEN_OWN_INVENTORY -> {
				if (!(current instanceof InventoryScreen)) {
					close(client, current);
					client.setScreen(new InventoryScreen(client.player));
				}
			}
			case UiStateCodec.SCREEN_CHAT -> {
				if (current instanceof ChatScreen chat) {
					((ChatScreenAccessor) chat).getChatField().setText(chatDraft);
				} else {
					close(client, current);
					client.setScreen(new ChatScreen(chatDraft, false));
				}
			}
			default -> {
			}
		}
	}

	private static void close(MinecraftClient client, Screen current) {
		if (current != null) {
			client.player.closeHandledScreen();
		}
	}

	/** Client thread, head of the replayed player's tick. */
	public static void drive(MinecraftClient client, ClientPlayerEntity player) {
		UiStateCodec.State state = pending;
		if (state != null) {
			pending = null;
			apply(client, player, state);
		}
		showScreen(client);
	}

	private static void apply(MinecraftClient client, ClientPlayerEntity player, UiStateCodec.State state) {
		takeScreen(state);

		if (state.has(UiStateCodec.FLAG_SELECTED_SLOT)) {
			player.getInventory().setSelectedSlot(state.selectedSlot());
		}

		if (state.has(UiStateCodec.FLAG_CHAT_SCROLL)) {
			((ChatHudAccessor) client.inGameHud.getChatHud()).setScrolledLines(state.chatScroll());
		}

		if (state.has(UiStateCodec.FLAG_SWING)) {
			if (state.swinging() && (!swinging || state.swingTicks() < swingTicks)) {
				player.swingHand(state.offHand() ? Hand.OFF_HAND : Hand.MAIN_HAND);
			}
			swinging = state.swinging();
			swingTicks = state.swingTicks();
		}

		if (state.has(UiStateCodec.FLAG_COOLDOWN)) {
			// Progress only ever falls when the player attacked; vanilla ramps it back up itself, at a
			// rate that depends on the held item - which the LOCAL_PLAYER track has already restored.
			if (state.cooldown() < cooldown) {
				player.resetTicksSinceLastAttack();
			}
			cooldown = state.cooldown();
		}
	}
}
