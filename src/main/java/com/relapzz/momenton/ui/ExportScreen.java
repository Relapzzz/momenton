package com.relapzz.momenton.ui;

import com.mojang.blaze3d.textures.GpuTexture;
import com.relapzz.momenton.edit.Clip;
import com.relapzz.momenton.export.EncoderProbe;
import com.relapzz.momenton.export.EncodingPreset;
import com.relapzz.momenton.export.ExportFinisher;
import com.relapzz.momenton.export.ExportSession;
import com.relapzz.momenton.export.ExportSettings;
import com.relapzz.momenton.export.FfmpegProcess;
import com.relapzz.momenton.export.VulkanMod;
import com.relapzz.momenton.format.ReplayReader;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExportScreen extends Screen {
	private static final Pattern BITRATE = Pattern.compile("(\\d+)\\s*([km]?)");

	private static EncodingPreset lastPreset;
	private static String lastBitrate = "10M";
	private static String lastCommand;
	private static String lastArgs;
	private static float lastGameVolume = 1.0f;
	private static float lastMicVolume = 1.0f;
	static ExportScreen last;

	private final Screen parent;
	private final Path replay;
	private final int durationTicks;
	private final List<Clip> clips;
	private final String commandDefault;
	private final boolean hasMic;
	private final List<Text> labels = new ArrayList<>();
	private final List<EncodingPreset> presets = EncodingPreset.available(EncoderProbe.working());
	private EncodingPreset preset = lastPreset != null && presets.contains(lastPreset) ? lastPreset : presets.getFirst();

	private TextFieldWidget widthField;
	private TextFieldWidget heightField;
	private TextFieldWidget fpsField;
	private TextFieldWidget startField;
	private TextFieldWidget endField;
	private TextFieldWidget bitrateField;
	private TextFieldWidget outputField;
	private TextFieldWidget commandField;
	private TextFieldWidget argsField;
	private CyclingButtonWidget<Boolean> cursor;
	private VolumeSlider gameVolume;
	private VolumeSlider micVolume;
	private CyclingButtonWidget<Boolean> mix;
	private Text message = Text.empty();

	public ExportScreen(Screen parent, Path replay, int durationTicks) {
		this(parent, replay, durationTicks, List.of(new Clip(0, durationTicks)));
	}

	public ExportScreen(Screen parent, Path replay, int durationTicks, List<Clip> clips) {
		super(Text.translatable("screen.momenton.export.title"));
		this.parent = parent;
		this.replay = replay;
		this.durationTicks = durationTicks;
		this.clips = clips;
		this.commandDefault = lastCommand != null ? lastCommand : FfmpegProcess.defaultCommand();
		this.hasMic = hasMic(replay);
	}

	private static boolean hasMic(Path replay) {
		try (ReplayReader reader = new ReplayReader(replay)) {
			return reader.metadata().micStartTick() != null;
		} catch (IOException e) {
			return false;
		}
	}

	@Override
	protected void init() {
		labels.clear();
		int left = width / 2 - 45;
		widthField = field(widthField, left, 95, "screen.momenton.export.resolution", "1920");
		heightField = field(heightField, left + 105, 95, "screen.momenton.export.resolution", "1080");
		if (VulkanMod.LOADED) {
			GpuTexture target = client.getFramebuffer().getColorAttachment();
			widthField.setText(String.valueOf(target.getWidth(0) & ~1));
			heightField.setText(String.valueOf(target.getHeight(0) & ~1));
			widthField.setEditable(false);
			heightField.setEditable(false);
			labels.add(Text.translatableWithFallback("screen.momenton.export.resolution.window", "Window size (VulkanMod)"));
		} else {
			label("screen.momenton.export.resolution");
		}
		fpsField = field(fpsField, left, 200, "screen.momenton.export.fps", "60");
		label("screen.momenton.export.fps");
		if (clips.size() == 1) {
			startField = field(startField, left, 95, "screen.momenton.export.range", seconds(clips.getFirst().startTick()));
			endField = field(endField, left + 105, 95, "screen.momenton.export.range", seconds(clips.getFirst().endTick()));
			label("screen.momenton.export.range");
		}
		addDrawableChild(CyclingButtonWidget.builder(ExportScreen::presetText, preset)
				.values(presets)
				.omitKeyText()
				.build(left, row(labels.size()), 200, 20, Text.translatable("screen.momenton.export.format"), (button, value) -> changePreset(value)));
		label("screen.momenton.export.format");
		if (preset.hasBitrate()) {
			bitrateField = field(bitrateField, left, 200, "screen.momenton.export.bitrate", lastBitrate);
			label("screen.momenton.export.bitrate");
		}
		outputField = field(outputField, left, 200, "screen.momenton.export.output", defaultOutput());
		label("screen.momenton.export.output");
		commandField = field(commandField, left, 200, "screen.momenton.export.ffmpeg", commandDefault);
		label("screen.momenton.export.ffmpeg");
		argsField = field(argsField, left, 200, "screen.momenton.export.arguments", lastArgs != null ? lastArgs : preset.arguments());
		label("screen.momenton.export.arguments");
		gameVolume = addDrawableChild(new VolumeSlider(left, row(labels.size()), hasMic ? 95 : 200, "screen.momenton.export.volume.game",
				gameVolume == null ? lastGameVolume : gameVolume.volume()));
		if (hasMic) {
			micVolume = addDrawableChild(new VolumeSlider(left + 105, row(labels.size()), 95, "screen.momenton.export.volume.voice",
					micVolume == null ? lastMicVolume : micVolume.volume()));
		}
		label("screen.momenton.export.volume");
		cursor = addDrawableChild(CyclingButtonWidget.onOffBuilder(cursor == null || cursor.getValue())
				.build(width / 2 - 155, row(labels.size()), hasMic ? 152 : 310, 20, Text.translatable("screen.momenton.export.cursor")));
		if (hasMic) {
			mix = addDrawableChild(CyclingButtonWidget.onOffBuilder(Text.translatable("screen.momenton.export.voice.mixed"),
							Text.translatable("screen.momenton.export.voice.separate"), mix == null ? ExportFinisher.mixCommentary : mix.getValue())
					.build(width / 2 + 3, row(labels.size()), 152, 20, Text.translatable("screen.momenton.export.voice")));
		}

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
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFFFF);
		for (int i = 0; i < labels.size(); i++) {
			context.drawTextWithShadow(textRenderer, labels.get(i), width / 2 - 155, row(i) + 6, 0xFFA0A0A0);
		}
		context.drawCenteredTextWithShadow(textRenderer, message, width / 2, height - 42, 0xFFFF5555);
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	private void changePreset(EncodingPreset value) {
		preset = value;
		argsField.setText(value.arguments());
		outputField.setText(withExtension(outputField.getText().trim(), value.extension()));
		clearAndInit();
	}

	private TextFieldWidget field(TextFieldWidget previous, int x, int fieldWidth, String label, String initial) {
		TextFieldWidget field = new TextFieldWidget(textRenderer, x, row(labels.size()), fieldWidth, 20, Text.translatable(label));
		field.setMaxLength(1024);
		field.setText(previous == null ? initial : previous.getText());
		return addDrawableChild(field);
	}

	private void label(String key) {
		labels.add(Text.translatable(key));
	}

	private static Text presetText(EncodingPreset preset) {
		return Text.translatable("screen.momenton.export.preset." + preset.name().toLowerCase(Locale.ROOT));
	}

	private static int row(int index) {
		return 26 + index * 22;
	}

	private static String seconds(int ticks) {
		return ticks % 20 == 0 ? String.valueOf(ticks / 20) : String.valueOf(ticks / 20.0);
	}

	private static String withExtension(String path, String extension) {
		int dot = path.lastIndexOf('.');
		int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		return (dot > separator ? path.substring(0, dot) : path) + "." + extension;
	}

	private String defaultOutput() {
		String name = replay.getFileName().toString().replaceFirst("\\.momenton$", "");
		return FabricLoader.getInstance().getGameDir().resolve("momenton").resolve("exports").resolve(name + "." + preset.extension()).toString();
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
		List<Clip> exported = clips;
		if (clips.size() == 1) {
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
			exported = List.of(new Clip(startTick, endTick));
		}
		Integer bitrate = preset.hasBitrate() ? bitrate(bitrateField.getText()) : Integer.valueOf(0);
		if (bitrate == null) {
			fail("screen.momenton.export.error.bitrate");
			return;
		}
		Path output = path(outputField);
		if (output == null) {
			fail("screen.momenton.export.error.output");
			return;
		}
		String command = command(commandField.getText().trim());
		if (command == null) {
			fail("screen.momenton.export.error.ffmpeg");
			return;
		}
		String args = argsField.getText().trim();
		if (!args.contains("%FILENAME%") || !splits(args)) {
			fail("screen.momenton.export.error.args");
			return;
		}
		try {
			Files.createDirectories(output.getParent());
		} catch (IOException e) {
			fail("screen.momenton.export.error.folder");
			return;
		}
		lastPreset = preset;
		if (preset.hasBitrate()) {
			lastBitrate = bitrateField.getText().trim();
		}
		lastCommand = commandField.getText().trim();
		lastArgs = args;
		lastGameVolume = gameVolume.volume();
		if (hasMic) {
			lastMicVolume = micVolume.volume();
			ExportFinisher.mixCommentary = mix.getValue();
		}
		last = this;
		message = Text.empty();
		client.setScreen(null);
		ExportSession.start(client, new ExportSettings(replay, exported, w, h, fps, output, command, args, bitrate, cursor.getValue(),
				lastGameVolume, hasMic ? lastMicVolume : 0.0f));
	}

	private void fail(String key) {
		message = Text.translatable(key);
	}

	private static Integer bitrate(String text) {
		Matcher matcher = BITRATE.matcher(text.trim().toLowerCase(Locale.ROOT));
		if (!matcher.matches()) {
			return null;
		}
		long bits = Long.parseLong(matcher.group(1)) * (matcher.group(2).equals("m") ? 1_000_000 : 1_000);
		return bits > 0 && bits <= Integer.MAX_VALUE ? (int) bits : null;
	}

	private static String command(String text) {
		if (text.isEmpty()) {
			return null;
		}
		try {
			Path path = Path.of(text);
			if (path.getParent() == null) {
				return text;
			}
			return Files.isRegularFile(path) ? path.toAbsolutePath().toString() : null;
		} catch (InvalidPathException e) {
			return null;
		}
	}

	private static boolean splits(String args) {
		try {
			FfmpegProcess.split(args);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
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

	private static final class VolumeSlider extends SliderWidget {
		private final String key;

		private VolumeSlider(int x, int y, int width, String key, float volume) {
			super(x, y, width, 20, Text.empty(), volume / 2.0);
			this.key = key;
			updateMessage();
		}

		private float volume() {
			return Math.round(value * 40) / 20.0f;
		}

		@Override
		protected void updateMessage() {
			int percent = Math.round(volume() * 100);
			setMessage(Text.translatable(key, percent == 0 ? Text.translatable("screen.momenton.export.volume.muted") : Text.literal(percent + "%")));
		}

		@Override
		protected void applyValue() {
		}
	}
}
