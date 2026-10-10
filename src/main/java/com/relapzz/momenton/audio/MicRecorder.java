package com.relapzz.momenton.audio;

import com.relapzz.momenton.Momenton;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public final class MicRecorder {
	private static final double NANOS_PER_TICK = 50_000_000.0;
	private static final int CHUNK_BYTES = WavWriter.SAMPLE_RATE / 50 * WavWriter.FRAME_BYTES;

	private static boolean enabled = true;
	private static boolean unavailableLogged;

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
		return enabled ? new MicRecorder(file, originTick) : null;
	}

	public static void toggle(MinecraftClient client) {
		enabled = !enabled;
		if (client.player != null) {
			client.player.sendMessage(Text.translatable(enabled ? "message.momenton.mic.on" : "message.momenton.mic.off"), true);
		}
	}

	public void stop() {
		running = false;
	}

	@Nullable
	public Double await() {
		return startTick.join();
	}

	public void attachTo(Path replay) {
		try {
			MicTrack.attach(file, replay);
		} catch (IOException e) {
			Momenton.LOGGER.error("Could not add the microphone track to {}, it is left at {}", replay, file, e);
		}
	}

	@Nullable
	private Double capture() {
		try (TargetDataLine line = AudioSystem.getTargetDataLine(WavWriter.FORMAT)) {
			line.open(WavWriter.FORMAT);
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
