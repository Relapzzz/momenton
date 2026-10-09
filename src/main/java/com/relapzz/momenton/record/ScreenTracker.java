package com.relapzz.momenton.record;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.screen.ScreenHandler;

import java.util.ArrayList;
import java.util.List;

public final class ScreenTracker {
	private static int syncId = -1;
	private static List<ItemStack> stacks = List.of();
	private static ItemStack cursor = ItemStack.EMPTY;

	private ScreenTracker() {
	}

	public static void onClientTick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (!Recorder.isRecording() || player == null) {
			syncId = -1;
			return;
		}

		ScreenHandler handler = player.currentScreenHandler;
		if (handler instanceof CreativeInventoryScreen.CreativeScreenHandler || unchanged(handler)) {
			return;
		}

		syncId = handler.syncId;
		stacks = new ArrayList<>(handler.slots.size());
		for (int slot = 0; slot < handler.slots.size(); slot++) {
			stacks.add(handler.getSlot(slot).getStack().copy());
		}
		cursor = handler.getCursorStack().copy();
		Recorder.synthesise(new InventoryS2CPacket(syncId, handler.getRevision(), stacks, cursor));
	}

	private static boolean unchanged(ScreenHandler handler) {
		if (handler.syncId != syncId || handler.slots.size() != stacks.size()
				|| !ItemStack.areEqual(cursor, handler.getCursorStack())) {
			return false;
		}
		for (int slot = 0; slot < stacks.size(); slot++) {
			if (!ItemStack.areEqual(stacks.get(slot), handler.getSlot(slot).getStack())) {
				return false;
			}
		}
		return true;
	}
}
