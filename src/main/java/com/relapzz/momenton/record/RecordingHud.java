package com.relapzz.momenton.record;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/** A red dot and the elapsed time, so it is never a question whether a session is being recorded. */
public final class RecordingHud implements HudElement {
	private static final int DOT_COLOUR = 0xFFE5484D;
	private static final int TEXT_COLOUR = 0xFFFFFFFF;

	@Override
	public void render(DrawContext context, RenderTickCounter counter) {
		if (!Recorder.isRecording()) {
			return;
		}

		int seconds = Recorder.elapsedTicks() / 20;
		context.fill(8, 9, 14, 15, DOT_COLOUR);
		context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
				String.format("%d:%02d", seconds / 60, seconds % 60), 18, 8, TEXT_COLOUR);
	}
}
