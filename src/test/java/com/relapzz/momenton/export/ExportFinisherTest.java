package com.relapzz.momenton.export;

import com.relapzz.momenton.audio.MicRecorder;
import com.relapzz.momenton.audio.WavWriter;
import com.relapzz.momenton.edit.Clip;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExportFinisherTest {
	private static final int RATE = 8000;
	private static final double MIC_START = 50.25;
	private static final int MIC_SAMPLES = 80_000;
	private static final List<Clip> CLIPS = List.of(new Clip(40, 60), new Clip(240, 270), new Clip(300, 311));
	private static final int FPS = 24;

	@Test
	void singleClipStartingBeforeTheMicIsPaddedAtTheFront() {
		List<ExportFinisher.Span> spans = ExportFinisher.spans(List.of(new Clip(0, 100)), 5.5, 48000, samples(List.of(new Clip(0, 100)), 48000, 60));
		assertEquals(List.of(new ExportFinisher.Span(-13_200, 240_000)), spans);
	}

	@Test
	void recordedMicStartUsesTheFractionalTick() {
		assertEquals(List.of(new ExportFinisher.Span(-12_820, 6_350_400)),
				ExportFinisher.spans(List.of(new Clip(0, 2646)), 5.34176096194458, 48000, samples(List.of(new Clip(0, 2646)), 48000, 60)));
	}

	@Test
	void clipsAreConcatenatedInTimelineOrder() {
		List<Clip> clips = List.of(new Clip(100, 140), new Clip(20, 60));
		assertEquals(List.of(new ExportFinisher.Span(240_000, 96_000), new ExportFinisher.Span(48_000, 96_000)),
				ExportFinisher.spans(clips, 0, 48000, samples(clips, 48000, 60)));
	}

	@Test
	void lastClipStretchesToTheLastVideoFrame() {
		long samples = samples(CLIPS, RATE, FPS);
		assertEquals(24_667, samples);
		List<ExportFinisher.Span> spans = ExportFinisher.spans(CLIPS, MIC_START, RATE, samples);
		assertEquals(List.of(new ExportFinisher.Span(-4_100, 8_000), new ExportFinisher.Span(75_900, 12_000), new ExportFinisher.Span(99_900, 4_667)), spans);
		assertEquals(samples, spans.stream().mapToLong(ExportFinisher.Span::length).sum());
	}

	@Test
	void commentaryIsSilentWhereTheMicDoesNotCoverTheClip(@TempDir Path dir) throws IOException {
		Path replay = replay(dir, MIC_SAMPLES, i -> i % 32_000 + 1);
		Path out = dir.resolve("mic.pcm");
		ExportFinisher.writeCommentary(replay, ExportFinisher.spans(CLIPS, MIC_START, RATE, samples(CLIPS, RATE, FPS)), out);
		short[] written = shorts(Files.readAllBytes(out));
		assertEquals(24_667, written.length);
		short[] expected = new short[24_667];
		for (int i = 0; i < 3_900; i++) {
			expected[4_100 + i] = (short) (i % 32_000 + 1);
		}
		for (int i = 0; i < 4_100; i++) {
			expected[8_000 + i] = (short) ((75_900 + i) % 32_000 + 1);
		}
		assertArrayEquals(expected, written);
	}

	@Test
	void muxedCommentaryIsASecondTrackInSyncWithTheVideo(@TempDir Path dir) throws IOException, InterruptedException {
		String ffmpeg = ffmpeg();
		Path video = video(ffmpeg, dir);
		Path game = Files.write(dir.resolve("game.pcm"), new byte[96_000 * 4]);
		Path replay = replay(dir, 48_000, i -> (int) (10_000 * Math.sin(2 * Math.PI * 440 * i / 48_000.0)));
		List<Clip> clips = List.of(new Clip(0, 40));
		Path mic = dir.resolve("mic.pcm");
		ExportFinisher.writeCommentary(replay, ExportFinisher.spans(clips, 10, 48000, samples(clips, 48000, 30)), mic);
		assertEquals(96_000 * 2, Files.size(mic));

		for (String extension : List.of("mp4", "mkv")) {
			Path output = dir.resolve("out." + extension);
			FfmpegProcess.mux(ffmpeg, video, new FfmpegProcess.MuxAudio(game, 1.0f, mic, 48000, 1.0f, false), output, dir.resolve("mux.log"), process -> {}, seconds -> {});
			List<String> streams = probe(ffmpeg, output);
			assertEquals(3, streams.size(), streams.toString());
			assertTrue(streams.get(0).contains("codec_type=video"), streams.toString());
			assertTrue(streams.get(1).contains("channels=2") && streams.get(1).contains("=Game") && streams.get(1).contains("default=1"), streams.toString());
			assertTrue(streams.get(2).contains("channels=1") && streams.get(2).contains("=Commentary") && streams.get(2).contains("default=0"), streams.toString());
			assertEquals(2.0, duration(ffmpeg, output), 0.03, extension);
		}

		short[] voice = decode(ffmpeg, dir.resolve("out.mkv"), "0:a:1");
		assertEquals(96_000, voice.length, 1_024);
		int first = 0;
		while (Math.abs(voice[first]) < 2_000) {
			first++;
		}
		int last = voice.length - 1;
		while (Math.abs(voice[last]) < 2_000) {
			last--;
		}
		assertEquals(24_000, first, 48);
		assertEquals(72_000, last, 48);
	}

	@Test
	void mixedCommentaryIsOneTrack(@TempDir Path dir) throws IOException, InterruptedException {
		String ffmpeg = ffmpeg();
		Path video = video(ffmpeg, dir);
		Path game = Files.write(dir.resolve("game.pcm"), new byte[96_000 * 4]);
		Path replay = replay(dir, 96_000, i -> (int) (10_000 * Math.sin(2 * Math.PI * 440 * i / 48_000.0)));
		List<Clip> clips = List.of(new Clip(0, 40));
		Path mic = dir.resolve("mic.pcm");
		ExportFinisher.writeCommentary(replay, ExportFinisher.spans(clips, 0, 48000, samples(clips, 48000, 30)), mic);
		Path output = dir.resolve("out.mp4");
		FfmpegProcess.mux(ffmpeg, video, new FfmpegProcess.MuxAudio(game, 1.0f, mic, 48000, 1.0f, true), output, dir.resolve("mux.log"), process -> {}, seconds -> {});
		List<String> streams = probe(ffmpeg, output);
		assertEquals(2, streams.size(), streams.toString());
		assertTrue(streams.get(1).contains("channels=2") && streams.get(1).contains("=Game and commentary"), streams.toString());
		short[] mixed = decode(ffmpeg, output, "0:a:0");
		int peak = 0;
		for (short sample : mixed) {
			peak = Math.max(peak, Math.abs(sample));
		}
		assertEquals(10_000, peak, 800);
	}

	private static long samples(List<Clip> clips, int rate, int fps) {
		int ticks = clips.stream().mapToInt(Clip::length).sum();
		long frames = ((long) ticks * fps + 19) / 20;
		return Math.round((double) frames * rate / fps);
	}

	private static Path replay(Path dir, int samples, IntUnaryOperator sample) throws IOException {
		Path wav = dir.resolve("mic.wav");
		ByteBuffer pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < samples; i++) {
			pcm.putShort((short) sample.applyAsInt(i));
		}
		try (WavWriter writer = new WavWriter(wav)) {
			writer.write(pcm.array(), pcm.capacity());
		}
		byte[] data = Files.readAllBytes(wav);
		CRC32 crc = new CRC32();
		crc.update(data);
		ZipEntry entry = new ZipEntry(MicRecorder.ENTRY);
		entry.setMethod(ZipEntry.STORED);
		entry.setSize(data.length);
		entry.setCompressedSize(data.length);
		entry.setCrc(crc.getValue());
		Path replay = dir.resolve("test.momenton");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(replay))) {
			zip.putNextEntry(entry);
			zip.write(data);
			zip.closeEntry();
		}
		return replay;
	}

	private static Path video(String ffmpeg, Path dir) throws IOException {
		Path video = dir.resolve("video.mp4");
		try (FfmpegProcess process = FfmpegProcess.start(ffmpeg, EncodingPreset.MP4_DEFAULT.arguments(), 16, 16, 30, 0, video, dir.resolve("export.log"))) {
			ByteBuffer frame = ByteBuffer.allocate(16 * 16 * 4);
			for (int i = 0; i < 60; i++) {
				process.writeFrame(frame);
			}
			process.finish();
		}
		return video;
	}

	private static List<String> probe(String ffmpeg, Path file) throws IOException, InterruptedException {
		return run(List.of(ffprobe(ffmpeg), "-v", "error", "-show_entries", "stream=codec_type,channels:stream_tags=title,handler_name:stream_disposition=default",
				"-of", "compact", file.toString())).lines().filter(line -> line.startsWith("stream|")).toList();
	}

	private static double duration(String ffmpeg, Path file) throws IOException, InterruptedException {
		return Double.parseDouble(run(List.of(ffprobe(ffmpeg), "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", file.toString())).trim());
	}

	private static short[] decode(String ffmpeg, Path file, String stream) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(ffmpeg, "-v", "error", "-nostdin", "-i", file.toString(), "-map", stream, "-f", "s16le", "-ar", "48000", "-")
				.redirectError(ProcessBuilder.Redirect.DISCARD).start();
		byte[] bytes = process.getInputStream().readAllBytes();
		assertEquals(0, process.waitFor());
		return shorts(bytes);
	}

	private static String run(List<String> command) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes());
		assertEquals(0, process.waitFor(), output);
		return output;
	}

	private static short[] shorts(byte[] bytes) {
		ShortBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
		short[] result = new short[buffer.remaining()];
		buffer.get(result);
		return result;
	}

	private static String ffprobe(String ffmpeg) {
		Path path = Path.of(ffmpeg);
		return path.resolveSibling(path.getFileName().toString().replace("ffmpeg", "ffprobe")).toString();
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
