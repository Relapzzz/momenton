package com.relapzz.momenton.export;

import com.relapzz.momenton.Momenton;

import java.io.IOException;
import java.nio.file.Path;

public final class ExportFinisher {
	private final ExportSettings settings;
	private final FfmpegProcess ffmpeg;
	private final Path video;
	private final Path pcm;
	private final int frames;
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
			FfmpegProcess.mux(settings.ffmpegCommand(), video, pcm, settings.output(), FfmpegProcess.logPath(), process -> {
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
			if (wrote && (cancelled || error != null)) {
				FfmpegProcess.deleteReleased(settings.output());
			}
			done = true;
		}
	}
}
