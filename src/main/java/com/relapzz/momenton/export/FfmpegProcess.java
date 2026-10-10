package com.relapzz.momenton.export;

import com.relapzz.momenton.audio.LoopbackRenderer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Stream;

public final class FfmpegProcess implements AutoCloseable {
	private static final int QUEUED_FRAMES = 3;
	private static final int STDERR_LINES = 50;
	private static final byte[] END = new byte[0];

	private final Process process;
	private final int frameBytes;
	private final BlockingQueue<byte[]> free = new ArrayBlockingQueue<>(QUEUED_FRAMES);
	private final BlockingQueue<byte[]> queued = new ArrayBlockingQueue<>(QUEUED_FRAMES + 1);
	private final Deque<String> stderr = new ArrayDeque<>();
	private final Thread writer;
	private final Thread reader;
	private volatile IOException failure;

	private FfmpegProcess(Process process, int frameBytes) {
		this.process = process;
		this.frameBytes = frameBytes;
		for (int i = 0; i < QUEUED_FRAMES; i++) {
			free.add(new byte[frameBytes]);
		}
		writer = Thread.ofPlatform().name("Momenton ffmpeg writer").daemon().start(this::writeLoop);
		reader = Thread.ofPlatform().name("Momenton ffmpeg stderr").daemon().start(this::readLoop);
	}

	public static Optional<Path> locate() {
		String name = File.separatorChar == '\\' ? "ffmpeg.exe" : "ffmpeg";
		Stream<Path> onPath = Stream.of(System.getenv().getOrDefault("PATH", "").split(File.pathSeparator))
			.map(dir -> dir.replace("\"", ""))
			.filter(dir -> !dir.isBlank())
			.map(dir -> Path.of(dir, name));
		return Stream.concat(Stream.ofNullable(System.getProperty("momenton.ffmpeg")).map(Path::of), onPath)
			.filter(path -> Files.isRegularFile(path) && Files.isExecutable(path))
			.findFirst();
	}

	public static final String DEFAULT_ARGS = "-y -f rawvideo -pix_fmt rgba -s %WIDTH%x%HEIGHT% -r %FPS% -i - -an -c:v libx264 -preset medium -crf 18 -pix_fmt yuv420p \"%FILENAME%\"";

	public static FfmpegProcess start(String command, String args, int width, int height, int fps, int bitrate, Path output) throws IOException {
		return start(Path.of(command), width, height, fps, output);
	}

	private static FfmpegProcess start(Path ffmpeg, int width, int height, int fps, Path output) throws IOException {
		Files.createDirectories(output.toAbsolutePath().getParent());
		Process process = new ProcessBuilder(command(ffmpeg, width, height, fps, output))
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.start();
		return new FfmpegProcess(process, width * height * 4);
	}

	static List<String> command(Path ffmpeg, int width, int height, int fps, Path output) {
		return List.of(ffmpeg.toString(), "-y",
			"-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-r", Integer.toString(fps), "-i", "pipe:0",
			"-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", output.toString());
	}

	public static void mux(String command, Path video, Path pcm, Path output) throws IOException {
		Path ffmpeg = Path.of(command);
		Process process = new ProcessBuilder(ffmpeg.toString(), "-nostdin", "-y", "-i", video.toString(),
			"-f", "s16le", "-ar", Integer.toString(LoopbackRenderer.SAMPLE_RATE), "-ac", "2", "-i", pcm.toString(),
			"-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest", output.toString())
			.redirectErrorStream(true)
			.start();
		List<String> log = process.inputReader().lines().toList();
		try {
			int exit = process.waitFor();
			if (exit != 0) {
				throw new IOException("ffmpeg mux exited with code " + exit + ":\n"
					+ String.join("\n", log.subList(Math.max(0, log.size() - STDERR_LINES), log.size())));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException();
		}
	}

	public void writeFrame(ByteBuffer rgba) throws IOException {
		if (rgba.remaining() != frameBytes) {
			throw new IllegalArgumentException("Expected " + frameBytes + " bytes, got " + rgba.remaining());
		}
		try {
			if (failure != null) {
				throw exited();
			}
			byte[] frame = free.take();
			rgba.get(rgba.position(), frame);
			queued.put(frame);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException();
		}
	}

	public void finish() throws IOException {
		try {
			queued.put(END);
			writer.join();
			if (failure != null || process.waitFor() != 0) {
				throw exited();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException();
		}
	}

	@Override
	public void close() {
		writer.interrupt();
		process.destroyForcibly().onExit().join();
	}

	private IOException exited() throws InterruptedException {
		int exit = process.waitFor();
		reader.join();
		return new IOException("ffmpeg exited with code " + exit + ":\n" + String.join("\n", stderr), failure);
	}

	private void writeLoop() {
		OutputStream out = process.getOutputStream();
		try {
			for (byte[] frame = queued.take(); frame != END; frame = queued.take()) {
				write(out, frame);
				free.add(frame);
			}
			out.close();
		} catch (IOException e) {
			fail(e);
		} catch (InterruptedException ignored) {
		}
	}

	private void write(OutputStream out, byte[] frame) {
		if (failure == null) {
			try {
				out.write(frame);
			} catch (IOException e) {
				fail(e);
			}
		}
	}

	private void fail(IOException e) {
		if (failure == null) {
			failure = e;
		}
	}

	private void readLoop() {
		try (BufferedReader in = process.errorReader()) {
			for (String line = in.readLine(); line != null; line = in.readLine()) {
				if (stderr.size() == STDERR_LINES) {
					stderr.removeFirst();
				}
				stderr.addLast(line);
			}
		} catch (IOException ignored) {
		}
	}
}
