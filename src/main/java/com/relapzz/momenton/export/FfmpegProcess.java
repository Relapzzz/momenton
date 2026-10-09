package com.relapzz.momenton.export;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Optional;

public final class FfmpegProcess implements AutoCloseable {
	public static Optional<Path> locate() {
		throw new UnsupportedOperationException();
	}

	public static FfmpegProcess start(Path ffmpeg, int width, int height, int fps, Path output) throws IOException {
		throw new UnsupportedOperationException();
	}

	public void writeFrame(ByteBuffer rgba) throws IOException {
		throw new UnsupportedOperationException();
	}

	public void finish() throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void close() {
		throw new UnsupportedOperationException();
	}
}
