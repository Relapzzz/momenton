package com.relapzz.momenton.export;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

public final class FrameGrabber implements AutoCloseable {
	private static final int BUFFERS = 3;

	private final int width;
	private final int height;
	private final ByteBuffer frame;
	private final GpuBuffer[] buffers = new GpuBuffer[BUFFERS];
	private final Pending[] pending = new Pending[BUFFERS];
	private int next;

	public FrameGrabber(int width, int height) {
		this.width = width;
		this.height = height;
		frame = ByteBuffer.allocateDirect(width * height * 4);
		for (int i = 0; i < BUFFERS; i++) {
			buffers[i] = RenderSystem.getDevice().createBuffer(() -> "Momenton frame grab", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) width * height * 4);
		}
	}

	public void grab(GpuTexture source, Consumer<ByteBuffer> sink) {
		deliver(next);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.copyTextureToBuffer(source, buffers[next], 0, () -> {
		}, 0);
		pending[next] = new Pending(encoder.createFence(), sink);
		next = (next + 1) % BUFFERS;
	}

	public void flush() {
		for (int i = 0; i < BUFFERS; i++) {
			deliver((next + i) % BUFFERS);
		}
	}

	@Override
	public void close() {
		for (int i = 0; i < BUFFERS; i++) {
			if (pending[i] != null) {
				pending[i].fence().close();
				pending[i] = null;
			}
			buffers[i].close();
		}
	}

	private void deliver(int slot) {
		Pending grab = pending[slot];
		if (grab == null) {
			return;
		}
		pending[slot] = null;
		try (GpuFence fence = grab.fence()) {
			fence.awaitCompletion(Long.MAX_VALUE);
		}
		int stride = width * 4;
		try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(buffers[slot], true, false)) {
			ByteBuffer data = view.data();
			for (int y = 0; y < height; y++) {
				frame.put(y * stride, data, (height - 1 - y) * stride, stride);
			}
		}
		grab.sink().accept(frame.clear());
	}

	private record Pending(GpuFence fence, Consumer<ByteBuffer> sink) {
	}
}
