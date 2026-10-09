import com.relapzz.momenton.format.ActionCodec;
import com.relapzz.momenton.format.ReplayMetadata;
import com.relapzz.momenton.format.ReplayWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class Verify {
	static int failures = 0;

	static void check(String what, boolean ok) {
		System.out.println((ok ? "PASS  " : "FAIL  ") + what);
		if (!ok) failures++;
	}

	public static void main(String[] args) throws Exception {
		varints();
		actionRoundTrip();
		container();
		System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILURES");
		if (failures > 0) System.exit(1);
	}

	static void varints() throws Exception {
		int[] values = {0, 1, 127, 128, 300, 65535, 1 << 20, Integer.MAX_VALUE};
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int v : values) ActionCodec.writeVarInt(out, v);
		InputStream in = new ByteArrayInputStream(out.toByteArray());
		boolean ok = true;
		for (int v : values) ok &= ActionCodec.readVarInt(in) == v;
		check("varint round-trip", ok && in.read() == -1);
	}

	static void actionRoundTrip() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		// deltas 0, 5, 0, 495 -> absolute ticks 0, 5, 5, 500
		ActionCodec.write(out, ActionCodec.PACKET, 0, "alpha".getBytes(StandardCharsets.UTF_8), 5);
		ActionCodec.write(out, ActionCodec.ENTITY_MOVE, 5, new byte[]{1, 2, 3}, 3);
		ActionCodec.write(out, ActionCodec.LOCAL_PLAYER, 0, new byte[0], 0);
		ActionCodec.write(out, ActionCodec.DIMENSION_CHANGE, 495, "minecraft:the_nether".getBytes(StandardCharsets.UTF_8), 20);

		ActionCodec.Reader reader = new ActionCodec.Reader(new ByteArrayInputStream(out.toByteArray()), 0);
		List<ActionCodec.Action> read = new ArrayList<>();
		for (ActionCodec.Action a = reader.next(); a != null; a = reader.next()) read.add(a);

		check("four actions read back", read.size() == 4);
		check("tick deltas accumulate to absolute", read.get(0).tick() == 0 && read.get(1).tick() == 5
				&& read.get(2).tick() == 5 && read.get(3).tick() == 500);
		check("types preserved", read.get(0).type() == ActionCodec.PACKET
				&& read.get(3).type() == ActionCodec.DIMENSION_CHANGE);
		check("payloads preserved", new String(read.get(0).payload(), StandardCharsets.UTF_8).equals("alpha")
				&& Arrays.equals(read.get(1).payload(), new byte[]{1, 2, 3})
				&& read.get(2).payload().length == 0
				&& new String(read.get(3).payload(), StandardCharsets.UTF_8).equals("minecraft:the_nether"));
		check("reader stops cleanly at end of stream", reader.next() == null);
	}

	static void container() throws Exception {
		Path root = Files.createTempDirectory("momenton-verify");
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
				if (!block.hasRemaining()) { writer.writeFrames(block); block.clear(); }
			}
		}
		if (block.position() > 0) writer.writeFrames(block);

		// A stale action arriving after the tick has moved on must not corrupt the stream.
		writer.write(5, ActionCodec.PACKET, "stale".getBytes(StandardCharsets.UTF_8));

		long frameBytes = writer.frameBytes();
		int total = frames;
		writer.finish(1200, chunks -> new ReplayMetadata(ReplayMetadata.FORMAT_VERSION, "1.21.11", "1.0.0",
				System.currentTimeMillis(), 1200, total, ReplayWriter.CHUNK_TICKS, "Test World", "minecraft:overworld",
				chunks, List.of()), output);

		check("staging directory removed", !Files.exists(staging));
		check("frames.bin length is 56 * framesRendered", frameBytes == 56L * frames);

		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(output))) {
			for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry())
				entries.put(e.getName(), zip.readAllBytes());
		}

		check("zip holds metadata, 3 chunks, 3 ui tracks and frames.bin",
				entries.keySet().equals(new LinkedHashSet<>(List.of("metadata.json",
						"chunk_0000.bin", "chunk_0001.bin", "chunk_0002.bin",
						"frames.bin", "ui_0000.bin", "ui_0001.bin", "ui_0002.bin"))));
		check("frames.bin entry is 56 * framesRendered bytes", entries.get("frames.bin").length == 56 * frames);
		check("no chunk is empty", entries.entrySet().stream()
				.filter(e -> e.getKey().startsWith("chunk_")).allMatch(e -> e.getValue().length > 0));

		ReplayMetadata meta = ReplayMetadata.fromJson(new String(entries.get("metadata.json"), StandardCharsets.UTF_8));
		check("metadata survives JSON", meta.durationTicks() == 1200 && meta.framesRendered() == frames
				&& meta.chunkTicks() == 500 && meta.initialDimension().equals("minecraft:overworld"));
		check("chunk index covers [0,500) [500,1000) [1000,1200)",
				meta.chunks().size() == 3
						&& meta.chunks().get(0).startTick() == 0 && meta.chunks().get(0).endTick() == 500
						&& meta.chunks().get(1).startTick() == 500 && meta.chunks().get(1).endTick() == 1000
						&& meta.chunks().get(2).startTick() == 1000 && meta.chunks().get(2).endTick() == 1200);

		// Read chunk 1 back: ticks must come out absolute and in range.
		ActionCodec.Reader reader = new ActionCodec.Reader(new ByteArrayInputStream(entries.get("chunk_0001.bin")), meta.chunks().get(1).startTick());
		int count = 0, first = -1, last = -1;
		for (ActionCodec.Action a = reader.next(); a != null; a = reader.next()) {
			if (first < 0) first = a.tick();
			last = a.tick();
			count++;
		}
		System.out.println("      DEBUG chunk_0001 count=" + count + " first=" + first + " last=" + last);
		check("chunk_0001 replays 500 actions across ticks 500-999",
				count == 500 && first == 500 && last == 999);

		// The final chunk carries the stale tick-5 action, clamped rather than written negative.
		ActionCodec.Reader tail = new ActionCodec.Reader(new ByteArrayInputStream(entries.get("chunk_0002.bin")), meta.chunks().get(2).startTick());
		ActionCodec.Action lastAction = null;
		for (ActionCodec.Action a = tail.next(); a != null; a = tail.next()) lastAction = a;
		System.out.println("      DEBUG last action tick=" + (lastAction == null ? "null" : lastAction.tick()) + " payload=" + (lastAction == null ? "" : new String(lastAction.payload(), StandardCharsets.UTF_8)));
		check("stale action clamped to the current tick, not negative",
				lastAction != null && lastAction.tick() == 1199
						&& new String(lastAction.payload(), StandardCharsets.UTF_8).equals("stale"));

		try (var walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}
}
