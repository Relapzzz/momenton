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
import java.io.UncheckedIOException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class FfmpegProcess implements AutoCloseable {
	private static final int QUEUED_FRAMES = 3;
	private static final int COPY_THREADS = 4;
	private static final int DELETE_ATTEMPTS = 100;
	private static final long DELETE_RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
	private static final int TAIL_LINES = 50;
	private static final int SEARCH_DEPTH = 4;
	private static final Set<String> SKIPPED = Set.of("saves", "momenton", "logs", "assets", "libraries", "versions",
			"screenshots", "crash-reports", "resourcepacks", "shaderpacks");
	private static final Frame END = new Frame(new byte[0], CompletableFuture.completedFuture(null));
	private static final Pattern FRAME = Pattern.compile("frame=\\s*(\\d+)");
	private static final Pattern TIME = Pattern.compile("time=(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
	private static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(60);

	private final Process process;
	private final FrameFormat format;
	private final int frameBytes;
	private final int stride;
	private final int rows;
	private final BufferedWriter logFile;
	private final StringBuilder log = new StringBuilder();
	private final BlockingQueue<byte[]> free = new ArrayBlockingQueue<>(QUEUED_FRAMES);
	private final BlockingQueue<Frame> queued = new ArrayBlockingQueue<>(QUEUED_FRAMES + 1);
	private final ExecutorService copiers = Executors.newFixedThreadPool(COPY_THREADS, Thread.ofPlatform().name("Momenton frame copy ", 0).daemon().factory());
	private final Thread writer;
	private final Thread reader;
	private volatile IOException failure;
	private volatile int encoded;
	private volatile long progressed = System.nanoTime();
	private long waited;

	private FfmpegProcess(Process process, FrameFormat format, int width, int height, BufferedWriter logFile, String header) {
		this.process = process;
		this.format = format;
		this.frameBytes = format.bytes(width, height);
		this.stride = format.stride(width);
		this.rows = format.rows(height);
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
			case WINDOWS -> findInGameDir(FabricLoader.getInstance().getGameDir()).or(FfmpegProcess::findWinget);
			case OSX -> findOnMac();
			default -> Optional.empty();
		};
		return found.map(Path::toString).orElse("ffmpeg");
	}

	private static Optional<Path> findWinget() {
		String local = System.getenv("LOCALAPPDATA");
		if (local == null) {
			return Optional.empty();
		}
		Path packages = Path.of(local, "Microsoft", "WinGet", "Packages");
		if (!Files.isDirectory(packages)) {
			return Optional.empty();
		}
		try (Stream<Path> found = Files.find(packages, 4,
				(path, attrs) -> attrs.isRegularFile() && path.getFileName().toString().equalsIgnoreCase("ffmpeg.exe"))) {
			return found.findFirst();
		} catch (IOException | UncheckedIOException e) {
			return Optional.empty();
		}
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

	static FfmpegProcess start(String command, String args, FrameFormat format, int width, int height, int fps, int bitrate, Path output) throws IOException {
		return start(command, args, format, width, height, fps, bitrate, output, logPath());
	}

	static FfmpegProcess start(String command, String args, FrameFormat format, int width, int height, int fps, int bitrate, Path output, Path logPath) throws IOException {
		Path folder = output.toAbsolutePath().getParent();
		List<String> commandLine = new ArrayList<>();
		commandLine.add(command);
		try {
			commandLine.addAll(split(substitute(format.expand(args), width, height, fps, bitrate, output.getFileName().toString())));
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
		return new FfmpegProcess(process, format, width, height, logFile, header);
	}

	private static String header(List<String> commandLine) {
		return "> " + String.join(" ", commandLine) + "\n";
	}

	static String substitute(String args, int width, int height, int fps, int bitrate, String fileName) {
		return args.replace("%WIDTH%", String.valueOf(width))
				.replace("%HEIGHT%", String.valueOf(height))
				.replace("%FPS%", String.valueOf(fps))
				.replace("%FILENAME%", fileName)
				.replace("%BITRATE%", String.valueOf(bitrate));
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

	public static void mux(String command, Path video, MuxAudio audio, Path output, Path logPath, Consumer<Process> started, DoubleConsumer seconds) throws IOException {
		List<String> commandLine = muxCommand(command, video, audio, output);
		Process process = new ProcessBuilder(commandLine).redirectErrorStream(true).start();
		started.accept(process);
		StringBuilder text = new StringBuilder(header(commandLine));
		try (BufferedReader in = process.inputReader()) {
			for (String line = in.readLine(); line != null; line = in.readLine()) {
				text.append(line).append('\n');
				Matcher time = TIME.matcher(line);
				if (time.find()) {
					seconds.accept(Integer.parseInt(time.group(1)) * 3600 + Integer.parseInt(time.group(2)) * 60 + Double.parseDouble(time.group(3)));
				}
			}
		}
		Files.writeString(logPath, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		try {
			int exit = process.waitFor();
			if (exit != 0) {
				throw new FfmpegException("ffmpeg could not add the audio, exit code " + exit, text.toString());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException();
		}
	}

	static List<String> muxCommand(String command, Path video, MuxAudio audio, Path output) {
		List<String> line = new ArrayList<>(List.of(command, "-nostdin", "-y", "-i", video.toString()));
		List<String> graph = new ArrayList<>();
		List<String> titles = new ArrayList<>();
		if (audio.gameVolume() > 0) {
			line.addAll(List.of("-f", "s16le", "-ar", Integer.toString(LoopbackRenderer.SAMPLE_RATE), "-ac", "2", "-i", audio.game().toString()));
			graph.add("[" + (graph.size() + 1) + ":a]volume=" + audio.gameVolume() + "[a" + graph.size() + "]");
			titles.add("Game");
		}
		if (audio.mic() != null && audio.micVolume() > 0) {
			line.addAll(List.of("-f", "s16le", "-ar", Integer.toString(audio.micRate()), "-ac", "1", "-i", audio.mic().toString()));
			graph.add("[" + (graph.size() + 1) + ":a]volume=" + audio.micVolume() + "[a" + graph.size() + "]");
			titles.add("Commentary");
		}
		List<String> outputs = new ArrayList<>();
		for (int i = 0; i < graph.size(); i++) {
			outputs.add("[a" + i + "]");
		}
		if (audio.mix() && outputs.size() == 2) {
			graph.add("[a1]pan=stereo|c0=c0|c1=c0[voice]");
			graph.add("[a0][voice]amix=inputs=2:duration=first:normalize=0[mix]");
			outputs = List.of("[mix]");
			titles = List.of("Game and commentary");
		}
		if (!graph.isEmpty()) {
			line.addAll(List.of("-filter_complex", String.join(";", graph)));
		}
		line.addAll(List.of("-map", "0:v"));
		for (String stream : outputs) {
			line.addAll(List.of("-map", stream));
		}
		line.addAll(List.of("-c:v", "copy"));
		if (!outputs.isEmpty()) {
			line.addAll(List.of("-c:a", audioCodec(output), "-b:a", "192k"));
		}
		for (int i = 0; i < titles.size(); i++) {
			line.addAll(List.of("-metadata:s:a:" + i, "title=" + titles.get(i), "-metadata:s:a:" + i, "handler_name=" + titles.get(i),
					"-disposition:a:" + i, i == 0 ? "default" : "0"));
		}
		line.addAll(List.of("-shortest", output.toString()));
		return line;
	}

	public record MuxAudio(Path game, float gameVolume, Path mic, int micRate, float micVolume, boolean mix) {
	}

	static String audioCodec(Path output) {
		return output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".webm") ? "libopus" : "aac";
	}

	public static Path logPath() {
		return FabricLoader.getInstance().getGameDir().resolve("momenton").resolve("export.log");
	}

	public CompletableFuture<Void> writeFrame(ByteBuffer source) throws IOException {
		if (source.remaining() != frameBytes) {
			throw new IllegalArgumentException("Expected " + frameBytes + " bytes, got " + source.remaining());
		}
		try {
			if (failure != null) {
				throw exited();
			}
			long start = System.nanoTime();
			byte[] frame = free.take();
			waited += System.nanoTime() - start;
			CompletableFuture<?>[] parts = new CompletableFuture<?>[COPY_THREADS];
			for (int i = 0; i < COPY_THREADS; i++) {
				int from = rows * i / COPY_THREADS;
				int to = rows * (i + 1) / COPY_THREADS;
				parts[i] = CompletableFuture.runAsync(() -> copy(source, frame, from, to), copiers);
			}
			CompletableFuture<Void> copied = CompletableFuture.allOf(parts);
			queued.put(new Frame(frame, copied));
			return copied;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException();
		}
	}

	private void copy(ByteBuffer source, byte[] frame, int from, int to) {
		int base = source.position();
		if (format == FrameFormat.NV12) {
			source.get(base + from * stride, frame, from * stride, (to - from) * stride);
			return;
		}
		for (int y = from; y < to; y++) {
			source.get(base + (rows - 1 - y) * stride, frame, y * stride, stride);
		}
	}

	public long waitedNanos() {
		return waited;
	}

	public int encodedFrames() {
		return encoded;
	}

	public void finish() throws IOException {
		copiers.shutdown();
		try {
			progressed = System.nanoTime();
			queued.put(END);
			while (!process.waitFor(1, TimeUnit.SECONDS)) {
				if (System.nanoTime() - progressed > STALL_NANOS) {
					process.destroyForcibly().waitFor();
					reader.join();
					throw new FfmpegException("ffmpeg made no progress for " + TimeUnit.NANOSECONDS.toSeconds(STALL_NANOS) + " s", log.toString());
				}
			}
			writer.join();
			if (failure != null || process.exitValue() != 0) {
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
		copiers.shutdown();
		process.destroyForcibly();
	}

	public void discard(Path... files) {
		close();
		Thread.ofPlatform().name("Momenton export cleanup").daemon().start(() -> {
			try {
				process.waitFor();
			} catch (InterruptedException e) {
				return;
			}
			for (Path file : files) {
				deleteReleased(file);
			}
		});
	}

	static void deleteReleased(Path path) {
		IOException error = null;
		for (int attempt = 0; attempt < DELETE_ATTEMPTS; attempt++) {
			try {
				Files.deleteIfExists(path);
				return;
			} catch (IOException e) {
				error = e;
				LockSupport.parkNanos(DELETE_RETRY_NANOS);
			}
		}
		Momenton.LOGGER.warn("Could not delete {}", path, error);
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
			for (Frame frame = queued.take(); frame != END; frame = queued.take()) {
				write(out, frame);
				free.add(frame.bytes());
			}
			out.close();
		} catch (IOException e) {
			fail(e);
		} catch (InterruptedException ignored) {
		}
	}

	private void write(OutputStream out, Frame frame) throws InterruptedException {
		try {
			frame.copied().get();
			if (failure == null) {
				out.write(frame.bytes());
			}
		} catch (ExecutionException e) {
			fail(new IOException("Could not copy a frame", e.getCause()));
		} catch (IOException e) {
			fail(e);
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
				Matcher frame = FRAME.matcher(line);
				int count = frame.find() ? Integer.parseInt(frame.group(1)) : 0;
				if (count > encoded) {
					encoded = count;
					progressed = System.nanoTime();
				}
				log.append(line).append('\n');
				logFile.write(line + "\n");
				logFile.flush();
			}
		} catch (IOException e) {
			Momenton.LOGGER.warn("Could not write the ffmpeg log", e);
		}
	}

	private record Frame(byte[] bytes, CompletableFuture<?> copied) {
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
