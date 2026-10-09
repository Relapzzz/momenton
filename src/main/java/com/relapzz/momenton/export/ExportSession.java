package com.relapzz.momenton.export;

import net.minecraft.client.MinecraftClient;

public final class ExportSession {
	private ExportSession() {
	}

	public static boolean isExporting() {
		return false;
	}

	public static boolean drawsCursor() {
		return false;
	}

	public static void start(MinecraftClient client, ExportSettings settings) {
		throw new UnsupportedOperationException();
	}
}
