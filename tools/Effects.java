import com.relapzz.momenton.format.ActionCodec;
import com.relapzz.momenton.format.ReplayMetadata;
import com.relapzz.momenton.format.ReplayReader;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Names the impact effects a recording carries: particles, entity animations and damage events. */
public final class Effects {
	public static void main(String[] args) throws Exception {
		Map<Integer, String> particles = new HashMap<>();
		for (String line : Files.readAllLines(Path.of("tools/particle_ids.txt"))) {
			String[] parts = line.split("\t");
			particles.put(Integer.parseInt(parts[0]), parts[1]);
		}

		ReplayReader reader = new ReplayReader(Path.of(args[0]));
		for (ReplayMetadata.ChunkEntry entry : reader.metadata().chunks()) {
			ActionCodec.Reader r = reader.chunk(entry);
			ActionCodec.Action a;
			while ((a = r.next()) != null) {
				if (a.type() != ActionCodec.PACKET) continue;
				byte[] p = a.payload();
				ByteArrayInputStream in = new ByteArrayInputStream(p);
				int id = ActionCodec.readVarInt(in);
				if (id == 46) {
					ByteBuffer b = ByteBuffer.wrap(p);
					float speed = b.getFloat(39);
					int count = b.getInt(43);
					int type = p[47] & 0xFF;
					System.out.printf("t=%-5d PARTICLE %s count=%d speed=%.2f%n",
							a.tick(), particles.getOrDefault(type, "id:" + type), count, speed);
				} else if (id == 2) {
					int entity = ActionCodec.readVarInt(in);
					int anim = in.read();
					String name = switch (anim) {
						case 0 -> "SWING_MAIN_HAND";
						case 3 -> "SWING_OFF_HAND";
						case 4 -> "CRIT";
						case 5 -> "ENCHANTED_HIT";
						default -> "anim:" + anim;
					};
					System.out.printf("t=%-5d ANIMATE %s entity=%d%n", a.tick(), name, entity);
				} else if (id == 45) {
					int event = ByteBuffer.wrap(p).getInt(1);
					System.out.printf("t=%-5d LEVEL_EVENT %d%s%n", a.tick(), event,
							event == 2001 ? " (BLOCK_BROKEN)" : "");
				} else if (id == 25) {
					System.out.printf("t=%-5d DAMAGE_EVENT%n", a.tick());
				} else if (id == 41) {
					System.out.printf("t=%-5d HURT_ANIMATION%n", a.tick());
				}
			}
		}
		reader.close();
	}
}
