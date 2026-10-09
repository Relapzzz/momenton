import com.relapzz.momenton.format.ActionCodec;
import com.relapzz.momenton.format.EntityMoveCodec;
import com.relapzz.momenton.format.FrameTrack;
import com.relapzz.momenton.format.ReplayMetadata;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Checks the ENTITY_MOVE track and the recorded path of the local player inside it.
 *
 * <p>Three things it is here to catch. That EntityMoveCodec and EntityPositionTracker agree byte for
 * byte, measured by rebuilding each payload's length from the flags that came back out of it. That
 * every chunk opens with absolute poses, without which a seek into the middle of a recording cannot
 * resolve a delta. And that the path agrees with frames.bin: in first person the recorded camera is
 * the player's own eye, so the two tracks check each other, and a phase error between them shows up
 * as a horizontal gap the size of one tick of walking.
 */
public class Motion {
	static final int STRIDE = FrameTrack.STRIDE;
	static final int LOGIN = 48;

	static int failures = 0;

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

		int localId = localPlayerId(e.get("snapshot_0000.bin"));
		System.out.println("      local player entity id " + localId);

		// The running absolute pose per entity, exactly as playback has to rebuild it.
		Map<Integer, double[]> pose = new HashMap<>();
		double[][] path = new double[m.durationTicks()][];
		int actions = 0, entries = 0, badFraming = 0, badChunkStart = 0, playerEntries = 0;
		Set<Integer> movers = new TreeSet<>();

		for (ReplayMetadata.ChunkEntry c : m.chunks()) {
			byte[] chunk = e.get(String.format("chunk_%04d.bin", c.index()));
			if (chunk == null) continue;
			ActionCodec.Reader reader = new ActionCodec.Reader(new ByteArrayInputStream(chunk), c.startTick());
			Set<Integer> seenInChunk = new HashSet<>();

			for (ActionCodec.Action a = reader.next(); a != null; a = reader.next()) {
				if (a.type() != ActionCodec.ENTITY_MOVE) continue;
				actions++;

				List<int[]> shape = new ArrayList<>();
				List<double[]> decoded = new ArrayList<>();
				EntityMoveCodec.decode(a.payload(), (id, flags, x, y, z, yaw, pitch, headYaw) -> {
					shape.add(new int[]{id, flags});
					decoded.add(new double[]{x, y, z, yaw, pitch, headYaw});
				});
				entries += shape.size();

				// Rebuilding the payload length from the flags proves the reader consumed exactly what
				// the writer produced. A field added on one side only would land here.
				int expected = varIntLength(shape.size());
				for (int[] s : shape)
					expected += varIntLength(s[0]) + 1
							+ ((s[1] & EntityMoveCodec.FLAG_POS_DELTA) != 0 ? 6 : 0)
							+ ((s[1] & EntityMoveCodec.FLAG_POS_ABSOLUTE) != 0 ? 24 : 0)
							+ ((s[1] & EntityMoveCodec.FLAG_ROTATION) != 0 ? 6 : 0);
				if (expected != a.payload().length) badFraming++;

				for (int i = 0; i < shape.size(); i++) {
					int id = shape.get(i)[0], flags = shape.get(i)[1];
					double[] d = decoded.get(i);
					movers.add(id);

					boolean moved = (flags & (EntityMoveCodec.FLAG_POS_DELTA | EntityMoveCodec.FLAG_POS_ABSOLUTE)) != 0;
					if (seenInChunk.add(id) && moved && (flags & EntityMoveCodec.FLAG_POS_ABSOLUTE) == 0)
						badChunkStart++;

					double[] p = pose.computeIfAbsent(id, k -> new double[5]);
					if ((flags & EntityMoveCodec.FLAG_POS_ABSOLUTE) != 0) {
						p[0] = d[0];
						p[1] = d[1];
						p[2] = d[2];
					} else if ((flags & EntityMoveCodec.FLAG_POS_DELTA) != 0) {
						p[0] += d[0];
						p[1] += d[1];
						p[2] += d[2];
					}
					if ((flags & EntityMoveCodec.FLAG_ROTATION) != 0) {
						p[3] = d[3];
						p[4] = d[4];
					}

					if (id == localId) {
						playerEntries++;
						if (a.tick() < path.length) path[a.tick()] = p.clone();
					}
				}
			}
		}

		System.out.println("      " + actions + " ENTITY_MOVE actions, " + entries + " entity entries, "
				+ movers.size() + " entities that ever moved");

		check("every ENTITY_MOVE payload decodes to exactly its own length", badFraming == 0);
		check("every chunk opens with absolute poses, so a seek can resolve the deltas after it",
				badChunkStart == 0);
		check("the local player is in the entity track, which is the only record of where it went",
				playerEntries > 0);
		if (playerEntries == 0) {
			System.out.println("\n" + failures + " FAILURES");
			System.exit(1);
		}

		// Carried forward: an unchanged entity writes nothing, so a tick with no entry holds the last
		// pose. Playback does the same thing by leaving the entity where it is.
		double[] last = null;
		for (int t = 0; t < path.length; t++) {
			if (path[t] != null) last = path[t];
			else path[t] = last;
		}

		double travelled = 0, turned = 0;
		for (int t = 1; t < path.length; t++) {
			if (path[t] == null || path[t - 1] == null) continue;
			travelled += Math.hypot(path[t][0] - path[t - 1][0], path[t][2] - path[t - 1][2]);
			turned += Math.abs(wrapDegrees(path[t][3] - path[t - 1][3]));
		}
		System.out.printf("      player path: %d poses, %.1f blocks travelled, %.0f degrees turned%n",
				playerEntries, travelled, turned);

		// In first person Camera.update puts the camera on the player's interpolated position plus the
		// eye height, so the gap between the two tracks is a measurement of their phase relative to
		// each other. Offset 0 is the relationship the recorder should produce: a frame stamped tick T
		// was rendered after tick T-1 was recorded, so it shows lerp(pose[T-2], pose[T-1]).
		ByteBuffer f = ByteBuffer.wrap(e.get("frames.bin")).order(ByteOrder.LITTLE_ENDIAN);
		Map<Integer, List<Double>> horizontal = new LinkedHashMap<>();
		List<Double> vertical = new ArrayList<>();
		List<Double> crouched = new ArrayList<>();
		for (int offset = 0; offset <= 2; offset++) horizontal.put(offset, new ArrayList<>());

		int sneaking = 0, thirdPerson = 0, thirdFrom = -1;
		for (int i = 0; i < f.capacity() / STRIDE; i++) {
			int flags = f.getInt(i * STRIDE + 52);
			if ((flags & FrameTrack.FLAG_SNEAKING) != 0) sneaking++;
			if ((flags & FrameTrack.FLAG_THIRD_PERSON) != 0) {
				thirdPerson++;
				if (thirdFrom < 0) thirdFrom = f.getInt(i * STRIDE);
			}
		}
		System.out.printf("      %d of %d frames third person%s, %d frames sneaking%n",
				thirdPerson, f.capacity() / STRIDE,
				thirdFrom < 0 ? "" : " (from tick " + thirdFrom + ")", sneaking);

		for (int i = 0; i < f.capacity() / STRIDE; i++) {
			int base = i * STRIDE;
			int tick = f.getInt(base);
			float progress = f.getFloat(base + 4);
			double cx = f.getDouble(base + 8), cy = f.getDouble(base + 16), cz = f.getDouble(base + 24);
			if ((f.getInt(base + 52) & FrameTrack.FLAG_THIRD_PERSON) != 0) continue;

			for (int offset = 0; offset <= 2; offset++) {
				int to = tick - 1 + offset;
				if (to < 1 || to >= path.length || path[to] == null || path[to - 1] == null) continue;
				double px = lerp(progress, path[to - 1][0], path[to][0]);
				double pz = lerp(progress, path[to - 1][2], path[to][2]);
				horizontal.get(offset).add(Math.hypot(cx - px, cz - pz));
				// Split by the sneak flag rather than smearing the two eye heights together. The
				// crouched set is what proves the flag tracks a real crouch: the camera drops with it.
				if (offset == 0)
					((f.getInt(base + 52) & FrameTrack.FLAG_SNEAKING) != 0 ? crouched : vertical)
							.add(cy - lerp(progress, path[to - 1][1], path[to][1]));
			}
		}

		for (Map.Entry<Integer, List<Double>> h : horizontal.entrySet())
			System.out.printf("      camera to player, offset %+d: median %.4f, 99th %.4f blocks (%d frames)%n",
					h.getKey(), median(h.getValue()), percentile(h.getValue(), 0.99), h.getValue().size());
		System.out.printf("      camera above player: median %.4f standing, %.4f crouched (%d frames)%n",
				median(vertical), median(crouched), crouched.size());

		List<Double> aligned = horizontal.get(0);
		check("first-person frames sit on the recorded path, so the camera and the player agree on "
						+ "which tick they are showing",
				!aligned.isEmpty() && percentile(aligned, 0.99) < 0.01);
		check("the camera is an eye height above the path, not at its feet",
				!vertical.isEmpty() && Math.abs(median(vertical) - 1.62) < 0.2);

		// Not a hardcoded 1.27, and deliberately not the full 1.62-to-1.27 drop either:
		// Camera.updateEyeHeight eases at 50% per *tick* (GameRenderer.tick), so a crouch reaches
		// 1.445 after one tick and only nears 1.27 after five. Short crouches therefore sit anywhere
		// in that range. What matters is that the flag moves with the camera at all - a flag stuck on,
		// or one recorded a tick out of step, fails this rather than passing it.
		if (crouched.isEmpty())
			System.out.println("SKIP  no sneaking frames - this recording cannot exercise the crouch");
		else
			check("sneaking frames sit at a lower eye height, so the flag tracks a real crouch",
					median(crouched) < median(vertical) - 0.05);

		System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILURES");
		if (failures > 0) System.exit(1);
	}

	/** GameJoinS2CPacket writes playerEntityId as a plain int, first, right after the packet id. */
	static int localPlayerId(byte[] snapshot) throws Exception {
		ByteArrayInputStream in = new ByteArrayInputStream(snapshot);
		byte[] join = in.readNBytes(ActionCodec.readVarInt(in));
		ByteArrayInputStream body = new ByteArrayInputStream(join);
		if (ActionCodec.readVarInt(body) != LOGIN) throw new IllegalStateException("snapshot does not lead with a join");
		return ByteBuffer.wrap(body.readNBytes(4)).getInt();
	}

	static int varIntLength(int value) {
		int length = 1;
		while ((value & 0xFFFFFF80) != 0) {
			value >>>= 7;
			length++;
		}
		return length;
	}

	static double lerp(double progress, double from, double to) {
		return from + (to - from) * progress;
	}

	static double wrapDegrees(double degrees) {
		double wrapped = degrees % 360.0;
		if (wrapped >= 180.0) wrapped -= 360.0;
		if (wrapped < -180.0) wrapped += 360.0;
		return wrapped;
	}

	static double median(List<Double> values) {
		return percentile(values, 0.5);
	}

	static double percentile(List<Double> values, double fraction) {
		if (values.isEmpty()) return Double.NaN;
		List<Double> sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		return sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * fraction)));
	}
}
