package com.relapzz.momenton.export;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.function.Consumer;

public final class FrameGrabber implements AutoCloseable {
	private static final int BUFFERS = 3;

	private final ArrayDeque<GpuBuffer> free = new ArrayDeque<>();
	private int pending;
	private boolean closed;

	public FrameGrabber(int width, int height) {
		for (int i = 0; i < BUFFERS; i++) {
			free.add(RenderSystem.getDevice().createBuffer(() -> "Momenton frame grab", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_HINT_CLIENT_STORAGE | GpuBuffer.USAGE_COPY_DST, (long) width * height * 4));
		}
	}

	public boolean grab(GpuTexture source, Consumer<ByteBuffer> sink) {
		GpuBuffer buffer = free.poll();
		if (buffer == null) {
			return false;
		}
		pending++;
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(source, buffer, 0, () -> deliver(buffer, sink), 0);
		return true;
	}

	public boolean idle() {
		return pending == 0;
	}

	@Override
	public void close() {
		closed = true;
		free.forEach(GpuBuffer::close);
		free.clear();
	}

	private void deliver(GpuBuffer buffer, Consumer<ByteBuffer> sink) {
		pending--;
		if (closed) {
			buffer.close();
			return;
		}
		try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(buffer, true, false)) {
			sink.accept(view.data());
		}
		free.add(buffer);
	}
}
