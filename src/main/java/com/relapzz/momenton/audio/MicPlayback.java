package com.relapzz.momenton.audio;

import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.export.ExportSession;
import com.relapzz.momenton.format.ReplayMetadata;
import com.relapzz.momenton.format.ReplayReader;
import com.relapzz.momenton.playback.ReplaySession;
import net.minecraft.client.MinecraftClient;
import org.jetbrains.annotations.Nullable;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class MicPlayback {
	private static final long NONE = Long.MIN_VALUE;
	private static final double TICKS_PER_SECOND = 20;
	private static final double RESYNC_TICKS = 3;
	private static final long PARK_NANOS = 10_000_000;

	private static Path playing;
	private static MicPlayback player;

	private final Path file;
	private final double startTick;
	private final int sampleRate;
	private final AtomicLong seekTo = new AtomicLong(NONE);
	private volatile boolean running = true;
	private volatile boolean paused;
	private volatile Anchor anchor;
	private int lastTick = Integer.MIN_VALUE;

	private MicPlayback(Path file, double startTick, int sampleRate) {
		this.file = file;
		this.startTick = startTick;
		this.sampleRate = sampleRate;
		Thread.ofPlatform().name("Momenton-Mic-Playback").daemon().start(this::run);
	}

	public static void onClientTick(MinecraftClient client) {
		Path replay = ExportSession.isExporting() ? null : ReplaySession.file();
		if (!Objects.equals(replay, playing)) {
			if (player != null) {
				player.running = false;
			}
			playing = replay;
			player = replay == null ? null : open(replay);
		}
		if (player != null) {
			player.update(ReplaySession.currentTick(), ReplaySession.isPaused(), client.getRenderTickCounter().getTickProgress(false));
		}
	}

	@Nullable
	private static MicPlayback open(Path replay) {
		ReplayMetadata metadata;
		try (ReplayReader reader = new ReplayReader(replay)) {
			metadata = reader.metadata();
		} catch (IOException e) {
			Momenton.LOGGER.warn("Could not read the microphone track of {}", replay, e);
			return null;
		}
		return metadata.micStartTick() == null ? null : new MicPlayback(replay, metadata.micStartTick(), metadata.micSampleRate());
	}

	private void update(int tick, boolean paused, float tickProgress) {
		this.paused = paused;
		long expected = Math.round((tick + tickProgress - startTick) * sampleRate / TICKS_PER_SECOND);
		boolean jumped = tick != lastTick && tick != lastTick + 1;
		lastTick = tick;
		Anchor at = anchor;
		boolean drifted = !paused && at != null && seekTo.get() == NONE
				&& Math.abs(expected - at.played()) > RESYNC_TICKS * sampleRate / TICKS_PER_SECOND;
		if (jumped || drifted) {
			seekTo.set(expected);
		}
	}

	private void run() {
		AudioFormat format = WavWriter.format(sampleRate);
		try (ZipFile zip = new ZipFile(file.toFile()); SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
			ZipEntry entry = zip.getEntry(MicTrack.ENTRY);
			if (entry == null) {
				Momenton.LOGGER.warn("{} lists a microphone track but has no {}", file, MicTrack.ENTRY);
				return;
			}
			long samples = (entry.getSize() - WavWriter.HEADER_BYTES) / WavWriter.FRAME_BYTES;
			int chunkBytes = sampleRate / 50 * WavWriter.FRAME_BYTES;
			byte[] chunk = new byte[chunkBytes];
			byte[] silence = new byte[chunkBytes];
			line.open(format, chunkBytes * 5);
			InputStream in = null;
			long cursor = 0;
			while (running) {
				long seek = seekTo.get();
				if (seek != NONE) {
					line.flush();
					if (in != null) {
						in.close();
						in = null;
					}
					cursor = seek;
					anchor = new Anchor(line, seek, line.getLongFramePosition());
					seekTo.compareAndSet(seek, NONE);
				}
				if (paused || anchor == null) {
					line.stop();
					LockSupport.parkNanos(PARK_NANOS);
					continue;
				}
				line.start();
				int bytes;
				if (cursor < 0 || cursor >= samples) {
					bytes = (int) Math.min(chunkBytes, cursor < 0 ? -cursor * WavWriter.FRAME_BYTES : chunkBytes);
					line.write(silence, 0, bytes);
				} else {
					if (in == null) {
						in = zip.getInputStream(entry);
						in.skipNBytes(WavWriter.HEADER_BYTES + cursor * WavWriter.FRAME_BYTES);
					}
					bytes = in.readNBytes(chunk, 0, (int) Math.min(chunkBytes, (samples - cursor) * WavWriter.FRAME_BYTES));
					line.write(chunk, 0, bytes);
				}
				cursor += bytes / WavWriter.FRAME_BYTES;
			}
		} catch (LineUnavailableException | IOException | IllegalArgumentException e) {
			Momenton.LOGGER.warn("Could not play the microphone track of {}", file, e);
		}
	}

	private record Anchor(SourceDataLine line, long sample, long linePosition) {
		long played() {
			return sample + line.getLongFramePosition() - linePosition;
		}
	}
}
