package com.relapzz.momenton.ui;

import com.relapzz.momenton.export.ExportSession;
import com.relapzz.momenton.edit.Clip;
import com.relapzz.momenton.export.ExportSettings;
import com.relapzz.momenton.export.FfmpegProcess;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

public class ExportScreen extends Screen {
	private static final String[] LABELS = {
			"screen.momenton.export.resolution",
			"screen.momenton.export.fps",
			"screen.momenton.export.range",
			"screen.momenton.export.output",
			"screen.momenton.export.ffmpeg"
	};

	private final Screen parent;
	private final Path replay;
	private final int durationTicks;
	private final List<Clip> clips;
	private final String ffmpegDefault;

	private TextFieldWidget widthField;
	private TextFieldWidget heightField;
	private TextFieldWidget fpsField;
	private TextFieldWidget startField;
	private TextFieldWidget endField;
	private TextFieldWidget outputField;
	private TextFieldWidget ffmpegField;
	private CyclingButtonWidget<Boolean> cursor;
	private Text message;

	public ExportScreen(Screen parent, Path replay, int durationTicks) {
		this(parent, replay, durationTicks, List.of(new Clip(0, durationTicks)));
	}

	public ExportScreen(Screen parent, Path replay, int durationTicks, List<Clip> clips) {
		super(Text.translatable("screen.momenton.export.title"));
		this.parent = parent;
		this.replay = replay;
		this.durationTicks = durationTicks;
		this.clips = clips;
		this.ffmpegDefault = FfmpegProcess.locate().map(Path::toString).orElse("");
		this.message = ffmpegDefault.isEmpty() ? Text.translatable("screen.momenton.export.no_ffmpeg") : Text.empty();
	}

	@Override
	protected void init() {
		int left = width / 2 - 45;
		widthField = field(widthField, left, row(0), 95, LABELS[0], "1920");
		heightField = field(heightField, left + 105, row(0), 95, LABELS[0], "1080");
		fpsField = field(fpsField, left, row(1), 200, LABELS[1], "60");
		startField = field(startField, left, row(2), 95, LABELS[2], "0");
		endField = field(endField, left + 105, row(2), 95, LABELS[2], seconds(durationTicks));
		outputField = field(outputField, left, row(3), 200, LABELS[3], defaultOutput());
		ffmpegField = field(ffmpegField, left, row(4), 200, LABELS[4], ffmpegDefault);
		cursor = addDrawableChild(CyclingButtonWidget.onOffBuilder(cursor == null || cursor.getValue())
				.build(width / 2 - 155, row(5), 310, 20, Text.translatable("screen.momenton.export.cursor")));

		addDrawableChild(ButtonWidget.builder(Text.translatable("screen.momenton.export"), button -> export())
				.dimensions(width / 2 - 155, height - 28, 150, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), button -> close())
				.dimensions(width / 2 + 5, height - 28, 150, 20)
				.build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFFFFFFFF);
		for (int i = 0; i < LABELS.length; i++) {
			context.drawTextWithShadow(textRenderer, Text.translatable(LABELS[i]), width / 2 - 155, row(i) + 6, 0xFFA0A0A0);
		}
		context.drawCenteredTextWithShadow(textRenderer, message, width / 2, row(6) + 6, 0xFFFF5555);
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	private TextFieldWidget field(TextFieldWidget previous, int x, int y, int fieldWidth, String label, String initial) {
		TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, fieldWidth, 20, Text.translatable(label));
		field.setMaxLength(1024);
		field.setText(previous == null ? initial : previous.getText());
		return addDrawableChild(field);
	}

	private static int row(int index) {
		return 36 + index * 24;
	}

	private static String seconds(int ticks) {
		return ticks % 20 == 0 ? String.valueOf(ticks / 20) : String.valueOf(ticks / 20.0);
	}

	private String defaultOutput() {
		String name = replay.getFileName().toString().replaceFirst("\\.momenton$", "");
		return FabricLoader.getInstance().getGameDir().resolve("momenton").resolve("exports").resolve(name + ".mp4").toString();
	}

	private void export() {
		Integer w = integer(widthField);
		Integer h = integer(heightField);
		if (w == null || h == null || w <= 0 || h <= 0 || w % 2 != 0 || h % 2 != 0) {
			fail("screen.momenton.export.error.size");
			return;
		}
		Integer fps = integer(fpsField);
		if (fps == null || fps < 24 || fps > 240) {
			fail("screen.momenton.export.error.fps");
			return;
		}
		Double start = decimal(startField);
		Double end = decimal(endField);
		if (start == null || end == null) {
			fail("screen.momenton.export.error.range");
			return;
		}
		int startTick = Math.clamp(Math.round(start * 20), 0, durationTicks);
		int endTick = Math.clamp(Math.round(end * 20), 0, durationTicks);
		if (startTick >= endTick) {
			fail("screen.momenton.export.error.order");
			return;
		}
		Path output = path(outputField);
		if (output == null) {
			fail("screen.momenton.export.error.output");
			return;
		}
		Path ffmpeg = path(ffmpegField);
		if (ffmpeg == null || !Files.isRegularFile(ffmpeg)) {
			fail("screen.momenton.export.error.ffmpeg");
			return;
		}
		try {
			Files.createDirectories(output.getParent());
		} catch (IOException e) {
			fail("screen.momenton.export.error.folder");
			return;
		}
		client.setScreen(null);
		ExportSession.start(client, new ExportSettings(replay, clips.size() > 1 ? clips : List.of(new Clip(startTick, endTick)), w, h, fps, output,
				ffmpeg.toString(), FfmpegProcess.DEFAULT_ARGS, 0, cursor.getValue()));
	}

	private void fail(String key) {
		message = Text.translatable(key);
	}

	private static Integer integer(TextFieldWidget field) {
		try {
			return Integer.parseInt(field.getText().trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Double decimal(TextFieldWidget field) {
		try {
			return Double.parseDouble(field.getText().trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Path path(TextFieldWidget field) {
		String text = field.getText().trim();
		if (text.isEmpty()) {
			return null;
		}
		try {
			return Path.of(text).toAbsolutePath();
		} catch (InvalidPathException e) {
			return null;
		}
	}
}
