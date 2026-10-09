package com.relapzz.momenton.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FfmpegProcessTest {
	@Test
	void buildsRawRgbaToH264Command() {
		Path ffmpeg = Path.of("bin", "ffmpeg");
		Path output = Path.of("out", "clip.mp4");
		assertEquals(List.of(ffmpeg.toString(), "-y", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", "1920x1080", "-r", "60",
				"-i", "pipe:0", "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", output.toString()),
			FfmpegProcess.command(ffmpeg, 1920, 1080, 60, output));
	}

	@Test
	void locatePrefersExecutableProperty(@TempDir Path dir) throws IOException {
		Path binary = Files.createFile(dir.resolve("my-ffmpeg"));
		assumeTrue(binary.toFile().setExecutable(true));
		Path missing = dir.resolve("missing-ffmpeg");
		try {
			System.setProperty("momenton.ffmpeg", binary.toString());
			assertEquals(Optional.of(binary), FfmpegProcess.locate());
			System.setProperty("momenton.ffmpeg", missing.toString());
			assertNotEquals(Optional.of(missing), FfmpegProcess.locate());
		} finally {
			System.clearProperty("momenton.ffmpeg");
		}
	}

	@Test
	void encodesTwoFrames(@TempDir Path dir) throws IOException {
		Optional<Path> ffmpeg = FfmpegProcess.locate();
		assumeTrue(ffmpeg.isPresent(), "ffmpeg not found");
		Path output = dir.resolve("nested").resolve("clip.mp4");
		ByteBuffer frame = ByteBuffer.allocate(16 * 16 * 4);
		try (FfmpegProcess process = FfmpegProcess.start(ffmpeg.get(), 16, 16, 30, output)) {
			process.writeFrame(frame);
			frame.put(0, (byte) 255);
			process.writeFrame(frame);
			process.finish();
		}
		assertTrue(Files.size(output) > 0);
	}

	@Test
	void reportsFfmpegFailure(@TempDir Path dir) {
		Optional<Path> ffmpeg = FfmpegProcess.locate();
		assumeTrue(ffmpeg.isPresent(), "ffmpeg not found");
		IOException error = assertThrows(IOException.class, () -> {
			try (FfmpegProcess process = FfmpegProcess.start(ffmpeg.get(), 16, 16, 30, dir.resolve("clip.unknownformat"))) {
				process.writeFrame(ByteBuffer.allocate(16 * 16 * 4));
				process.finish();
			}
		});
		assertTrue(error.getMessage().contains("unknownformat"), error.getMessage());
	}

	@Test
	void rejectsWrongFrameSize(@TempDir Path dir) throws IOException {
		Optional<Path> ffmpeg = FfmpegProcess.locate();
		assumeTrue(ffmpeg.isPresent(), "ffmpeg not found");
		try (FfmpegProcess process = FfmpegProcess.start(ffmpeg.get(), 16, 16, 30, dir.resolve("clip.mp4"))) {
			assertThrows(IllegalArgumentException.class, () -> process.writeFrame(ByteBuffer.allocate(10)));
		}
	}
}
