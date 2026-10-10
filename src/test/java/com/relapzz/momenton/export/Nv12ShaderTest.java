package com.relapzz.momenton.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Nv12ShaderTest {
	private static final float[] LUMA = {0.2126f, 0.7152f, 0.0722f};
	private static final float[] TAPS = {-0.0140625f, -0.0421875f, 0.1203125f, 0.4359375f, 0.4359375f, 0.1203125f, -0.0421875f, -0.0140625f};

	@Test
	void shaderMatchesFfmpegOnNoise(@TempDir Path dir) throws IOException {
		Random random = new Random(1);
		ByteBuffer frame = ByteBuffer.allocateDirect(256 * 128 * 4);
		for (int i = 0; i < frame.capacity(); i++) {
			frame.put(i, (byte) random.nextInt(256));
		}
		assertMatchesFfmpeg(dir, frame, 256, 128, 60, 60);
	}

	@Test
	void shaderMatchesFfmpegOnGradients(@TempDir Path dir) throws IOException {
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
	void shaderMatchesFfmpegOnHardEdges(@TempDir Path dir) throws IOException {
		int width = 640;
		int height = 360;
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
		assertMatchesFfmpeg(dir, frame, width, height, 60, 60);
	}

	private static void assertMatchesFfmpeg(Path dir, ByteBuffer bottomUp, int width, int height, double lumaPsnr, double chromaPsnr) throws IOException {
		Path output = dir.resolve("ffmpeg.nv12");
		try (FfmpegProcess process = FfmpegProcess.start(FfmpegProcessTest.ffmpeg(), "-y %INPUT% -f rawvideo -pix_fmt nv12 \"%FILENAME%\"",
				FrameFormat.RGBA, width, height, 30, 0, output, dir.resolve("export.log"))) {
			process.writeFrame(bottomUp);
			process.finish();
		}
		byte[] ffmpeg = Files.readAllBytes(output);
		byte[] shader = shader(bottomUp, width, height);
		String report = String.format(Locale.ROOT, "%dx%d %s %s %s", width, height,
				plane("Y", shader, ffmpeg, 0, width * height, 1), plane("U", shader, ffmpeg, width * height, ffmpeg.length, 2),
				plane("V", shader, ffmpeg, width * height + 1, ffmpeg.length, 2));
		System.out.println(report);
		assertTrue(maxError(shader, ffmpeg) <= 1, report);
		assertTrue(psnr(shader, ffmpeg, 0, width * height, 1) >= lumaPsnr, report);
		assertTrue(psnr(shader, ffmpeg, width * height, ffmpeg.length, 2) >= chromaPsnr, report);
		assertTrue(psnr(shader, ffmpeg, width * height + 1, ffmpeg.length, 2) >= chromaPsnr, report);
	}

	private static byte[] shader(ByteBuffer source, int width, int height) {
		byte[] out = new byte[FrameFormat.NV12.bytes(width, height)];
		for (int row = 0; row < FrameFormat.NV12.rows(height); row++) {
			for (int fragment = 0; fragment < width / 4; fragment++) {
				float[] colour = main(source, width, height, fragment, row);
				for (int i = 0; i < 4; i++) {
					out[row * width + fragment * 4 + i] = (byte) Math.round(Math.clamp(colour[i], 0f, 1f) * 255f);
				}
			}
		}
		return out;
	}

	private static float[] main(ByteBuffer source, int width, int height, int fragmentX, int row) {
		int x = fragmentX * 4;
		if (row < height) {
			int y = height - 1 - row;
			return new float[] {luma(source, width, x, y), luma(source, width, x + 1, y), luma(source, width, x + 2, y), luma(source, width, x + 3, y)};
		}
		float[] first = chroma(source, width, x / 2, row - height, height);
		float[] second = chroma(source, width, x / 2 + 1, row - height, height);
		return new float[] {first[0], first[1], second[0], second[1]};
	}

	private static float[] rgb(ByteBuffer source, int width, int x, int y) {
		int i = (y * width + x) * 4;
		return new float[] {(source.get(i) & 255) / 255f, (source.get(i + 1) & 255) / 255f, (source.get(i + 2) & 255) / 255f};
	}

	private static float dot(float[] a, float[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	private static float luma(ByteBuffer source, int width, int x, int y) {
		return (16f + 219f * dot(LUMA, rgb(source, width, x, y))) / 255f;
	}

	private static float[] chroma(ByteBuffer source, int width, int x, int row, int height) {
		float[] sum = new float[3];
		for (int i = 0; i < 8; i++) {
			int y = height - 1 - Math.clamp(2 * row - 3 + i, 0, height - 1);
			float[] left = rgb(source, width, 2 * x, y);
			float[] right = rgb(source, width, 2 * x + 1, y);
			for (int c = 0; c < 3; c++) {
				sum[c] += TAPS[i] * (left[c] + right[c]) * 0.5f;
			}
		}
		float l = dot(LUMA, sum);
		return new float[] {(128f + 224f * ((sum[2] - l) / 1.8556f)) / 255f, (128f + 224f * ((sum[0] - l) / 1.5748f)) / 255f};
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
