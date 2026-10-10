package com.relapzz.momenton.record;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.Packet;

import java.util.List;

final class ServerStateSnapshot {
	private ServerStateSnapshot() {
	}

	static void capture(MinecraftClient client, List<Packet<?>> packets) {
	}
}
