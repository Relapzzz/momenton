package com.relapzz.momenton.audio;

import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.config.MomentonConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public final class MicRecorder {
	public static final String ENTRY = "audio/mic.wav";

	private static final double NANOS_PER_TICK = 50_000_000.0;
	static final int CHUNK_BYTES = WavWriter.SAMPLE_RATE / 50 * WavWriter.FRAME_BYTES;

	private static final DataLine.Info LINE = new DataLine.Info(TargetDataLine.class, WavWriter.FORMAT);

	private static boolean unavailableLogged;
	private static boolean missingLogged;

	private final Path file;
	private final double originTick;
	private final long originNanos = System.nanoTime();
	private final CompletableFuture<Double> startTick;
	private volatile boolean running = true;

	private MicRecorder(Path file, double originTick) {
		this.file = file;
		this.originTick = originTick;
		this.startTick = CompletableFuture.supplyAsync(this::capture,
				task -> Thread.ofPlatform().name("Momenton-Mic").daemon().start(task));
	}

	@Nullable
	public static MicRecorder start(Path file, double originTick) {
		return MomentonConfig.get().micEnabled ? new MicRecorder(file, originTick) : null;
	}

	public static void toggle(MinecraftClient client) {
		MomentonConfig config = MomentonConfig.get();
		config.micEnabled = !config.micEnabled;
		config.save();
		if (client.player != null) {
			client.player.sendMessage(Text.translatable(config.micEnabled ? "message.momenton.mic.on" : "message.momenton.mic.off"), true);
		}
	}

	public static List<String> devices() {
		return captureMixers().map(Mixer.Info::getName).toList();
	}

	static TargetDataLine open() throws LineUnavailableException {
		String device = MomentonConfig.get().micDevice;
		Mixer.Info mixer = device.isEmpty() ? null : captureMixers().filter(info -> info.getName().equals(device)).findFirst().orElse(null);
		if (!device.isEmpty() && mixer == null && !missingLogged) {
			missingLogged = true;
			Momenton.LOGGER.warn("Microphone {} not found, using the system default", device);
		}
		TargetDataLine line = AudioSystem.getTargetDataLine(WavWriter.FORMAT, mixer);
		line.open(WavWriter.FORMAT);
		return line;
	}

	private static Stream<Mixer.Info> captureMixers() {
		return Arrays.stream(AudioSystem.getMixerInfo()).filter(info -> AudioSystem.getMixer(info).isLineSupported(LINE));
	}

	public void stop() {
		running = false;
	}

	@Nullable
	public Double await() {
		return startTick.join();
	}

	@Nullable
	private Double capture() {
		try (TargetDataLine line = open()) {
			try (WavWriter wav = new WavWriter(file)) {
				line.start();
				double tick = originTick + (System.nanoTime() - originNanos) / NANOS_PER_TICK;
				byte[] buffer = new byte[CHUNK_BYTES];
				while (running) {
					wav.write(buffer, line.read(buffer, 0, buffer.length));
				}
				return tick;
			}
		} catch (LineUnavailableException | IllegalArgumentException e) {
			if (!unavailableLogged) {
				unavailableLogged = true;
				Momenton.LOGGER.warn("No microphone available, recording without commentary: {}", e.toString());
			}
			return null;
		} catch (IOException e) {
			Momenton.LOGGER.error("Microphone recording failed, partial track left at {}", file, e);
			return null;
		}
	}
}
