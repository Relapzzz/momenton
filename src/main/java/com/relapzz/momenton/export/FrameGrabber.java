package com.relapzz.momenton.export;

import com.mojang.blaze3d.textures.GpuTexture;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

public final class FrameGrabber implements AutoCloseable {
	public FrameGrabber(int width, int height) {
		throw new UnsupportedOperationException();
	}

	public void grab(GpuTexture source, Consumer<ByteBuffer> sink) {
		throw new UnsupportedOperationException();
	}

	public void flush() {
		throw new UnsupportedOperationException();
	}

	@Override
	public void close() {
		throw new UnsupportedOperationException();
	}
}
