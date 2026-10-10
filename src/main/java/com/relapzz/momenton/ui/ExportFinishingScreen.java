package com.relapzz.momenton.ui;

import com.relapzz.momenton.export.ExportFinisher;
import com.relapzz.momenton.export.ExportSession;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class ExportFinishingScreen extends Screen {
	private final Screen parent;
	private final ExportFinisher finisher;

	public ExportFinishingScreen(Screen parent, ExportFinisher finisher) {
		super(Text.translatable("screen.momenton.export.finishing"));
		this.parent = parent;
		this.finisher = finisher;
	}

	@Override
	protected void init() {
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), button -> close())
				.dimensions(width / 2 - 75, height - 28, 150, 20)
				.build());
	}

	@Override
	public void tick() {
		if (!finisher.done()) {
			return;
		}
		if (finisher.cancelled()) {
			client.setScreen(parent);
			ExportSession.report(client, false, "Export cancelled");
		} else if (finisher.error() != null) {
			ExportFailedScreen.show(client, finisher.settings(), finisher.error());
		} else {
			client.setScreen(parent);
			ExportSession.report(client, true, "Saved " + finisher.settings().output());
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		int center = width / 2;
		int barWidth = Math.min(width - 20, 400);
		int y = height / 2 - 20;
		context.drawCenteredTextWithShadow(textRenderer, title, center, y - 30, 0xFFFFFFFF);
		Text phase = Text.translatable(finisher.cancelled() ? "screen.momenton.export.cancelling"
				: finisher.muxing() ? "screen.momenton.export.muxing" : "screen.momenton.export.encoding");
		context.drawCenteredTextWithShadow(textRenderer, phase, center, y - 14, 0xFFA0A0A0);
		double fraction = finisher.progress();
		ExportProgressOverlay.drawBar(context, textRenderer, center - barWidth / 2, y, barWidth, fraction,
				Text.literal((int) (Math.clamp(fraction, 0, 1) * 100) + "%"));
		context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.momenton.export.elapsed", ExportProgressOverlay.time(finisher.elapsedNanos())),
				center, y + 22, 0xFFA0A0A0);
	}

	@Override
	public void close() {
		if (!finisher.cancelled()) {
			finisher.cancel();
		}
	}
}
