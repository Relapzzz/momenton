package com.relapzz.momenton.ui;

import com.relapzz.momenton.audio.MicMeter;
import com.relapzz.momenton.audio.MicRecorder;
import com.relapzz.momenton.config.MomentonConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class SettingsScreen extends Screen {
	private final Screen parent;
	private MicMeter meter;

	public SettingsScreen(Screen parent) {
		super(Text.translatable("screen.momenton.settings.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		MomentonConfig config = MomentonConfig.get();
		addDrawableChild(CyclingButtonWidget.onOffBuilder(config.micEnabled)
				.build(width / 2 - 155, 50, 310, 20, Text.translatable("screen.momenton.settings.mic"), (button, value) -> {
					config.micEnabled = value;
					config.save();
				}));

		List<String> available = MicRecorder.devices();
		List<String> devices = new ArrayList<>();
		devices.add("");
		devices.addAll(available);
		if (!devices.contains(config.micDevice)) {
			devices.add(config.micDevice);
		}
		addDrawableChild(CyclingButtonWidget.<String>builder(device -> deviceText(device, available), config.micDevice)
				.values(devices)
				.build(width / 2 - 155, 74, 310, 20, Text.translatable("screen.momenton.settings.device"), (button, value) -> {
					config.micDevice = value;
					config.save();
					meter.close();
					meter = new MicMeter();
				}));

		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
				.dimensions(width / 2 - 100, height - 28, 200, 20)
				.build());

		if (meter == null) {
			meter = new MicMeter();
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFFFFFFFF);
		int left = width / 2 - 155;
		context.drawTextWithShadow(textRenderer, Text.translatable("screen.momenton.settings.level"), left, 104, 0xFFA0A0A0);
		int barLeft = left + 100;
		context.fill(barLeft, 103, left + 310, 113, 0xFF202020);
		if (meter.unavailable()) {
			context.drawTextWithShadow(textRenderer, Text.translatable("screen.momenton.settings.no_input"), barLeft + 4, 104, 0xFFFF5555);
		} else {
			context.fill(barLeft, 103, barLeft + Math.round(meter.level() * (310 - 100)), 113, 0xFF55FF55);
		}
	}

	@Override
	public void removed() {
		meter.close();
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	private static Text deviceText(String device, List<String> available) {
		if (device.isEmpty()) {
			return Text.translatable("screen.momenton.settings.device.default");
		}
		return available.contains(device) ? Text.literal(device) : Text.translatable("screen.momenton.settings.device.missing", device);
	}
}
