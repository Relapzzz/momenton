import com.relapzz.momenton.format.ActionCodec;
import com.relapzz.momenton.format.ReplayMetadata;
import com.relapzz.momenton.format.ReplayReader;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Boundary {
	static final int ADD_ENTITY = 1;

	record ChunkPos(int x, int z) {
		static ChunkPos read(byte[] packet, int[] at) {
			ByteBuffer b = ByteBuffer.wrap(packet, at[0], 8);
			return new ChunkPos(b.getInt(), b.getInt());
		}
	}

	public static void main(String[] args) throws Exception {
		int missing = 0;
		try (ReplayReader reader = new ReplayReader(Path.of(args[0]))) {
			for (ReplayMetadata.ChunkEntry c : reader.metadata().chunks()) {
				if (c.index() == 0) continue;

				Set<ChunkPos> snapshotChunks = new HashSet<>();
				Set<Integer> snapshotEntities = new HashSet<>();
				InputStream in = new ByteArrayInputStream(reader.snapshot(c.index()));
				while (in.available() > 0) {
					byte[] packet = in.readNBytes(ActionCodec.readVarInt(in));
					int[] at = {0};
					int id = Setup.varint(packet, at);
					if (id == Setup.CHUNK) snapshotChunks.add(ChunkPos.read(packet, at));
					else if (id == ADD_ENTITY) snapshotEntities.add(Setup.varint(packet, at));
				}

				int chunks = 0, entities = 0;
				List<String> lost = new ArrayList<>();
				ActionCodec.Reader r = reader.chunk(c);
				for (ActionCodec.Action a = r.next(); a != null && a.tick() <= c.startTick(); a = r.next()) {
					if (a.type() != ActionCodec.PACKET || a.tick() != c.startTick()) continue;
					int[] at = {0};
					int id = Setup.varint(a.payload(), at);
					if (id == Setup.CHUNK) {
						chunks++;
						ChunkPos pos = ChunkPos.read(a.payload(), at);
						if (!snapshotChunks.contains(pos)) lost.add("chunk [" + pos.x() + ", " + pos.z() + "]");
					} else if (id == ADD_ENTITY) {
						entities++;
						int entity = Setup.varint(a.payload(), at);
						if (!snapshotEntities.contains(entity)) lost.add("entity " + entity);
					}
				}

				System.out.printf("%s  chunk %d at tick %d: snapshot has %d chunks and %d entities; %d chunk packets and %d entity spawns stamped %d, %d of them in neither%n",
						lost.isEmpty() ? "PASS" : "FAIL", c.index(), c.startTick(), snapshotChunks.size(),
						snapshotEntities.size(), chunks, entities, c.startTick(), lost.size());
				for (String item : lost) System.out.println("        missing " + item);
				missing += lost.size();
			}
		}
		System.out.println(missing == 0 ? "\nALL PASS" : "\n" + missing + " MISSING");
		if (missing > 0) System.exit(1);
	}
}
