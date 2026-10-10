package com.relapzz.momenton.export;

import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.audio.MicRecorder;
import com.relapzz.momenton.audio.WavWriter;
import com.relapzz.momenton.edit.Clip;
import com.relapzz.momenton.edit.TimelineResolver;
import com.relapzz.momenton.format.ReplayMetadata;
import com.relapzz.momenton.format.ReplayReader;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ExportFinisher {
	private static final int COPY_BYTES = 1 << 16;

	public static boolean mixCommentary;

	private final ExportSettings settings;
	private final FfmpegProcess ffmpeg;
	private final Path video;
	private final Path pcm;
	private final Path mic;
	private final int frames;
	private final boolean mix = mixCommentary;
	private final long started = System.nanoTime();
	private volatile boolean muxing;
	private volatile boolean cancelled;
	private volatile boolean done;
	private volatile double muxedSeconds;
	private volatile Process mux;
	private volatile IOException error;

	private ExportFinisher(ExportSettings settings, FfmpegProcess ffmpeg, Path video, Path pcm, int frames) {
		this.settings = settings;
		this.ffmpeg = ffmpeg;
		this.video = video;
		this.pcm = pcm;
		this.mic = settings.output().resolveSibling("momenton-mic-" + settings.output().getFileName() + ".pcm");
		this.frames = frames;
	}

	static ExportFinisher start(ExportSettings settings, FfmpegProcess ffmpeg, Path video, Path pcm, int frames) {
		ExportFinisher finisher = new ExportFinisher(settings, ffmpeg, video, pcm, frames);
		Thread.ofPlatform().name("Momenton export finish").daemon().start(finisher::run);
		return finisher;
	}

	public ExportSettings settings() {
		return settings;
	}

	public boolean muxing() {
		return muxing;
	}

	public double progress() {
		return muxing ? muxedSeconds * settings.fps() / frames : (double) ffmpeg.encodedFrames() / frames;
	}

	public long elapsedNanos() {
		return System.nanoTime() - started;
	}

	public boolean done() {
		return done;
	}

	public boolean cancelled() {
		return cancelled;
	}

	public IOException error() {
		return error;
	}

	public void cancel() {
		cancelled = true;
		ffmpeg.close();
		Process process = mux;
		if (process != null) {
			process.destroyForcibly();
		}
	}

	private void run() {
		boolean wrote = false;
		try {
			ffmpeg.finish();
			if (ffmpeg.encodedFrames() != frames) {
				Momenton.LOGGER.warn("ffmpeg reported {} frames, expected {}", ffmpeg.encodedFrames(), frames);
			}
			Momenton.LOGGER.info("ffmpeg encoded {} of {} frames, {} s after the last frame", ffmpeg.encodedFrames(), frames, elapsedNanos() / 1_000_000_000.0);
			muxing = true;
			wrote = true;
			FfmpegProcess.mux(settings.ffmpegCommand(), video, audio(), settings.output(), FfmpegProcess.logPath(), process -> {
				mux = process;
				if (cancelled) {
					process.destroyForcibly();
				}
			}, seconds -> muxedSeconds = seconds);
			Momenton.LOGGER.info("Export saved to {}, {} s after the last frame", settings.output(), elapsedNanos() / 1_000_000_000.0);
		} catch (IOException e) {
			if (!cancelled) {
				error = e;
				Momenton.LOGGER.error("Export failed while finishing", e);
			}
		} finally {
			FfmpegProcess.deleteReleased(video);
			FfmpegProcess.deleteReleased(pcm);
			FfmpegProcess.deleteReleased(mic);
			if (wrote && (cancelled || error != null)) {
				FfmpegProcess.deleteReleased(settings.output());
			}
			done = true;
		}
	}

	private FfmpegProcess.MuxAudio audio() throws IOException {
		ReplayMetadata metadata;
		try (ReplayReader reader = new ReplayReader(settings.replay())) {
			metadata = reader.metadata();
		}
		if (metadata.micStartTick() == null || settings.micVolume() <= 0) {
			return new FfmpegProcess.MuxAudio(pcm, settings.gameVolume(), null, 0, 0, mix);
		}
		int rate = metadata.micSampleRate();
		long samples = Math.round((double) frames * rate / settings.fps());
		writeCommentary(settings.replay(), spans(settings.clips(), metadata.micStartTick(), rate, samples), mic);
		return new FfmpegProcess.MuxAudio(pcm, settings.gameVolume(), mic, rate, settings.micVolume(), mix);
	}

	static List<Span> spans(List<Clip> clips, double micStartTick, int rate, long samples) {
		List<Span> spans = new ArrayList<>();
		long start = 0;
		long ticks = 0;
		for (int i = 0; i < clips.size(); i++) {
			Clip clip = clips.get(i);
			ticks += clip.length();
			long end = i == clips.size() - 1 ? samples : Math.round((double) ticks * rate / TimelineResolver.TICKS_PER_SECOND);
			spans.add(new Span(Math.round((clip.startTick() - micStartTick) * rate / TimelineResolver.TICKS_PER_SECOND), end - start));
			start = end;
		}
		return spans;
	}

	static void writeCommentary(Path replay, List<Span> spans, Path out) throws IOException {
		byte[] buffer = new byte[COPY_BYTES];
		byte[] silence = new byte[COPY_BYTES];
		try (ZipFile zip = new ZipFile(replay.toFile()); OutputStream pcm = new BufferedOutputStream(Files.newOutputStream(out), COPY_BYTES)) {
			ZipEntry entry = zip.getEntry(MicRecorder.ENTRY);
			long recorded = (entry.getSize() - WavWriter.HEADER_BYTES) / WavWriter.FRAME_BYTES;
			for (Span span : spans) {
				long lead = Math.clamp(-span.from(), 0, span.length());
				long voiced = Math.max(0, Math.min(span.from() + span.length(), recorded) - Math.max(span.from(), 0));
				write(pcm, silence, lead);
				if (voiced > 0) {
					try (InputStream in = zip.getInputStream(entry)) {
						in.skipNBytes(WavWriter.HEADER_BYTES + Math.max(span.from(), 0) * WavWriter.FRAME_BYTES);
						for (long left = voiced * WavWriter.FRAME_BYTES; left > 0; ) {
							int read = in.readNBytes(buffer, 0, (int) Math.min(buffer.length, left));
							pcm.write(buffer, 0, read);
							left -= read;
						}
					}
				}
				write(pcm, silence, span.length() - lead - voiced);
			}
		}
	}

	private static void write(OutputStream out, byte[] silence, long samples) throws IOException {
		for (long left = samples * WavWriter.FRAME_BYTES; left > 0; left -= silence.length) {
			out.write(silence, 0, (int) Math.min(silence.length, left));
		}
	}

	record Span(long from, long length) {
	}
}
