package com.relapzz.momenton.ui;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.export.ExportProgress;
import com.relapzz.momenton.mixin.client.export.ExportGameRendererAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.util.Window;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class ExportProgressOverlay implements AutoCloseable {
	private static final Identifier PREVIEW = Momenton.id("export_preview");
	private static final int BACKGROUND = 0xFF202020;
	private static final int GREY = 0xFFA0A0A0;
	private static final int WHITE = 0xFFFFFFFF;

	private final MinecraftClient client;
	private final int width;
	private final int height;
	private final int[] windowWidth = new int[1];
	private final int[] windowHeight = new int[1];
	private boolean preview = true;
	private boolean captured;
	private PreviewTexture texture;

	public ExportProgressOverlay(MinecraftClient client, int width, int height) {
		this.client = client;
		this.width = width;
		this.height = height;
	}

	public void togglePreview() {
		preview = !preview;
	}

	public void capture(GpuTexture frame) {
		if (!preview) {
			return;
		}
		if (texture == null) {
			texture = new PreviewTexture(width, height);
			client.getTextureManager().registerTexture(PREVIEW, texture);
		}
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(frame, texture.getGlTexture(), 0, 0, 0, 0, 0, width, height);
		captured = true;
	}

	public void draw(ExportProgress progress) {
		ExportGameRendererAccessor renderer = (ExportGameRendererAccessor) client.gameRenderer;
		GuiRenderState state = renderer.momenton$guiState();
		state.clear();
		DrawContext context = new DrawContext(client, state, 0, 0);
		layout(context, progress);
		renderer.momenton$guiRenderer().render(renderer.momenton$fogRenderer().getFogBuffer(FogRenderer.FogType.NONE));
	}

	@Override
	public void close() {
		if (texture != null) {
			client.getTextureManager().destroyTexture(PREVIEW);
		}
	}

	private void layout(DrawContext context, ExportProgress progress) {
		Window window = client.getWindow();
		GLFW.glfwGetFramebufferSize(window.getHandle(), windowWidth, windowHeight);
		double scale = window.getScaleFactor();
		int right = (int) (Math.min(windowWidth[0], window.getFramebufferWidth()) / scale);
		int top = (int) (Math.max(0, window.getFramebufferHeight() - windowHeight[0]) / scale);
		int bottom = window.getScaledHeight();
		int center = right / 2;
		TextRenderer text = client.textRenderer;

		context.fill(0, 0, window.getScaledWidth(), bottom, BACKGROUND);
		context.drawCenteredTextWithShadow(text, Text.translatable("screen.momenton.export.rendering"), center, top + 6, WHITE);

		int barY = bottom - 36;
		int barWidth = Math.min(right - 20, 400);
		double fraction = (double) progress.done() / progress.total();
		drawBar(context, text, center - barWidth / 2, barY, barWidth, fraction,
				Text.translatable("screen.momenton.export.frames", progress.done(), progress.total(), (int) (fraction * 100)));
		int statsY = barY - 12;
		context.drawTextWithShadow(text, Text.translatable("screen.momenton.export.elapsed", time(progress.elapsedNanos())), center - barWidth / 2, statsY, GREY);
		Text remaining = Text.translatable("screen.momenton.export.remaining", time(progress.remainingNanos()));
		context.drawTextWithShadow(text, remaining, center + barWidth / 2 - text.getWidth(remaining), statsY, GREY);
		context.drawCenteredTextWithShadow(text, status(progress), center, statsY, GREY);
		context.drawCenteredTextWithShadow(text, Text.translatable(preview ? "screen.momenton.export.hint.preview_on" : "screen.momenton.export.hint.preview_off"),
				center, bottom - 14, GREY);

		int areaTop = top + 20;
		int areaBottom = statsY - 6;
		if (areaBottom - areaTop < 20) {
			return;
		}
		if (preview && captured) {
			int previewHeight = Math.min(areaBottom - areaTop, (right - 20) * height / width);
			int previewWidth = previewHeight * width / height;
			int x = center - previewWidth / 2;
			int y = areaTop + (areaBottom - areaTop - previewHeight) / 2;
			context.drawTexturedQuad(PREVIEW, x, y, x + previewWidth, y + previewHeight, 0, 1, 1, 0);
		} else {
			context.drawCenteredTextWithShadow(text, Text.translatable("screen.momenton.export.preview_off"), center, (areaTop + areaBottom) / 2, GREY);
		}
	}

	private static Text status(ExportProgress progress) {
		if (progress.done() == 0) {
			return Text.translatable("screen.momenton.export.preparing");
		}
		if (progress.done() == progress.total()) {
			return Text.translatable("screen.momenton.export.draining");
		}
		return Text.translatable("screen.momenton.export.fps", String.format(Locale.ROOT, "%.1f", progress.fps()));
	}

	static void drawBar(DrawContext context, TextRenderer text, int x, int y, int width, double fraction, Text label) {
		context.fill(x - 1, y - 1, x + width + 1, y + 15, 0xFF000000);
		context.fill(x, y, x + width, y + 14, 0xFF404040);
		context.fill(x, y, x + (int) (width * Math.clamp(fraction, 0, 1)), y + 14, 0xFF3C8527);
		context.drawCenteredTextWithShadow(text, label, x + width / 2, y + 3, WHITE);
	}

	static String time(long nanos) {
		if (nanos < 0) {
			return "-";
		}
		long seconds = TimeUnit.NANOSECONDS.toSeconds(nanos);
		return seconds >= 3600
				? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
				: String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}

	private static final class PreviewTexture extends AbstractTexture {
		PreviewTexture(int width, int height) {
			GpuDevice device = RenderSystem.getDevice();
			glTexture = device.createTexture(() -> "Momenton export preview", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
					TextureFormat.RGBA8, width, height, 1, 1);
			glTextureView = device.createTextureView(glTexture);
			sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
		}
	}
}
