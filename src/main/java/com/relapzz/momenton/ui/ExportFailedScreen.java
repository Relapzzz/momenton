package com.relapzz.momenton.ui;

import com.relapzz.momenton.export.ExportSettings;
import com.relapzz.momenton.export.FfmpegProcess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ScrollableTextWidget;
import net.minecraft.text.Text;

import java.util.List;

public class ExportFailedScreen extends Screen {
	private static final int LOG_LINES = 200;

	private final Text cause;
	private final Text hint;
	private final Text log;

	private ExportFailedScreen(String reason, String log) {
		super(Text.translatable("screen.momenton.export.failed.title"));
		this.cause = Text.translatable("screen.momenton.export.failed." + reason);
		this.hint = Text.translatable("screen.momenton.export.failed." + reason + ".hint", FfmpegProcess.logPath().toString());
		List<String> lines = log.replace("\t", "    ").lines().toList();
		this.log = Text.literal(String.join("\n", lines.subList(Math.max(0, lines.size() - LOG_LINES), lines.size())));
	}

	public static void show(MinecraftClient client, ExportSettings settings, Exception error) {
		client.setScreen(switch (error) {
			case FfmpegProcess.NotFoundException e -> new ExportFailedScreen("not_found", "> " + settings.ffmpegCommand() + "\n" + e.getCause().getMessage());
			case FfmpegProcess.StartupException e -> new ExportFailedScreen("arguments", e.log());
			case FfmpegProcess.FfmpegException e -> new ExportFailedScreen("stopped", e.log());
			default -> new ExportFailedScreen("stopped", String.valueOf(error.getMessage()));
		});
	}

	@Override
	protected void init() {
		int top = 50 + textRenderer.wrapLines(hint, width - 20).size() * textRenderer.fontHeight;
		addDrawableChild(new ScrollableTextWidget(10, top, width - 20, height - 36 - top, log, textRenderer));
		addDrawableChild(ButtonWidget.builder(Text.translatable("screen.momenton.export.failed.back"), button -> client.setScreen(ExportScreen.last))
				.dimensions(width / 2 - 155, height - 28, 150, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("screen.momenton.export.failed.close"), button -> close())
				.dimensions(width / 2 + 5, height - 28, 150, 20)
				.build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(textRenderer, cause, width / 2, 26, 0xFFFF5555);
		context.drawWrappedTextWithShadow(textRenderer, hint, 10, 40, width - 20, 0xFFA0A0A0);
	}

	@Override
	public void close() {
		client.setScreen(null);
	}
}
