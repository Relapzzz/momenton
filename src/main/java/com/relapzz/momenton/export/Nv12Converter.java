package com.relapzz.momenton.export;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

final class Nv12Converter {
	private static final int SHIFT = 40;
	private static final double KR = 0.2126;
	private static final double KG = 0.7152;
	private static final double KB = 0.0722;
	private static final double Y_SCALE = 219.0 / 255.0;
	private static final double U_SCALE = 224.0 / 1.8556 / 255.0 / 2.0 / 640.0;
	private static final double V_SCALE = 224.0 / 1.5748 / 255.0 / 2.0 / 640.0;
	private static final long YR = fixed(Y_SCALE * KR);
	private static final long YG = fixed(Y_SCALE * KG);
	private static final long YB = fixed(Y_SCALE * KB);
	private static final long UR = fixed(-U_SCALE * KR);
	private static final long UG = fixed(-U_SCALE * KG);
	private static final long UB = fixed(U_SCALE * (1 - KB));
	private static final long VR = fixed(V_SCALE * (1 - KR));
	private static final long VG = fixed(-V_SCALE * KG);
	private static final long VB = fixed(-V_SCALE * KB);
	private static final long Y_OFFSET = fixed(16.5);
	private static final long C_OFFSET = fixed(128.5);

	private Nv12Converter() {
	}

	static void convert(ByteBuffer source, byte[] out, int width, int height, boolean bgra, int from, int to) {
		int red = bgra ? 16 : 0;
		int blue = bgra ? 0 : 16;
		int half = width / 2;
		IntBuffer frame = source.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
		int[] pixels = new int[width];
		int[] r = new int[8 * half];
		int[] g = new int[8 * half];
		int[] b = new int[8 * half];
		for (int y = 2 * from - 3; y < 2 * to + 3; y++) {
			int clamped = Math.clamp(y, 0, height - 1);
			frame.get(clamped * width, pixels);
			if (y == clamped && y >= 2 * from && y < 2 * to) {
				int line = y * width;
				for (int x = 0; x < width; x++) {
					int p = pixels[x];
					out[line + x] = (byte) (YR * (p >> red & 255) + YG * (p >> 8 & 255) + YB * (p >> blue & 255) + Y_OFFSET >> SHIFT);
				}
			}
			int slot = (y & 7) * half;
			for (int x = 0; x < half; x++) {
				int p = pixels[2 * x];
				int q = pixels[2 * x + 1];
				r[slot + x] = (p >> red & 255) + (q >> red & 255);
				g[slot + x] = (p >> 8 & 255) + (q >> 8 & 255);
				b[slot + x] = (p >> blue & 255) + (q >> blue & 255);
			}
			if (y >= 2 * from + 4 && (y & 1) == 0) {
				int row = y / 2 - 2;
				int s0 = (y - 7 & 7) * half;
				int s1 = (y - 6 & 7) * half;
				int s2 = (y - 5 & 7) * half;
				int s3 = (y - 4 & 7) * half;
				int s4 = (y - 3 & 7) * half;
				int s5 = (y - 2 & 7) * half;
				int s6 = (y - 1 & 7) * half;
				int line = width * height + row * width;
				for (int x = 0; x < half; x++) {
					long rf = 279 * (r[s3 + x] + r[s4 + x]) + 77 * (r[s2 + x] + r[s5 + x]) - 27 * (r[s1 + x] + r[s6 + x]) - 9 * (r[s0 + x] + r[slot + x]);
					long gf = 279 * (g[s3 + x] + g[s4 + x]) + 77 * (g[s2 + x] + g[s5 + x]) - 27 * (g[s1 + x] + g[s6 + x]) - 9 * (g[s0 + x] + g[slot + x]);
					long bf = 279 * (b[s3 + x] + b[s4 + x]) + 77 * (b[s2 + x] + b[s5 + x]) - 27 * (b[s1 + x] + b[s6 + x]) - 9 * (b[s0 + x] + b[slot + x]);
					out[line + 2 * x] = (byte) Math.clamp(UR * rf + UG * gf + UB * bf + C_OFFSET >> SHIFT, 0, 255);
					out[line + 2 * x + 1] = (byte) Math.clamp(VR * rf + VG * gf + VB * bf + C_OFFSET >> SHIFT, 0, 255);
				}
			}
		}
	}

	private static long fixed(double value) {
		return Math.round(value * (1L << SHIFT));
	}
}
