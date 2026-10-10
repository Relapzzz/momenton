package com.relapzz.momenton.export;

import java.util.Locale;

final class ExportTimings {
	enum Pass { SETTLE, TERRAIN, STALL, GRAB, DRAIN }

	private final long frameBytes;
	private long last = System.nanoTime();
	private long renderStart;
	private long windowStart = last;
	private int frames;
	private int passes;
	private int stalls;
	private long render;
	private long settle;
	private long terrain;
	private long map;
	private long submit;
	private long ffmpegWait;
	private long copy;
	private int copies;
	private long audio;
	private long overlay;

	ExportTimings(long frameBytes) {
		this.frameBytes = frameBytes;
	}

	void renderStarted() {
		renderStart = System.nanoTime();
	}

	void pass(Pass pass) {
		long now = System.nanoTime();
		long wall = now - last;
		last = now;
		render += now - renderStart;
		passes++;
		switch (pass) {
			case SETTLE -> settle += wall;
			case TERRAIN -> terrain += wall;
			case STALL -> stalls++;
			case GRAB -> frames++;
			case DRAIN -> {
			}
		}
	}

	void map(long nanos) {
		map += nanos;
	}

	void submit(long nanos, long waited) {
		submit += nanos - waited;
		ffmpegWait += waited;
	}

	synchronized void copied(long nanos) {
		copy += nanos;
		copies++;
	}

	void audio(long nanos) {
		audio += nanos;
	}

	void overlay(long nanos) {
		overlay += nanos;
	}

	synchronized String report(int encoded) {
		long now = System.nanoTime();
		int n = Math.max(1, frames);
		String line = String.format(Locale.ROOT,
				"%.1f fps, per frame: %.2f passes, pass %.1f ms, render %.1f ms, settle %.1f ms, terrain wait %.1f ms, readback stalls %d, map %.2f ms, submit %.2f ms, ffmpeg wait %.1f ms, copy %.1f ms off-thread at %.0f MB/s, audio %.2f ms, overlay %.2f ms; ffmpeg has encoded %d",
				frames * 1e9 / Math.max(1, now - windowStart), (double) passes / n, ms(now - windowStart, n), ms(render, n), ms(settle, n),
				ms(terrain, n), stalls, ms(map, n), ms(submit, n), ms(ffmpegWait, n), ms(copy, Math.max(1, copies)), copies * frameBytes * 1e3 / Math.max(1, copy),
				ms(audio, n), ms(overlay, n), encoded);
		windowStart = now;
		frames = passes = stalls = copies = 0;
		render = settle = terrain = map = submit = ffmpegWait = copy = audio = overlay = 0;
		return line;
	}

	private static double ms(long nanos, int frames) {
		return nanos / 1e6 / frames;
	}
}
