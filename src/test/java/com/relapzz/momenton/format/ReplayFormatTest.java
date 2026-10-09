package com.relapzz.momenton.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayFormatTest {
	@Test
	void varintsRoundTrip() throws IOException {
		int[] values = {0, 1, 127, 128, 300, 65535, 1 << 20, Integer.MAX_VALUE};
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int v : values) ActionCodec.writeVarInt(out, v);
		InputStream in = new ByteArrayInputStream(out.toByteArray());
		for (int v : values) assertEquals(v, ActionCodec.readVarInt(in));
		assertEquals(-1, in.read());
	}

	@Test
	void actionsRoundTrip() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ActionCodec.write(out, ActionCodec.PACKET, 0, "alpha".getBytes(StandardCharsets.UTF_8), 5);
		ActionCodec.write(out, ActionCodec.ENTITY_MOVE, 5, new byte[]{1, 2, 3}, 3);
		ActionCodec.write(out, ActionCodec.LOCAL_PLAYER, 0, new byte[0], 0);
		ActionCodec.write(out, ActionCodec.DIMENSION_CHANGE, 495, "minecraft:the_nether".getBytes(StandardCharsets.UTF_8), 20);

		ActionCodec.Reader reader = new ActionCodec.Reader(new ByteArrayInputStream(out.toByteArray()), 0);
		List<ActionCodec.Action> read = new ArrayList<>();
		for (ActionCodec.Action a = reader.next(); a != null; a = reader.next()) read.add(a);

		assertEquals(4, read.size());
		assertEquals(List.of(0, 5, 5, 500), read.stream().map(ActionCodec.Action::tick).toList());
		assertEquals(ActionCodec.PACKET, read.get(0).type());
		assertEquals(ActionCodec.DIMENSION_CHANGE, read.get(3).type());
		assertEquals("alpha", new String(read.get(0).payload(), StandardCharsets.UTF_8));
		assertArrayEquals(new byte[]{1, 2, 3}, read.get(1).payload());
		assertEquals(0, read.get(2).payload().length);
		assertEquals("minecraft:the_nether", new String(read.get(3).payload(), StandardCharsets.UTF_8));
		assertNull(reader.next());
	}

	@Test
	void containerRollsChunksAndKeepsMetadata(@TempDir Path root) throws IOException {
		Path staging = root.resolve(".staging");
		Path output = root.resolve("out.momenton");

		ReplayWriter writer = new ReplayWriter(staging, 0);
		int frames = 0;
		ByteBuffer block = ByteBuffer.allocate(56 * 4).order(ByteOrder.LITTLE_ENDIAN);

		for (int tick = 0; tick < 1200; tick++) {
			writer.rollIfNeeded(tick);
			writer.write(tick, ActionCodec.PACKET, ("tick" + tick).getBytes(StandardCharsets.UTF_8));
			writer.write(tick, ActionCodec.UI_STATE, new byte[]{(byte) (tick % 9)});
			for (int f = 0; f < 3; f++) {
				block.putInt(tick).putFloat(f / 3f).putDouble(1).putDouble(2).putDouble(3)
						.putFloat(0).putFloat(0).putFloat(70).putFloat(0).putFloat(0).putInt(0);
				frames++;
				if (!block.hasRemaining()) {
					writer.writeFrames(block);
					block.clear();
				}
			}
		}
		if (block.position() > 0) writer.writeFrames(block);

		writer.write(5, ActionCodec.PACKET, "stale".getBytes(StandardCharsets.UTF_8));

		long frameBytes = writer.frameBytes();
		int total = frames;
		writer.finish(1200, chunks -> new ReplayMetadata(ReplayMetadata.FORMAT_VERSION, "1.21.11", "1.0.0",
				System.currentTimeMillis(), 1200, total, ReplayWriter.CHUNK_TICKS, "Test World", "minecraft:overworld",
				chunks, List.of(), 1920, 1080, 3), output);

		assertFalse(Files.exists(staging));
		assertEquals(56L * frames, frameBytes);

		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(output))) {
			for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry())
				entries.put(e.getName(), zip.readAllBytes());
		}

		assertEquals(Set.of("metadata.json", "chunk_0000.bin", "chunk_0001.bin", "chunk_0002.bin",
				"frames.bin", "ui_0000.bin", "ui_0001.bin", "ui_0002.bin"), entries.keySet());
		assertEquals(56 * frames, entries.get("frames.bin").length);
		entries.forEach((name, bytes) -> {
			if (name.startsWith("chunk_")) assertTrue(bytes.length > 0, name + " is empty");
		});

		ReplayMetadata meta = ReplayMetadata.fromJson(new String(entries.get("metadata.json"), StandardCharsets.UTF_8));
		assertEquals(1200, meta.durationTicks());
		assertEquals(frames, meta.framesRendered());
		assertEquals(500, meta.chunkTicks());
		assertEquals("minecraft:overworld", meta.initialDimension());
		assertEquals(3, meta.chunks().size());
		int[][] ranges = {{0, 500}, {500, 1000}, {1000, 1200}};
		for (int i = 0; i < 3; i++) {
			assertEquals(ranges[i][0], meta.chunks().get(i).startTick());
			assertEquals(ranges[i][1], meta.chunks().get(i).endTick());
		}

		ActionCodec.Reader reader = new ActionCodec.Reader(new ByteArrayInputStream(entries.get("chunk_0001.bin")), meta.chunks().get(1).startTick());
		List<Integer> ticks = new ArrayList<>();
		for (ActionCodec.Action a = reader.next(); a != null; a = reader.next()) ticks.add(a.tick());
		assertEquals(500, ticks.size());
		assertEquals(500, ticks.getFirst());
		assertEquals(999, ticks.getLast());

		ActionCodec.Reader tail = new ActionCodec.Reader(new ByteArrayInputStream(entries.get("chunk_0002.bin")), meta.chunks().get(2).startTick());
		ActionCodec.Action last = null;
		for (ActionCodec.Action a = tail.next(); a != null; a = tail.next()) last = a;
		assertEquals(1199, last.tick());
		assertEquals("stale", new String(last.payload(), StandardCharsets.UTF_8));
	}
}
