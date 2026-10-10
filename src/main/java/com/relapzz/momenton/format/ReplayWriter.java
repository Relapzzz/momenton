package com.relapzz.momenton.format;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes the parts of a replay to a staging directory, then zips them into a {@code .momenton} file.
 *
 * <p>A zip cannot be written incrementally here: {@code chunk_NNNN.bin}, {@code ui_NNNN.bin} and
 * {@code frames.bin} all grow at once and {@link ZipOutputStream} only holds one entry open at a
 * time. Staging to loose files instead means a crash leaves every completed chunk on disk, which is
 * what the incremental requirement was actually for.
 *
 * <p>Single-threaded by contract: only the recorder's writer thread may touch an instance.
 */
public final class ReplayWriter {
	public static final int CHUNK_TICKS = 500;

	private final Path staging;
	private final OutputStream frameOut;
	private final List<ReplayMetadata.ChunkEntry> chunks = new ArrayList<>();

	private OutputStream chunkOut;
	private OutputStream uiOut;
	private int chunkIndex;
	private int chunkStartTick;
	private int chunkLastTick;
	private int uiLastTick;
	private long frameBytes;

	public ReplayWriter(Path staging, int startTick) throws IOException {
		this.staging = staging;
		Files.createDirectories(staging);
		this.frameOut = open("frames.bin");
		openChunk(startTick);
	}

	/**
	 * Routes an action to the tick-rate UI track or to the packet chunk, whichever it belongs to.
	 *
	 * <p>Deltas are clamped at zero. A packet captured on the netty thread can be queued a moment
	 * after the client thread has moved on, so an action occasionally arrives one tick stale; filing
	 * it under the current tick is invisible in playback, whereas a negative delta would corrupt the
	 * stream.
	 */
	public void write(int tick, int type, byte[] payload) throws IOException {
		if (type == ActionCodec.UI_STATE) {
			ActionCodec.write(uiOut, type, Math.max(0, tick - uiLastTick), payload, payload.length);
			uiLastTick = Math.max(uiLastTick, tick);
		} else {
			ActionCodec.write(chunkOut, type, Math.max(0, tick - chunkLastTick), payload, payload.length);
			chunkLastTick = Math.max(chunkLastTick, tick);
		}
	}

	/** Writes a complete part in one go. {@link #finish} picks it up with everything else in staging. */
	public void writePart(String name, byte[] content) throws IOException {
		Files.write(staging.resolve(name), content);
	}

	/** Appends a block of fixed-stride frame records; {@code block} is written from 0 to its position. */
	public void writeFrames(ByteBuffer block) throws IOException {
		frameOut.write(block.array(), 0, block.position());
		frameBytes += block.position();
	}

	/** Closes the current chunk and starts the next once {@link #CHUNK_TICKS} have elapsed. */
	public void rollIfNeeded(int tick) throws IOException {
		if (tick - chunkStartTick < CHUNK_TICKS) {
			return;
		}
		closeChunk(tick);
		openChunk(tick);
	}

	public long frameBytes() {
		return frameBytes;
	}

	/** Closes every part, writes the metadata and zips the staging directory into {@code output}. */
	public void finish(int endTick, MetadataFactory metadataFactory, Path output) throws IOException {
		closeChunk(endTick);
		frameOut.close();

		Files.createDirectories(output.getParent());
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
			zip.putNextEntry(new ZipEntry("metadata.json"));
			zip.write(metadataFactory.create(chunks).toJson().getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();

			try (Stream<Path> parts = Files.walk(staging)) {
				for (Path part : parts.filter(Files::isRegularFile).sorted().toList()) {
					String name = staging.relativize(part).toString().replace(File.separatorChar, '/');
					zip.putNextEntry(name.endsWith(".wav") ? stored(name, part) : new ZipEntry(name));
					Files.copy(part, zip);
					zip.closeEntry();
				}
			}
		}
		deleteStaging();
	}

	private static ZipEntry stored(String name, Path part) throws IOException {
		CRC32 crc = new CRC32();
		try (InputStream in = Files.newInputStream(part)) {
			byte[] buffer = new byte[1 << 16];
			for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
				crc.update(buffer, 0, read);
			}
		}
		ZipEntry entry = new ZipEntry(name);
		entry.setMethod(ZipEntry.STORED);
		entry.setSize(Files.size(part));
		entry.setCrc(crc.getValue());
		return entry;
	}

	/** Supplies the metadata once the chunk index is final. */
	public interface MetadataFactory {
		ReplayMetadata create(List<ReplayMetadata.ChunkEntry> chunks);
	}

	private void openChunk(int startTick) throws IOException {
		chunkStartTick = startTick;
		chunkLastTick = startTick;
		uiLastTick = startTick;
		chunkOut = open(String.format("chunk_%04d.bin", chunkIndex));
		uiOut = open(String.format("ui_%04d.bin", chunkIndex));
	}

	private void closeChunk(int endTick) throws IOException {
		chunkOut.close();
		uiOut.close();
		chunks.add(new ReplayMetadata.ChunkEntry(chunkIndex, chunkStartTick, endTick));
		chunkIndex++;
	}

	private OutputStream open(String name) throws IOException {
		return new BufferedOutputStream(Files.newOutputStream(staging.resolve(name)), 1 << 16);
	}

	private void deleteStaging() throws IOException {
		try (Stream<Path> tree = Files.walk(staging)) {
			for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}
}
