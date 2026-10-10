import com.relapzz.momenton.format.ActionCodec;
import com.relapzz.momenton.format.FrameTrack;
import com.relapzz.momenton.format.ReplayMetadata;
import com.relapzz.momenton.format.ReplayReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Deaths {
	private static final int ENTITY_EVENT = 34;
	private static final int ENTITY_POSITION_SYNC = 35;
	private static final int REMOVE_ENTITIES = 75;

	public static void main(String[] args) throws IOException {
		try (ReplayReader reader = new ReplayReader(Path.of(args[0]))) {
			int simulation = simulationDistance(reader.snapshot(0));
			FrameTrack frames = reader.frames();
			System.out.println("join simulation distance " + simulation);

			Map<Integer, int[]> deaths = new LinkedHashMap<>();
			Map<Integer, double[]> corpses = new LinkedHashMap<>();
			for (ReplayMetadata.ChunkEntry entry : reader.metadata().chunks()) {
				ActionCodec.Reader r = reader.chunk(entry);
				for (ActionCodec.Action a = r.next(); a != null; a = r.next()) {
					if (a.type() != ActionCodec.PACKET) continue;
					byte[] p = a.payload();
					ByteArrayInputStream in = new ByteArrayInputStream(p);
					int id = ActionCodec.readVarInt(in);
					if (id == ENTITY_EVENT && p[5] == 3) {
						deaths.putIfAbsent(ByteBuffer.wrap(p).getInt(1), new int[]{a.tick(), -1});
					} else if (id == ENTITY_POSITION_SYNC) {
						int entity = ActionCodec.readVarInt(in);
						int[] death = deaths.get(entity);
						if (death != null && !corpses.containsKey(entity)) {
							ByteBuffer b = ByteBuffer.wrap(p, p.length - in.available(), 24);
							corpses.put(entity, new double[]{a.tick(), b.getDouble(), b.getDouble(), b.getDouble()});
						}
					} else if (id == REMOVE_ENTITIES) {
						int count = ActionCodec.readVarInt(in);
						for (int i = 0; i < count; i++) {
							int[] death = deaths.get(ActionCodec.readVarInt(in));
							if (death != null && death[1] < 0) death[1] = a.tick();
						}
					}
				}
			}

			int frozen = 0;
			for (Map.Entry<Integer, int[]> death : deaths.entrySet()) {
				double[] corpse = corpses.get(death.getKey());
				if (corpse == null) {
					System.out.printf("entity %5d dies at %5d, removed at %5d, no position sync while dying%n", death.getKey(), death.getValue()[0], death.getValue()[1]);
					continue;
				}
				FrameTrack.Pose viewer = frames.sample((int) corpse[0], 0);
				int distance = Math.max(Math.abs(chunk(corpse[1]) - chunk(viewer.x())), Math.abs(chunk(corpse[3]) - chunk(viewer.z())));
				boolean stops = distance > simulation;
				frozen += stops ? 1 : 0;
				System.out.printf("entity %5d dies at %5d, removed at %5d, corpse chunk distance %d at tick %d%s%n", death.getKey(), death.getValue()[0],
						death.getValue()[1], distance, (int) corpse[0], stops ? "  deathTime stops in playback" : "");
			}
			System.out.printf("%d of %d deaths are outside the recorded simulation distance%n", frozen, deaths.size());
		}
	}

	private static int chunk(double coordinate) {
		return Math.floorDiv((int) Math.floor(coordinate), 16);
	}

	private static int simulationDistance(byte[] snapshot) throws IOException {
		ByteArrayInputStream in = new ByteArrayInputStream(snapshot);
		ActionCodec.readVarInt(in);
		ActionCodec.readVarInt(in);
		in.skipNBytes(5);
		int dimensions = ActionCodec.readVarInt(in);
		for (int i = 0; i < dimensions; i++) {
			in.skipNBytes(ActionCodec.readVarInt(in));
		}
		ActionCodec.readVarInt(in);
		ActionCodec.readVarInt(in);
		return ActionCodec.readVarInt(in);
	}
}
