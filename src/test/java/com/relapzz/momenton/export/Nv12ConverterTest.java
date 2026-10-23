package com.relapzz.momenton.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Nv12ConverterTest {
	@Test
	void matchesFfmpegOnNoise(@TempDir Path dir) throws IOException {
		Random random = new Random(1);
		ByteBuffer frame = ByteBuffer.allocateDirect(256 * 128 * 4);
		for (int i = 0; i < frame.capacity(); i++) {
			frame.put(i, (byte) random.nextInt(256));
		}
		assertMatchesFfmpeg(dir, frame, 256, 128, 60, 60);
	}

	@Test
	void matchesFfmpegOnGradients(@TempDir Path dir) throws IOException {
		int width = 640;
		int height = 360;
		ByteBuffer frame = ByteBuffer.allocateDirect(width * height * 4);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int i = (y * width + x) * 4;
				frame.put(i, (byte) (x * 255 / (width - 1)));
				frame.put(i + 1, (byte) (y * 255 / (height - 1)));
				frame.put(i + 2, (byte) ((x + y) * 255 / (width + height - 2)));
				frame.put(i + 3, (byte) 255);
			}
		}
		assertMatchesFfmpeg(dir, frame, width, height, 65, 65);
	}

	@Test
	void matchesFfmpegOnHardEdges(@TempDir Path dir) throws IOException {
		assertMatchesFfmpeg(dir, hardEdges(640, 360), 640, 360, 60, 60);
	}

	@Test
	void ffmpegReceivesTheConvertedFrameUnchanged(@TempDir Path dir) throws IOException {
		int width = 640;
		int height = 362;
		ByteBuffer frame = hardEdges(width, height);
		for (FrameFormat format : new FrameFormat[] {FrameFormat.NV12_FROM_RGBA, FrameFormat.NV12_FROM_BGRA}) {
			Path output = dir.resolve(format + ".nv12");
			try (FfmpegProcess process = FfmpegProcess.start(FfmpegProcessTest.ffmpeg(), "-y %INPUT% -f rawvideo -pix_fmt nv12 \"%FILENAME%\"",
					format, width, height, 30, 0, output, dir.resolve("export.log"))) {
				process.writeFrame(frame);
				process.finish();
			}
			assertArrayEquals(convert(frame, width, height, format == FrameFormat.NV12_FROM_BGRA), Files.readAllBytes(output), format.toString());
		}
	}

	private static ByteBuffer hardEdges(int width, int height) {
		Random random = new Random(2);
		int[] colours = random.ints(64, 0, 1 << 24).toArray();
		ByteBuffer frame = ByteBuffer.allocateDirect(width * height * 4);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int colour = colours[((y + 3) / 7 % 8) * 8 + (x + 5) / 9 % 8];
				int i = (y * width + x) * 4;
				frame.put(i, (byte) (colour >> 16));
				frame.put(i + 1, (byte) (colour >> 8));
				frame.put(i + 2, (byte) colour);
				frame.put(i + 3, (byte) 255);
			}
		}
		return frame;
	}

	private static void assertMatchesFfmpeg(Path dir, ByteBuffer topDown, int width, int height, double lumaPsnr, double chromaPsnr) throws IOException {
		for (FrameFormat format : new FrameFormat[] {FrameFormat.RGBA_TOP_DOWN, FrameFormat.BGRA_TOP_DOWN}) {
			Path output = dir.resolve(format + ".nv12");
			try (FfmpegProcess process = FfmpegProcess.start(FfmpegProcessTest.ffmpeg(), "-y %INPUT% -f rawvideo -pix_fmt nv12 \"%FILENAME%\"",
					format, width, height, 30, 0, output, dir.resolve("export.log"))) {
				process.writeFrame(topDown);
				process.finish();
			}
			byte[] ffmpeg = Files.readAllBytes(output);
			byte[] converted = convert(topDown, width, height, format == FrameFormat.BGRA_TOP_DOWN);
			String report = String.format(Locale.ROOT, "%s %dx%d %s %s %s", format, width, height,
					plane("Y", converted, ffmpeg, 0, width * height, 1), plane("U", converted, ffmpeg, width * height, ffmpeg.length, 2),
					plane("V", converted, ffmpeg, width * height + 1, ffmpeg.length, 2));
			System.out.println(report);
			assertTrue(maxError(converted, ffmpeg) <= 1, report);
			assertTrue(psnr(converted, ffmpeg, 0, width * height, 1) >= lumaPsnr, report);
			assertTrue(psnr(converted, ffmpeg, width * height, ffmpeg.length, 2) >= chromaPsnr, report);
			assertTrue(psnr(converted, ffmpeg, width * height + 1, ffmpeg.length, 2) >= chromaPsnr, report);
		}
	}

	private static byte[] convert(ByteBuffer source, int width, int height, boolean bgra) {
		byte[] out = new byte[FrameFormat.NV12.bytes(width, height)];
		Nv12Converter.convert(source, out, width, height, bgra, 0, height / 2);
		return out;
	}

	private static int maxError(byte[] a, byte[] b) {
		int max = 0;
		for (int i = 0; i < a.length; i++) {
			max = Math.max(max, Math.abs((a[i] & 255) - (b[i] & 255)));
		}
		return max;
	}

	private static double psnr(byte[] a, byte[] b, int from, int to, int step) {
		double squared = 0;
		int count = 0;
		for (int i = from; i < to; i += step) {
			int d = (a[i] & 255) - (b[i] & 255);
			squared += d * d;
			count++;
		}
		return squared == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 * count / squared);
	}

	private static String plane(String name, byte[] a, byte[] b, int from, int to, int step) {
		int max = 0;
		int exact = 0;
		int count = 0;
		for (int i = from; i < to; i += step) {
			int d = Math.abs((a[i] & 255) - (b[i] & 255));
			max = Math.max(max, d);
			exact += d == 0 ? 1 : 0;
			count++;
		}
		return String.format(Locale.ROOT, "%s psnr %.1f dB max %d exact %.2f%%", name, psnr(a, b, from, to, step), max, 100.0 * exact / count);
	}
}
