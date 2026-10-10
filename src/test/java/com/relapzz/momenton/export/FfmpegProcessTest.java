package com.relapzz.momenton.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FfmpegProcessTest {
	private static final List<String> INPUT = List.of("-y", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", "1920x1080", "-r", "60", "-i", "-");

	@Test
	void splitsOnWhitespace() {
		assertEquals(List.of("-c:v", "libx264", "-crf", "18"), FfmpegProcess.split("  -c:v\tlibx264   -crf 18 "));
		assertEquals(List.of(), FfmpegProcess.split("   "));
	}

	@Test
	void splitKeepsQuotedSpaces() {
		assertEquals(List.of("-i", "my clip.mp4", "out"), FfmpegProcess.split("-i \"my clip.mp4\" out"));
		assertEquals(List.of("-vf", "scale=iw/2:ih/2,format=yuv420p"), FfmpegProcess.split("-vf scale=\"iw/2:ih/2\",format=yuv420p"));
		assertEquals(List.of("a", "", "b"), FfmpegProcess.split("a \"\" b"));
	}

	@Test
	void splitRejectsUnbalancedQuotes() {
		assertThrows(IllegalArgumentException.class, () -> FfmpegProcess.split("-i \"clip.mp4"));
	}

	@Test
	void substitutesEveryPreset() {
		for (EncodingPreset preset : EncodingPreset.values()) {
			List<String> args = FfmpegProcess.split(FfmpegProcess.substitute(preset.arguments(), 1920, 1080, 60, 8_000_000, "my clip." + preset.extension()));
			assertEquals(INPUT, args.subList(0, INPUT.size()), preset.name());
			assertEquals("-an", args.get(INPUT.size()), preset.name());
			assertEquals("my clip." + preset.extension(), args.getLast(), preset.name());
			assertEquals(preset.hasBitrate(), args.contains("8000000"), preset.name());
			assertTrue(args.stream().noneMatch(arg -> arg.contains("%")), preset.name());
		}
	}

	@Test
	void defaultPresetIsCrf18() {
		assertEquals(List.of("-an", "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "clip.mp4"),
				FfmpegProcess.split(FfmpegProcess.substitute(EncodingPreset.MP4_DEFAULT.arguments(), 1920, 1080, 60, 0, "clip.mp4")).subList(INPUT.size(), INPUT.size() + 10));
		assertFalse(EncodingPreset.MP4_DEFAULT.hasBitrate());
		assertTrue(EncodingPreset.WEBM_CUSTOM.hasBitrate());
	}

	@Test
	void audioCodecFollowsContainer() {
		assertEquals("aac", FfmpegProcess.audioCodec(Path.of("out", "clip.mp4")));
		assertEquals("aac", FfmpegProcess.audioCodec(Path.of("out", "clip.mkv")));
		assertEquals("libopus", FfmpegProcess.audioCodec(Path.of("out", "clip.WEBM")));
	}

	@Test
	void defaultCommandPrefersProperty() {
		try {
			System.setProperty("momenton.ffmpeg", "/opt/my ffmpeg/ffmpeg");
			assertEquals("/opt/my ffmpeg/ffmpeg", FfmpegProcess.defaultCommand());
		} finally {
			System.clearProperty("momenton.ffmpeg");
		}
	}

	@Test
	void missingBinaryIsNotFound(@TempDir Path dir) {
		assertThrows(FfmpegProcess.NotFoundException.class, () -> FfmpegProcess.start(dir.resolve("missing-ffmpeg").toString(),
				EncodingPreset.MP4_DEFAULT.arguments(), 16, 16, 30, 0, dir.resolve("clip.mp4"), dir.resolve("export.log")));
	}

	@Test
	void unbalancedQuotesAreAStartupFailure(@TempDir Path dir) {
		assertThrows(FfmpegProcess.StartupException.class, () -> FfmpegProcess.start("ffmpeg", "-i - \"%FILENAME%", 16, 16, 30, 0,
				dir.resolve("clip.mp4"), dir.resolve("export.log")));
	}

	@Test
	void earlyExitWithoutOutputIsAStartupFailure(@TempDir Path dir) throws IOException {
		String java = ProcessHandle.current().info().command().orElseThrow();
		Path log = dir.resolve("logs").resolve("export.log");
		FfmpegProcess.StartupException error = assertThrows(FfmpegProcess.StartupException.class, () -> {
			try (FfmpegProcess process = FfmpegProcess.start(java, "-XX:+MomentonNoSuchOption", 16, 16, 30, 0, dir.resolve("clip.mp4"), log)) {
				process.writeFrame(ByteBuffer.allocate(16 * 16 * 4));
				process.finish();
			}
		});
		assertTrue(error.log().startsWith("> " + java + " -XX:+MomentonNoSuchOption\n"), error.log());
		assertTrue(error.log().contains("Unrecognized VM option"), error.log());
		assertEquals(error.log(), Files.readString(log));
	}

	@Test
	void encodesTwoFrames(@TempDir Path dir) throws IOException {
		String ffmpeg = ffmpeg();
		Path output = dir.resolve("nested").resolve("my clip.mp4");
		ByteBuffer frame = ByteBuffer.allocate(16 * 16 * 4);
		try (FfmpegProcess process = FfmpegProcess.start(ffmpeg, EncodingPreset.MP4_DEFAULT.arguments(), 16, 16, 30, 0, output, dir.resolve("export.log"))) {
			process.writeFrame(frame);
			frame.put(0, (byte) 255);
			process.writeFrame(frame);
			process.finish();
		}
		assertTrue(Files.size(output) > 0);
		assertTrue(Files.readString(dir.resolve("export.log")).contains("Output #0"));
	}

	@Test
	void unknownFormatIsAStartupFailure(@TempDir Path dir) {
		String ffmpeg = ffmpeg();
		FfmpegProcess.StartupException error = assertThrows(FfmpegProcess.StartupException.class, () -> {
			try (FfmpegProcess process = FfmpegProcess.start(ffmpeg, EncodingPreset.MP4_DEFAULT.arguments(), 16, 16, 30, 0, dir.resolve("clip.unknownformat"), dir.resolve("export.log"))) {
				process.writeFrame(ByteBuffer.allocate(16 * 16 * 4));
				process.finish();
			}
		});
		assertTrue(error.getMessage().contains("unknownformat"), error.getMessage());
	}

	@Test
	void rejectsWrongFrameSize(@TempDir Path dir) throws IOException {
		String ffmpeg = ffmpeg();
		try (FfmpegProcess process = FfmpegProcess.start(ffmpeg, EncodingPreset.MP4_DEFAULT.arguments(), 16, 16, 30, 0, dir.resolve("clip.mp4"), dir.resolve("export.log"))) {
			assertThrows(IllegalArgumentException.class, () -> process.writeFrame(ByteBuffer.allocate(10)));
		}
	}

	private static String ffmpeg() {
		String command = System.getProperty("momenton.ffmpeg", "ffmpeg");
		boolean runs;
		try {
			Process process = new ProcessBuilder(command, "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			runs = process.waitFor() == 0;
		} catch (IOException | InterruptedException e) {
			runs = false;
		}
		assumeTrue(runs, "ffmpeg not found");
		return command;
	}
}
