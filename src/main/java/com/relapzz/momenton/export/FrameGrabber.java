package com.relapzz.momenton.export;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

final class FrameGrabber implements AutoCloseable {
	private static final int BUFFERS = 4;

	private final ArrayDeque<GpuBuffer> free = new ArrayDeque<>();
	private final List<Copy> copying = new ArrayList<>();
	private final ExportTimings timings;
	private int pending;
	private boolean closed;

	FrameGrabber(int width, int height, ExportTimings timings) {
		this.timings = timings;
		for (int i = 0; i < BUFFERS; i++) {
			free.add(RenderSystem.getDevice().createBuffer(() -> "Momenton frame grab", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_HINT_CLIENT_STORAGE | GpuBuffer.USAGE_COPY_DST, (long) width * height * 4));
		}
	}

	boolean grab(GpuTexture source, Function<ByteBuffer, CompletableFuture<?>> sink) {
		reclaim();
		GpuBuffer buffer = free.poll();
		if (buffer == null) {
			return false;
		}
		pending++;
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(source, buffer, 0, () -> deliver(buffer, sink), 0);
		return true;
	}

	boolean idle() {
		reclaim();
		return pending == 0 && copying.isEmpty();
	}

	@Override
	public void close() {
		closed = true;
		copying.forEach(copy -> copy.done().exceptionally(error -> null).join());
		reclaim();
		free.forEach(GpuBuffer::close);
		free.clear();
	}

	private void deliver(GpuBuffer buffer, Function<ByteBuffer, CompletableFuture<?>> sink) {
		pending--;
		if (closed) {
			buffer.close();
			return;
		}
		long start = System.nanoTime();
		GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(buffer, true, false);
		timings.map(System.nanoTime() - start);
		copying.add(new Copy(buffer, view, sink.apply(view.data())));
	}

	private void reclaim() {
		copying.removeIf(copy -> {
			if (!copy.done().isDone()) {
				return false;
			}
			copy.view().close();
			free.add(copy.buffer());
			return true;
		});
	}

	private record Copy(GpuBuffer buffer, GpuBuffer.MappedView view, CompletableFuture<?> done) {
	}
}
