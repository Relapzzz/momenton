package com.relapzz.momenton.audio;

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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

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
	void attachesStoredUnderAudio(@TempDir Path dir) throws IOException {
		Path replay = dir.resolve("test.momenton");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(replay))) {
			zip.putNextEntry(new ZipEntry("metadata.json"));
			zip.write("{}".getBytes());
			zip.closeEntry();
		}
		Path wav = dir.resolve(".test.mic.wav");
		byte[] pcm = pcm(4800);
		try (WavWriter writer = new WavWriter(wav)) {
			writer.write(pcm, pcm.length);
		}
		byte[] expected = Files.readAllBytes(wav);

		MicTrack.attach(wav, replay);

		assertFalse(Files.exists(wav));
		try (ZipFile zip = new ZipFile(replay.toFile())) {
			ZipEntry entry = zip.getEntry(MicTrack.ENTRY);
			assertEquals(ZipEntry.STORED, entry.getMethod());
			assertArrayEquals("{}".getBytes(), zip.getInputStream(zip.getEntry("metadata.json")).readAllBytes());
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
