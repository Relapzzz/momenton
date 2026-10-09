package com.relapzz.momenton.record;

import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.format.ReplayWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;

/**
 * {@code frames.bin}: one fixed-stride record per rendered frame, appended from the render thread.
 *
 * <p>Fixed stride is the point - sampling the track at any output frame rate is an index lookup
 * rather than a scan. Appending allocates nothing: records go into a reused buffer, and a full
 * buffer is handed to the writer thread while the render thread takes the other one from the pool.
 */
public final class FrameTrackWriter {
	public static final int RECORD_BYTES = 56;
	private static final int FRAMES_PER_BLOCK = 256;
	private static final int BUFFERS = 2;

	private final Executor io;
	private final ReplayWriter writer;
	private final ArrayBlockingQueue<ByteBuffer> free = new ArrayBlockingQueue<>(BUFFERS);

	private ByteBuffer current;
	private long framesWritten;

	public FrameTrackWriter(Executor io, ReplayWriter writer) {
		this.io = io;
		this.writer = writer;
		for (int i = 0; i < BUFFERS; i++) {
			free.add(ByteBuffer.allocate(RECORD_BYTES * FRAMES_PER_BLOCK).order(ByteOrder.LITTLE_ENDIAN));
		}
		current = take();
	}

	public void append(int tick, float tickProgress, double x, double y, double z,
			float yaw, float pitch, float fov, float mouseX, float mouseY, int flags) {
		current.putInt(tick).putFloat(tickProgress)
				.putDouble(x).putDouble(y).putDouble(z)
				.putFloat(yaw).putFloat(pitch).putFloat(fov)
				.putFloat(mouseX).putFloat(mouseY)
				.putInt(flags);
		framesWritten++;

		if (!current.hasRemaining()) {
			flush();
		}
	}

	public long framesWritten() {
		return framesWritten;
	}

	/** Hands the partly filled buffer over; call once, from the render thread, before finishing. */
	public void close() {
		if (current.position() > 0) {
			flush();
		}
	}

	private void flush() {
		ByteBuffer full = current;
		io.execute(() -> {
			try {
				writer.writeFrames(full);
			} catch (IOException e) {
				Momenton.LOGGER.error("Failed to write frame track", e);
			} finally {
				full.clear();
				free.add(full);
			}
		});
		current = take();
	}

	private ByteBuffer take() {
		try {
			return free.take();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted waiting for a frame buffer", e);
		}
	}
}
