package com.relapzz.momenton.export;

public final class ExportProgress {
	private static final int WINDOW = 50;

	private final int total;
	private final long[] times = new long[WINDOW];
	private int done;
	private long started;

	ExportProgress(int total) {
		this.total = total;
	}

	void frameDone() {
		long now = System.nanoTime();
		if (done == 0) {
			started = now;
		}
		times[done % WINDOW] = now;
		done++;
	}

	public int done() {
		return done;
	}

	public int total() {
		return total;
	}

	public long elapsedNanos() {
		return done == 0 ? 0 : System.nanoTime() - started;
	}

	public double fps() {
		int span = Math.min(done, WINDOW) - 1;
		if (span < 1) {
			return 0;
		}
		return span * 1e9 / (times[(done - 1) % WINDOW] - times[(done - 1 - span) % WINDOW]);
	}

	public long remainingNanos() {
		double fps = fps();
		return fps > 0 ? (long) ((total - done) / fps * 1e9) : -1;
	}
}
