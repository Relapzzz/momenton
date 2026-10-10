package com.relapzz.momenton.audio;

import com.relapzz.momenton.format.ReplayMetadata;
import com.relapzz.momenton.format.ReplayWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WavWriterTest {
	@Test
	void patchesSizesAndParses(@TempDir Path dir) throws IOException, UnsupportedAudioFileException {
		Path file = dir.resolve("mic.wav");
		byte[] pcm = pcm(1000);
		try (WavWriter wav = new WavWriter(file)) {
			wav.write(pcm, 600);
			wav.write(Arrays.copyOfRange(pcm, 600, pcm.length), pcm.length - 600);
		}

		ByteBuffer header = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(WavWriter.HEADER_BYTES + pcm.length, header.capacity());
		assertEquals(36 + pcm.length, header.getInt(4));
		assertEquals(pcm.length, header.getInt(40));

		try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
			AudioFormat format = in.getFormat();
			assertEquals(48_000f, format.getSampleRate());
			assertEquals(1, format.getChannels());
			assertEquals(16, format.getSampleSizeInBits());
			assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
			assertFalse(format.isBigEndian());
			assertEquals(1000, in.getFrameLength());
			assertArrayEquals(pcm, in.readAllBytes());
		}
	}

	@Test
	void emptyTrackHasZeroData(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("mic.wav");
		new WavWriter(file).close();
		ByteBuffer header = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(WavWriter.HEADER_BYTES, header.capacity());
		assertEquals(36, header.getInt(4));
		assertEquals(0, header.getInt(40));
	}

	@Test
	void stagedTrackIsStoredWithoutDataDescriptor(@TempDir Path dir) throws IOException {
		Path staging = dir.resolve("staging");
		ReplayWriter writer = new ReplayWriter(staging, 0);
		Path wav = staging.resolve(MicRecorder.ENTRY);
		Files.createDirectories(wav.getParent());
		byte[] pcm = pcm(4800);
		try (WavWriter out = new WavWriter(wav)) {
			out.write(pcm, pcm.length);
		}
		byte[] expected = Files.readAllBytes(wav);
		Path replay = dir.resolve("test.momenton");

		writer.finish(1, chunks -> new ReplayMetadata(ReplayMetadata.FORMAT_VERSION, "1.21.11", "1.0.0", 0L, 1, 0L,
				ReplayWriter.CHUNK_TICKS, "Test World", "minecraft:overworld", chunks, List.of(), 1920, 1080, 3), replay);

		try (ZipInputStream stream = new ZipInputStream(Files.newInputStream(replay))) {
			for (ZipEntry entry = stream.getNextEntry(); entry != null; entry = stream.getNextEntry()) {
				stream.readAllBytes();
			}
		}
		try (ZipFile zip = new ZipFile(replay.toFile())) {
			ZipEntry entry = zip.getEntry(MicRecorder.ENTRY);
			assertEquals(ZipEntry.STORED, entry.getMethod());
			try (InputStream in = zip.getInputStream(entry)) {
				assertArrayEquals(expected, in.readAllBytes());
			}
			try (InputStream in = zip.getInputStream(entry)) {
				in.skipNBytes(WavWriter.HEADER_BYTES + 1000L * WavWriter.FRAME_BYTES);
				assertArrayEquals(Arrays.copyOfRange(pcm, 2000, 2010), in.readNBytes(10));
			}
		}
	}

	private static byte[] pcm(int samples) {
		ByteBuffer buffer = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < samples; i++) {
			buffer.putShort((short) (Math.sin(i / 10.0) * 12000));
		}
		return buffer.array();
	}
}
