package com.relapzz.momenton.format;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Random access to the parts of a {@code .momenton} file.
 *
 * <p>{@link ZipFile} rather than {@code ZipInputStream}: seeking loads one chunk out of the middle of
 * a replay, which a forward-only stream cannot do.
 */
public final class ReplayReader implements Closeable {
	private final ZipFile zip;
	private final ReplayMetadata metadata;

	public ReplayReader(Path file) throws IOException {
		this.zip = new ZipFile(file.toFile());
		this.metadata = ReplayMetadata.fromJson(new String(read("metadata.json"), StandardCharsets.UTF_8));
	}

	public ReplayMetadata metadata() {
		return metadata;
	}

	/** True for replays made before the setup track existed, which can be inspected but not played. */
	public boolean hasSetup() {
		return zip.getEntry("setup.bin") != null;
	}

	public byte[] setup() throws IOException {
		return read("setup.bin");
	}

	/** The whole frame track. Fixed stride, so it is an array to index rather than a stream to read. */
	public FrameTrack frames() throws IOException {
		return new FrameTrack(read("frames.bin"), metadata.formatVersion());
	}

	/**
	 * The world snapshot taken at the start of chunk {@code index}: a join packet followed by
	 * everything the join's empty world was missing, framed the same way as {@code setup.bin}.
	 */
	public byte[] snapshot(int index) throws IOException {
		return read(String.format("snapshot_%04d.bin", index));
	}

	public boolean hasSnapshot(int index) {
		return zip.getEntry(String.format("snapshot_%04d.bin", index)) != null;
	}

	/** Actions in one chunk, seeded with that chunk's start tick so the deltas resolve. */
	public ActionCodec.Reader chunk(ReplayMetadata.ChunkEntry entry) throws IOException {
		return reader(String.format("chunk_%04d.bin", entry.index()), entry.startTick());
	}

	public ActionCodec.Reader ui(ReplayMetadata.ChunkEntry entry) throws IOException {
		return reader(String.format("ui_%04d.bin", entry.index()), entry.startTick());
	}

	@Override
	public void close() throws IOException {
		zip.close();
	}

	private ActionCodec.Reader reader(String name, int startTick) throws IOException {
		return new ActionCodec.Reader(open(name), startTick);
	}

	private byte[] read(String name) throws IOException {
		try (InputStream in = open(name)) {
			return in.readAllBytes();
		}
	}

	private InputStream open(String name) throws IOException {
		ZipEntry entry = zip.getEntry(name);
		if (entry == null) {
			throw new IOException(name + " is missing from " + zip.getName());
		}
		return zip.getInputStream(entry);
	}
}
