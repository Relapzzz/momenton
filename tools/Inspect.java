import com.relapzz.momenton.format.ActionCodec;
import com.relapzz.momenton.format.ReplayMetadata;
import com.relapzz.momenton.format.UiStateCodec;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class Inspect {
	static int failures = 0;
	static final String[] TYPE = {"PACKET", "ENTITY_MOVE", "LOCAL_PLAYER", "DIMENSION_CHANGE", "UI_STATE", "ATTACK (removed)"};

	static void check(String what, boolean ok) {
		System.out.println((ok ? "PASS  " : "FAIL  ") + what);
		if (!ok) failures++;
	}

	public static void main(String[] args) throws Exception {
		Map<String, byte[]> e = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(Path.of(args[0])))) {
			for (ZipEntry z = zip.getNextEntry(); z != null; z = zip.getNextEntry())
				e.put(z.getName(), zip.readAllBytes());
		}
		ReplayMetadata m = ReplayMetadata.fromJson(new String(e.get("metadata.json"), StandardCharsets.UTF_8));

		for (ReplayMetadata.ChunkEntry c : m.chunks()) {
			readTrack(e, String.format("chunk_%04d.bin", c.index()), c);
			readTrack(e, String.format("ui_%04d.bin", c.index()), c);
			uiOpensComplete(e, c);
		}
		frames(e.get("frames.bin"), m);

		System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILURES");
		if (failures > 0) System.exit(1);
	}

	/**
	 * The first UI action of a chunk must carry every field. The track is change-gated, so without the
	 * boundary reset a seek into a later chunk never learns a hotbar slot or an open screen last
	 * written in an earlier one - and the file looks perfectly well-formed either way.
	 */
	static void uiOpensComplete(Map<String, byte[]> e, ReplayMetadata.ChunkEntry c) throws Exception {
		String name = String.format("ui_%04d.bin", c.index());
		ActionCodec.Action first = new ActionCodec.Reader(
				new ByteArrayInputStream(e.get(name)), c.startTick()).next();
		int all = UiStateCodec.FLAG_SELECTED_SLOT | UiStateCodec.FLAG_SCREEN | UiStateCodec.FLAG_CHAT_SCROLL
				| UiStateCodec.FLAG_SWING | UiStateCodec.FLAG_COOLDOWN;
		check(name + " opens with the whole UI state, so a seek into this chunk knows it",
				first != null && first.tick() == c.startTick()
						&& (UiStateCodec.decode(first.payload()).flags() & all) == all);
	}

	static void readTrack(Map<String, byte[]> e, String name, ReplayMetadata.ChunkEntry c) throws Exception {
		ActionCodec.Reader r = new ActionCodec.Reader(new ByteArrayInputStream(e.get(name)), c.startTick());
		Map<String, Integer> counts = new TreeMap<>();
		int n = 0, prev = Integer.MIN_VALUE, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
		boolean ordered = true, inRange = true;
		long bytes = 0;
		for (ActionCodec.Action a = r.next(); a != null; a = r.next()) {
			counts.merge(a.type() < TYPE.length ? TYPE[a.type()] : "TYPE" + a.type(), 1, Integer::sum);
			ordered &= a.tick() >= prev;
			inRange &= a.tick() >= c.startTick() && a.tick() < c.endTick();
			prev = a.tick();
			min = Math.min(min, a.tick());
			max = Math.max(max, a.tick());
			bytes += a.payload().length;
			n++;
		}
		System.out.printf("      %-16s %5d actions, ticks %d-%d, %d payload bytes, %s%n",
				name, n, n == 0 ? -1 : min, n == 0 ? -1 : max, bytes, counts);
		check(name + " parses fully, ticks ordered and inside [" + c.startTick() + "," + c.endTick() + ")",
				ordered && inRange);
	}

	static void frames(byte[] data, ReplayMetadata m) {
		check("frames.bin length is exactly 56 * framesRendered", data.length == 56L * m.framesRendered());
		ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		int prevTick = -1;
		boolean ticksOrdered = true, ticksInRange = true, progressInRange = true, finite = true, fovSane = true;
		int distinctTicks = 0;
		float minFov = Float.MAX_VALUE, maxFov = -Float.MAX_VALUE;
		double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		while (b.hasRemaining()) {
			int tick = b.getInt();
			float progress = b.getFloat();
			double x = b.getDouble(), y = b.getDouble(), z = b.getDouble();
			float yaw = b.getFloat(), pitch = b.getFloat(), fov = b.getFloat();
			float mx = b.getFloat(), my = b.getFloat();
			b.getInt();
			if (tick != prevTick) distinctTicks++;
			ticksOrdered &= tick >= prevTick;
			ticksInRange &= tick >= 0 && tick <= m.durationTicks();
			progressInRange &= progress >= 0f && progress < 1f;
			finite &= Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
					&& Float.isFinite(yaw) && Float.isFinite(pitch) && Float.isFinite(mx) && Float.isFinite(my);
			fovSane &= fov > 1f && fov < 180f;
			minFov = Math.min(minFov, fov); maxFov = Math.max(maxFov, fov);
			minY = Math.min(minY, y); maxY = Math.max(maxY, y);
			prevTick = tick;
		}
		System.out.printf("      frames.bin       %d records over %d distinct ticks, fov %.2f-%.2f, camera y %.2f-%.2f%n",
				data.length / 56, distinctTicks, minFov, maxFov, minY, maxY);
		check("frame ticks non-decreasing and within the recording", ticksOrdered && ticksInRange);
		check("tick progress in [0,1)", progressInRange);
		check("camera pose is finite", finite);
		check("fov is a sane angle", fovSane);
		check("frames cover most ticks (interpolated playback, not a slideshow)",
				distinctTicks > m.durationTicks() * 0.9);
	}
}
