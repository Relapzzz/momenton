package com.relapzz.momenton.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FfmpegProcessTest {
	private static final List<String> RGBA_INPUT = List.of("-y", "-f", "rawvideo", "-pix_fmt", "rgba", "-color_primaries", "bt709", "-color_trc", "bt709",
			"-s", "1920x1080", "-r", "60", "-i", "-", "-vf", "scale=out_color_matrix=bt709:out_range=tv");
	private static final List<String> NV12_INPUT = List.of("-y", "-f", "rawvideo", "-pix_fmt", "nv12", "-colorspace", "bt709", "-color_primaries", "bt709",
			"-color_trc", "bt709", "-color_range", "tv", "-s", "1920x1080", "-r", "60", "-i", "-");

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
	void substitutesEveryPresetForEitherFrameFormat() {
		for (FrameFormat format : FrameFormat.values()) {
			for (EncodingPreset preset : EncodingPreset.values()) {
				List<String> args = FfmpegProcess.split(FfmpegProcess.substitute(format.expand(preset.arguments()), 1920, 1080, 60, 8_000_000, "my clip." + preset.extension()));
				List<String> input = format == FrameFormat.NV12 && preset != EncodingPreset.MKV_LOSSLESS ? NV12_INPUT : RGBA_INPUT;
				assertEquals(input, args.subList(0, input.size()), format + " " + preset);
				assertEquals("-an", args.get(input.size()), format + " " + preset);
				assertEquals("my clip." + preset.extension(), args.getLast(), format + " " + preset);
				assertEquals(preset.hasBitrate(), args.contains("8000000"), format + " " + preset);
				assertTrue(args.stream().noneMatch(arg -> arg.contains("%")), format + " " + preset);
			}
		}
	}

	@Test
	void frameSizes() {
		assertEquals(1920 * 1080 * 4, FrameFormat.RGBA.bytes(1920, 1080));
		assertEquals(1920 * 1080 * 3 / 2, FrameFormat.NV12.bytes(1920, 1080));
	}

	@Test
	void defaultPresetIsSuperfastCrf18() {
		assertEquals(List.of("-an", "-c:v", "libx264", "-preset", "superfast", "-crf", "18", "-pix_fmt", "yuv420p", "clip.mp4"),
				FfmpegProcess.split(FfmpegProcess.substitute(FrameFormat.NV12.expand(EncodingPreset.MP4_DEFAULT.arguments()), 1920, 1080, 60, 0, "clip.mp4"))
						.subList(NV12_INPUT.size(), NV12_INPUT.size() + 10));
		assertFalse(EncodingPreset.MP4_DEFAULT.hasBitrate());
		assertTrue(EncodingPreset.WEBM_CUSTOM.hasBitrate());
	}

	@Test
	void hardwarePresetsAreOfferedOnlyWhenTheyWork() {
		List<EncodingPreset> software = EncodingPreset.available(Set.of());
		assertEquals(EncodingPreset.MP4_DEFAULT, software.getFirst());
		assertTrue(software.stream().noneMatch(EncodingPreset::hardware));
		List<EncodingPreset> withQsv = EncodingPreset.available(Set.of(EncodingPreset.MP4_QSV));
		assertEquals(EncodingPreset.MP4_QSV, withQsv.getFirst());
		assertEquals(software.size() + 1, withQsv.size());
	}

	@Test
	void probeAcceptsWorkingEncoderAndRejectsMissingOne() {
		String ffmpeg = ffmpeg();
		assertTrue(EncoderProbe.works(ffmpeg, EncodingPreset.MP4_DEFAULT));
		assertFalse(EncoderProbe.works(ffmpeg + "-missing", EncodingPreset.MP4_QSV));
	}

	@Test
	void writeFrameFlipsRowsToTopDown(@TempDir Path dir) throws IOException {
		String ffmpeg = ffmpeg();
		Path output = dir.resolve("frame.rgba");
		ByteBuffer frame = ByteBuffer.allocateDirect(2 * 2 * 4);
		for (int i = 0; i < 8; i++) {
			frame.put(i, (byte) (i < 4 ? 1 : 2));
			frame.put(i + 8, (byte) (i < 4 ? 3 : 4));
		}
		try (FfmpegProcess process = FfmpegProcess.start(ffmpeg, "-y -f rawvideo -pix_fmt rgba -s %WIDTH%x%HEIGHT% -r %FPS% -i - -f rawvideo -pix_fmt rgba \"%FILENAME%\"",
				FrameFormat.RGBA, 2, 2, 30, 0, output, dir.resolve("export.log"))) {
			process.writeFrame(frame);
			process.finish();
			assertEquals(1, process.encodedFrames());
		}
		byte[] written = Files.readAllBytes(output);
		assertEquals(16, written.length);
		assertEquals(3, written[0]);
		assertEquals(4, written[4]);
		assertEquals(1, written[8]);
		assertEquals(2, written[12]);
	}

	@Test
	void framesReachFfmpegInOrderWithEveryRowFlipped(@TempDir Path dir) throws IOException {
		String ffmpeg = ffmpeg();
		int width = 16;
		int height = 67;
		int stride = width * 4;
		int frames = 7;
		Path output = dir.resolve("frames.rgba");
		byte[] expected = new byte[frames * height * stride];
		try (FfmpegProcess process = FfmpegProcess.start(ffmpeg, "-y -f rawvideo -pix_fmt rgba -s %WIDTH%x%HEIGHT% -r %FPS% -i - -f rawvideo -pix_fmt rgba \"%FILENAME%\"",
				FrameFormat.RGBA, width, height, 30, 0, output, dir.resolve("export.log"))) {
			for (int f = 0; f < frames; f++) {
				ByteBuffer frame = ByteBuffer.allocateDirect(height * stride);
				for (int row = 0; row < height; row++) {
					for (int x = 0; x < stride; x++) {
						byte value = (byte) (f * 31 + row * 7 + x);
						frame.put(row * stride + x, value);
						expected[f * height * stride + (height - 1 - row) * stride + x] = value;
					}
				}
				process.writeFrame(frame);
			}
			process.finish();
			assertEquals(frames, process.encodedFrames());
		}
		assertArrayEquals(expected, Files.readAllBytes(output));
	}

	@Test
	void nv12FramesReachFfmpegUnchangedAndInOrder(@TempDir Path dir) throws IOException {
		String ffmpeg = ffmpeg();
		int width = 16;
		int height = 10;
		int frames = 5;
		int size = FrameFormat.NV12.bytes(width, height);
		Path output = dir.resolve("frames.nv12");
		byte[] expected = new byte[frames * size];
		try (FfmpegProcess process = FfmpegProcess.start(ffmpeg, "-y %INPUT% -f rawvideo -pix_fmt nv12 \"%FILENAME%\"",
				FrameFormat.NV12, width, height, 30, 0, output, dir.resolve("export.log"))) {
			for (int f = 0; f < frames; f++) {
				ByteBuffer frame = ByteBuffer.allocateDirect(size);
				for (int i = 0; i < size; i++) {
					byte value = (byte) (16 + (f * 37 + i * 11) % 220);
					frame.put(i, value);
					expected[f * size + i] = value;
				}
				process.writeFrame(frame);
			}
			process.finish();
			assertEquals(frames, process.encodedFrames());
		}
		assertArrayEquals(expected, Files.readAllBytes(output));
	}

	@Test
	void everyFrameFormatIsTaggedBt709LimitedRange(@TempDir Path dir) throws IOException {
		String ffmpeg = ffmpeg();
		Map<EncodingPreset, FrameFormat> runs = Map.of(EncodingPreset.MP4_DEFAULT, FrameFormat.NV12, EncodingPreset.MP4_CUSTOM, FrameFormat.RGBA,
				EncodingPreset.WEBM_CUSTOM, FrameFormat.NV12, EncodingPreset.MKV_LOSSLESS, FrameFormat.RGBA);
		runs.forEach((preset, format) -> {
			Path log = dir.resolve(preset + ".log");
			try (FfmpegProcess process = FfmpegProcess.start(ffmpeg, preset.arguments(), format, 16, 16, 30, 1_000_000,
					dir.resolve("clip." + preset.extension()), log)) {
				process.writeFrame(ByteBuffer.allocate(format.bytes(16, 16)));
				process.finish();
				String chroma = preset == EncodingPreset.MKV_LOSSLESS ? "yuv444p" : "yuv420p";
				assertTrue(Files.readString(log).contains(chroma + "(tv, bt709, progressive)"), preset.name());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	@Test
	void closeReturnsAtOnceWhileTheWriterIsBlockedAndDiscardDeletesTheVideo(@TempDir Path dir) throws Exception {
		String ffmpeg = ffmpeg();
		Path video = dir.resolve("clip.mp4");
		Path pcm = Files.write(dir.resolve("clip.pcm"), new byte[16]);
		FfmpegProcess process = FfmpegProcess.start(ffmpeg, "-nostdin -y -re -f lavfi -i color=s=16x16:r=10 -t 60 -c:v libx264 -preset ultrafast \"%FILENAME%\"",
				FrameFormat.RGBA, 1920, 1080, 60, 0, video, dir.resolve("export.log"));
		process.writeFrame(ByteBuffer.allocateDirect(1920 * 1080 * 4)).join();
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (!(writerBlockedInPipe() && Files.exists(video)) && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		assertTrue(writerBlockedInPipe());
		assertTrue(ffmpegRunning());
		assertTrue(Files.exists(video));

		assertTimeoutPreemptively(Duration.ofMillis(500), () -> process.discard(video, pcm));

		while ((ffmpegRunning() || Files.exists(video) || Files.exists(pcm)) && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		assertFalse(ffmpegRunning());
		assertFalse(Files.exists(video));
		assertFalse(Files.exists(pcm));
	}

	private static boolean writerBlockedInPipe() {
		return Thread.getAllStackTraces().entrySet().stream()
				.anyMatch(entry -> entry.getKey().getName().equals("Momenton ffmpeg writer")
						&& Arrays.stream(entry.getValue()).anyMatch(element -> element.getMethodName().equals("writeBytes")));
	}

	private static boolean ffmpegRunning() {
		return ProcessHandle.current().children().anyMatch(child -> child.isAlive() && child.info().command().orElse("").contains("ffmpeg"));
	}

	@Test
	void muxReportsProgressAndWritesTheLog(@TempDir Path dir) throws IOException {
		String ffmpeg = ffmpeg();
		Path video = dir.resolve("video.mp4");
		try (FfmpegProcess process = FfmpegProcess.start(ffmpeg, EncodingPreset.MP4_DEFAULT.arguments(), FrameFormat.RGBA, 16, 16, 30, 0, video, dir.resolve("export.log"))) {
			ByteBuffer frame = ByteBuffer.allocate(16 * 16 * 4);
			for (int i = 0; i < 30; i++) {
				process.writeFrame(frame);
			}
			process.finish();
			assertEquals(30, process.encodedFrames());
		}
		Path pcm = dir.resolve("audio.pcm");
		Files.write(pcm, new byte[48000 * 4]);
		Path output = dir.resolve("out.mp4");
		Path log = dir.resolve("mux.log");
		List<Process> started = new ArrayList<>();
		List<Double> seconds = new ArrayList<>();
		FfmpegProcess.mux(ffmpeg, video, new FfmpegProcess.MuxAudio(pcm, 1.0f, null, 0, 0, false), output, log, started::add, seconds::add);
		assertEquals(1, started.size());
		assertFalse(seconds.isEmpty());
		assertTrue(seconds.getLast() > 0.9, seconds.toString());
		assertTrue(Files.size(output) > 0);
		assertTrue(Files.readString(log).startsWith("> " + ffmpeg + " -nostdin"));
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
				EncodingPreset.MP4_DEFAULT.arguments(), FrameFormat.RGBA, 16, 16, 30, 0, dir.resolve("clip.mp4"), dir.resolve("export.log")));
	}

	@Test
	void unbalancedQuotesAreAStartupFailure(@TempDir Path dir) {
		assertThrows(FfmpegProcess.StartupException.class, () -> FfmpegProcess.start("ffmpeg", "-i - \"%FILENAME%", FrameFormat.RGBA, 16, 16, 30, 0,
				dir.resolve("clip.mp4"), dir.resolve("export.log")));
	}

	@Test
	void earlyExitWithoutOutputIsAStartupFailure(@TempDir Path dir) throws IOException {
		String java = ProcessHandle.current().info().command().orElseThrow();
		Path log = dir.resolve("logs").resolve("export.log");
		FfmpegProcess.StartupException error = assertThrows(FfmpegProcess.StartupException.class, () -> {
			try (FfmpegProcess process = FfmpegProcess.start(java, "-XX:+MomentonNoSuchOption", FrameFormat.RGBA, 16, 16, 30, 0, dir.resolve("clip.mp4"), log)) {
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
		try (FfmpegProcess process = FfmpegProcess.start(ffmpeg, EncodingPreset.MP4_DEFAULT.arguments(), FrameFormat.RGBA, 16, 16, 30, 0, output, dir.resolve("export.log"))) {
			process.writeFrame(frame).join();
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
			try (FfmpegProcess process = FfmpegProcess.start(ffmpeg, EncodingPreset.MP4_DEFAULT.arguments(), FrameFormat.RGBA, 16, 16, 30, 0, dir.resolve("clip.unknownformat"), dir.resolve("export.log"))) {
				process.writeFrame(ByteBuffer.allocate(16 * 16 * 4));
				process.finish();
			}
		});
		assertTrue(error.getMessage().contains("unknownformat"), error.getMessage());
	}

	@Test
	void rejectsWrongFrameSize(@TempDir Path dir) throws IOException {
		String ffmpeg = ffmpeg();
		try (FfmpegProcess process = FfmpegProcess.start(ffmpeg, EncodingPreset.MP4_DEFAULT.arguments(), FrameFormat.RGBA, 16, 16, 30, 0, dir.resolve("clip.mp4"), dir.resolve("export.log"))) {
			assertThrows(IllegalArgumentException.class, () -> process.writeFrame(ByteBuffer.allocate(10)));
		}
		ProcessHandle.current().children().filter(child -> child.info().command().orElse("").contains("ffmpeg")).forEach(child -> child.onExit().join());
	}

	static String ffmpeg() {
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
