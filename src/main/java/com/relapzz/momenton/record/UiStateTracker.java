package com.relapzz.momenton.record;

import com.relapzz.momenton.format.ActionCodec;
import com.relapzz.momenton.format.UiStateCodec;
import com.relapzz.momenton.mixin.client.ChatHudAccessor;
import com.relapzz.momenton.mixin.client.ChatScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The client-side UI state that never crosses the wire, and so cannot be reconstructed from packets.
 *
 * <p>Pressing E produces no packet at all; the selected hotbar slot only travels client to server;
 * hand swing and attack cooldown are driven by local input. Without this track a replay renders the
 * right world under the wrong HUD.
 */
public final class UiStateTracker {
	private static final int FLAG_SELECTED_SLOT = UiStateCodec.FLAG_SELECTED_SLOT;
	private static final int FLAG_SCREEN = UiStateCodec.FLAG_SCREEN;
	private static final int FLAG_CHAT_SCROLL = UiStateCodec.FLAG_CHAT_SCROLL;
	private static final int FLAG_SWING = UiStateCodec.FLAG_SWING;
	private static final int FLAG_COOLDOWN = UiStateCodec.FLAG_COOLDOWN;
	private static final int FLAG_CHAT_DRAFT = UiStateCodec.FLAG_CHAT_DRAFT;
	private static final int FLAG_RESETS = UiStateCodec.FLAG_RESETS;

	static final int SCREEN_NONE = UiStateCodec.SCREEN_NONE;
	static final int SCREEN_OWN_INVENTORY = UiStateCodec.SCREEN_OWN_INVENTORY;
	static final int SCREEN_CHAT = UiStateCodec.SCREEN_CHAT;
	static final int SCREEN_OTHER = UiStateCodec.SCREEN_OTHER;
	static final int SCREEN_CONTAINER = UiStateCodec.SCREEN_CONTAINER;

	private static int resets;

	private int selectedSlot = -1;
	private int screen = -1;
	private int chatScroll = -1;
	private int swing = -1;
	private int swingTicks = -1;
	private float cooldown = Float.NaN;
	private String chatDraft;
	private boolean resetsWritten;

	UiStateTracker() {
		resets = 0;
	}

	public static void countersReset(PlayerEntity player, int kind) {
		if (player == MinecraftClient.getInstance().player) {
			resets |= kind;
		}
	}

	/**
	 * Forgets the last state, so the next capture writes every field.
	 *
	 * <p>Called at a chunk boundary. This track is change-gated, so a seek that lands in a later chunk
	 * would otherwise miss a hotbar slot or an open screen last written in an earlier one.
	 */
	void reset() {
		selectedSlot = -1;
		screen = -1;
		chatScroll = -1;
		swing = -1;
		swingTicks = -1;
		cooldown = Float.NaN;
		chatDraft = null;
		resetsWritten = false;
	}

	/** The payload for this tick, or null if nothing changed. */
	@Nullable
	byte[] capture(MinecraftClient client, ClientPlayerEntity player) throws IOException {
		int currentSlot = player.getInventory().getSelectedSlot();
		int currentScreen = screenKind(client);
		int currentChatScroll = ((ChatHudAccessor) client.inGameHud.getChatHud()).getScrolledLines();
		int currentSwing = (player.handSwinging ? 1 : 0) | (player.preferredHand == Hand.OFF_HAND ? 2 : 0);
		int currentSwingTicks = player.handSwingTicks;
		float currentCooldown = player.getAttackCooldownProgress(0.0f);
		String currentChatDraft = client.currentScreen instanceof ChatScreen chat
				? ((ChatScreenAccessor) chat).getChatField().getText() : "";
		int currentResets = resets;
		resets = 0;

		int flags = 0;
		if (currentSlot != selectedSlot) {
			flags |= FLAG_SELECTED_SLOT;
		}
		if (currentScreen != screen) {
			flags |= FLAG_SCREEN;
		}
		if (currentChatScroll != chatScroll) {
			flags |= FLAG_CHAT_SCROLL;
		}
		if (currentSwing != swing || currentSwingTicks != swingTicks) {
			flags |= FLAG_SWING;
		}
		if (currentCooldown != cooldown) {
			flags |= FLAG_COOLDOWN;
		}
		if (!currentChatDraft.equals(chatDraft)) {
			flags |= FLAG_CHAT_DRAFT;
		}
		if (currentResets != 0 || !resetsWritten) {
			flags |= FLAG_RESETS;
		}
		if (flags == 0) {
			return null;
		}

		ByteArrayOutputStream payload = new ByteArrayOutputStream(16);
		DataOutputStream out = new DataOutputStream(payload);
		out.writeByte(flags);

		if ((flags & FLAG_SELECTED_SLOT) != 0) {
			selectedSlot = currentSlot;
			out.writeByte(selectedSlot);
		}
		if ((flags & FLAG_SCREEN) != 0) {
			screen = currentScreen;
			out.writeByte(screen);
		}
		if ((flags & FLAG_CHAT_SCROLL) != 0) {
			chatScroll = currentChatScroll;
			ActionCodec.writeVarInt(out, chatScroll);
		}
		if ((flags & FLAG_SWING) != 0) {
			swing = currentSwing;
			swingTicks = currentSwingTicks;
			out.writeByte(swing);
			ActionCodec.writeVarInt(out, swingTicks);
		}
		if ((flags & FLAG_COOLDOWN) != 0) {
			cooldown = currentCooldown;
			out.writeFloat(cooldown);
		}
		if ((flags & FLAG_CHAT_DRAFT) != 0) {
			chatDraft = currentChatDraft;
			out.writeUTF(chatDraft);
		}
		if ((flags & FLAG_RESETS) != 0) {
			resetsWritten = true;
			out.writeByte(currentResets);
		}

		return payload.toByteArray();
	}

	private static int screenKind(MinecraftClient client) {
		if (client.currentScreen == null) {
			return SCREEN_NONE;
		}
		if (client.currentScreen instanceof InventoryScreen) {
			return SCREEN_OWN_INVENTORY;
		}
		if (client.currentScreen instanceof ChatScreen) {
			return SCREEN_CHAT;
		}
		if (client.currentScreen instanceof HandledScreen<?> && !(client.currentScreen instanceof CreativeInventoryScreen)) {
			return SCREEN_CONTAINER;
		}
		return SCREEN_OTHER;
	}
}
