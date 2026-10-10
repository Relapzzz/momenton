package com.relapzz.momenton.audio;

import javax.sound.sampled.AudioFormat;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class WavWriter implements Closeable {
	public static final int SAMPLE_RATE = 48_000;
	public static final int FRAME_BYTES = 2;
	public static final int HEADER_BYTES = 44;
	public static final AudioFormat FORMAT = format(SAMPLE_RATE);

	private final FileChannel channel;
	private long dataBytes;

	public WavWriter(Path file) throws IOException {
		channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		channel.write(header(0));
	}

	public static AudioFormat format(float sampleRate) {
		return new AudioFormat(sampleRate, 16, 1, true, false);
	}

	public void write(byte[] pcm, int length) throws IOException {
		channel.write(ByteBuffer.wrap(pcm, 0, length));
		dataBytes += length;
	}

	@Override
	public void close() throws IOException {
		try (channel) {
			channel.write(header(dataBytes), 0);
		}
	}

	private static ByteBuffer header(long dataBytes) {
		return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
				.put(ascii("RIFF")).putInt((int) (HEADER_BYTES - 8 + dataBytes)).put(ascii("WAVE"))
				.put(ascii("fmt ")).putInt(16).putShort((short) 1).putShort((short) 1)
				.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * FRAME_BYTES).putShort((short) FRAME_BYTES).putShort((short) 16)
				.put(ascii("data")).putInt((int) dataBytes)
				.flip();
	}

	private static byte[] ascii(String tag) {
		return tag.getBytes(StandardCharsets.US_ASCII);
	}
}
