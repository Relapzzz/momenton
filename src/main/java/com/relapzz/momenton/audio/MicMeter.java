package com.relapzz.momenton.audio;

import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

public final class MicMeter implements AutoCloseable {
	private volatile boolean running = true;
	private volatile boolean unavailable;
	private volatile float level;

	public MicMeter() {
		Thread.ofPlatform().name("Momenton-MicMeter").daemon().start(this::run);
	}

	public float level() {
		return level;
	}

	public boolean unavailable() {
		return unavailable;
	}

	@Override
	public void close() {
		running = false;
	}

	static float level(byte[] buffer, int length) {
		int peak = 0;
		for (int i = 0; i + 1 < length; i += 2) {
			peak = Math.max(peak, Math.abs((short) (buffer[i] & 0xFF | buffer[i + 1] << 8)));
		}
		return peak == 0 ? 0 : (float) Math.max(0, 1 + Math.log10(peak / 32768.0) / 3);
	}

	private void run() {
		try (TargetDataLine line = MicRecorder.open()) {
			line.start();
			byte[] buffer = new byte[MicRecorder.CHUNK_BYTES];
			while (running) {
				level = level(buffer, line.read(buffer, 0, buffer.length));
			}
		} catch (LineUnavailableException | IllegalArgumentException e) {
			unavailable = true;
		}
	}
}
