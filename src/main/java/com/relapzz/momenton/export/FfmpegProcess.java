package com.relapzz.momenton.export;

import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.audio.LoopbackRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class FfmpegProcess implements AutoCloseable {
	private static final int QUEUED_FRAMES = 3;
	private static final int TAIL_LINES = 50;
	private static final int SEARCH_DEPTH = 4;
	private static final Set<String> SKIPPED = Set.of("saves", "momenton", "logs", "assets", "libraries", "versions",
			"screenshots", "crash-reports", "resourcepacks", "shaderpacks");
	private static final byte[] END = new byte[0];

	private final Process process;
	private final int frameBytes;
	private final BufferedWriter logFile;
	private final StringBuilder log = new StringBuilder();
	private final BlockingQueue<byte[]> free = new ArrayBlockingQueue<>(QUEUED_FRAMES);
	private final BlockingQueue<byte[]> queued = new ArrayBlockingQueue<>(QUEUED_FRAMES + 1);
	private final Thread writer;
	private final Thread reader;
	private volatile IOException failure;

	private FfmpegProcess(Process process, int frameBytes, BufferedWriter logFile, String header) {
		this.process = process;
		this.frameBytes = frameBytes;
		this.logFile = logFile;
		log.append(header);
		for (int i = 0; i < QUEUED_FRAMES; i++) {
			free.add(new byte[frameBytes]);
		}
		writer = Thread.ofPlatform().name("Momenton ffmpeg writer").daemon().start(this::writeLoop);
		reader = Thread.ofPlatform().name("Momenton ffmpeg log").daemon().start(this::readLoop);
	}

	public static String defaultCommand() {
		String property = System.getProperty("momenton.ffmpeg");
		if (property != null) {
			return property;
		}
		Optional<Path> found = switch (Util.getOperatingSystem()) {
			case WINDOWS -> findInGameDir(FabricLoader.getInstance().getGameDir());
			case OSX -> findOnMac();
			default -> Optional.empty();
		};
		return found.map(Path::toString).orElse("ffmpeg");
	}

	private static Optional<Path> findInGameDir(Path gameDir) {
		Path unpacked = gameDir.resolve("ffmpeg").resolve("bin").resolve("ffmpeg.exe");
		if (Files.isRegularFile(unpacked)) {
			return Optional.of(unpacked);
		}
		Path[] found = new Path[1];
		try {
			Files.walkFileTree(gameDir, Set.of(), SEARCH_DEPTH, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					return SKIPPED.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (attrs.isRegularFile() && file.getFileName().toString().equalsIgnoreCase("ffmpeg.exe")) {
						found[0] = file;
						return FileVisitResult.TERMINATE;
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException e) {
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			Momenton.LOGGER.debug("Could not search {} for ffmpeg.exe", gameDir, e);
		}
		return Optional.ofNullable(found[0]).map(Path::toAbsolutePath);
	}

	private static Optional<Path> findOnMac() {
		Stream<Path> fixed = Stream.of(Path.of("/usr/local/bin/ffmpeg"), Path.of("/usr/bin/ffmpeg"));
		Stream<Path> homebrew = Stream.of("/usr/local", "/opt/homebrew").flatMap(prefix -> cellar(Path.of(prefix, "Cellar", "ffmpeg")));
		return Stream.concat(fixed, homebrew).filter(Files::isRegularFile).findFirst();
	}

	private static Stream<Path> cellar(Path folder) {
		Comparator<int[]> byNumbers = Arrays::compare;
		try (Stream<Path> versions = Files.list(folder)) {
			return versions.sorted(Comparator.comparing(FfmpegProcess::version, byNumbers).reversed())
					.map(version -> version.resolve("bin").resolve("ffmpeg"))
					.toList()
					.stream();
		} catch (IOException e) {
			return Stream.empty();
		}
	}

	private static int[] version(Path folder) {
		return Arrays.stream(folder.getFileName().toString().split("\\D+"))
				.filter(part -> !part.isEmpty())
				.mapToInt(Integer::parseInt)
				.toArray();
	}

	public static FfmpegProcess start(String command, String args, int width, int height, int fps, int bitrate, Path output) throws IOException {
		return start(command, args, width, height, fps, bitrate, output, logPath());
	}

	static FfmpegProcess start(String command, String args, int width, int height, int fps, int bitrate, Path output, Path logPath) throws IOException {
		Path folder = output.toAbsolutePath().getParent();
		List<String> commandLine = new ArrayList<>();
		commandLine.add(command);
		try {
			commandLine.addAll(split(substitute(args, width, height, fps, bitrate, output.getFileName().toString())));
		} catch (IllegalArgumentException e) {
			throw new StartupException(e.getMessage());
		}
		Files.createDirectories(folder);
		Files.createDirectories(logPath.getParent());
		String header = header(commandLine);
		BufferedWriter logFile = Files.newBufferedWriter(logPath);
		logFile.write(header);
		logFile.flush();
		Process process;
		try {
			process = new ProcessBuilder(commandLine).directory(folder.toFile()).redirectErrorStream(true).start();
		} catch (IOException e) {
			logFile.close();
			throw new NotFoundException(command, e);
		}
		return new FfmpegProcess(process, width * height * 4, logFile, header);
	}

	private static String header(List<String> commandLine) {
		return "> " + String.join(" ", commandLine) + "\n";
	}

	static String substitute(String args, int width, int height, int fps, int bitrate, String fileName) {
		return args.replace("%WIDTH%", String.valueOf(width))
				.replace("%HEIGHT%", String.valueOf(height))
				.replace("%FPS%", String.valueOf(fps))
				.replace("%FILENAME%", fileName)
				.replace("%BITRATE%", String.valueOf(bitrate))
				.replace("%FILTERS%", "");
	}

	public static List<String> split(String args) {
		List<String> result = new ArrayList<>();
		StringBuilder token = new StringBuilder();
		boolean quoted = false;
		boolean pending = false;
		for (char c : args.toCharArray()) {
			if (c == '"') {
				quoted = !quoted;
				pending = true;
			} else if (!quoted && Character.isWhitespace(c)) {
				if (pending) {
					result.add(token.toString());
					token.setLength(0);
					pending = false;
				}
			} else {
				token.append(c);
				pending = true;
			}
		}
		if (quoted) {
			throw new IllegalArgumentException("Unbalanced quotes in the ffmpeg arguments: " + args);
		}
		if (pending) {
			result.add(token.toString());
		}
		return result;
	}

	public static void mux(String command, Path video, Path pcm, Path output) throws IOException {
		List<String> commandLine = List.of(command, "-nostdin", "-y", "-i", video.toString(),
				"-f", "s16le", "-ar", Integer.toString(LoopbackRenderer.SAMPLE_RATE), "-ac", "2", "-i", pcm.toString(),
				"-c:v", "copy", "-c:a", audioCodec(output), "-b:a", "192k", "-shortest", output.toString());
		Process process = new ProcessBuilder(commandLine).redirectErrorStream(true).start();
		String text = process.inputReader().lines().collect(Collectors.joining("\n", header(commandLine), "\n"));
		Files.writeString(logPath(), text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		try {
			int exit = process.waitFor();
			if (exit != 0) {
				throw new FfmpegException("ffmpeg could not add the audio, exit code " + exit, text);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException();
		}
	}

	static String audioCodec(Path output) {
		return output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".webm") ? "libopus" : "aac";
	}

	public static Path logPath() {
		return FabricLoader.getInstance().getGameDir().resolve("momenton").resolve("export.log");
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
			if (!process.waitFor(30, TimeUnit.SECONDS)) {
				process.destroy();
			}
			if (failure != null || process.waitFor() != 0) {
				throw exited();
			}
			reader.join();
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
		String text = log.toString();
		if (text.lines().noneMatch(line -> line.startsWith("Output #0"))) {
			return new StartupException(text);
		}
		return new FfmpegException("ffmpeg exited with code " + exit, text);
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
		try (BufferedReader in = process.inputReader(); logFile) {
			for (String line = in.readLine(); line != null; line = in.readLine()) {
				log.append(line).append('\n');
				logFile.write(line + "\n");
				logFile.flush();
			}
		} catch (IOException e) {
			Momenton.LOGGER.warn("Could not write the ffmpeg log", e);
		}
	}

	public static class FfmpegException extends IOException {
		private final String log;

		FfmpegException(String message, String log) {
			super(message + ":\n" + tail(log));
			this.log = log;
		}

		public String log() {
			return log;
		}

		private static String tail(String log) {
			List<String> lines = log.lines().toList();
			return String.join("\n", lines.subList(Math.max(0, lines.size() - TAIL_LINES), lines.size()));
		}
	}

	public static final class StartupException extends FfmpegException {
		StartupException(String log) {
			super("ffmpeg rejected the arguments", log);
		}
	}

	public static final class NotFoundException extends IOException {
		NotFoundException(String command, IOException cause) {
			super("ffmpeg could not be started from " + command, cause);
		}
	}
}
