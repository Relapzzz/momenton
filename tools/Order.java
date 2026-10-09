import com.relapzz.momenton.format.ActionCodec;
import com.relapzz.momenton.format.ReplayMetadata;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Dumps the action sequence over a tick window, in file order. Ordering bugs are invisible to counts. */
public class Order {
	public static void main(String[] args) throws Exception {
		Map<Integer, String> names = new HashMap<>();
		for (String line : Files.readAllLines(Path.of("tools/s2c_ids.txt"))) {
			String[] p = line.split("\t");
			names.put(Integer.parseInt(p[0]), p[1]);
		}
		int from = Integer.parseInt(args[1]);
		int to = Integer.parseInt(args[2]);

		Map<String, byte[]> e = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(Path.of(args[0])))) {
			for (ZipEntry z = zip.getNextEntry(); z != null; z = zip.getNextEntry())
				e.put(z.getName(), zip.readAllBytes());
		}
		ReplayMetadata m = ReplayMetadata.fromJson(new String(e.get("metadata.json"), StandardCharsets.UTF_8));

		String[] kinds = {"PACKET", "ENTITY_MOVE", "LOCAL_PLAYER", "DIMENSION_CHANGE", "UI_STATE"};
		for (ReplayMetadata.ChunkEntry c : m.chunks()) {
			if (c.endTick() < from || c.startTick() > to) continue;
			ActionCodec.Reader r = new ActionCodec.Reader(
					new ByteArrayInputStream(e.get(String.format("chunk_%04d.bin", c.index()))), c.startTick());
			for (ActionCodec.Action a = r.next(); a != null; a = r.next()) {
				if (a.tick() < from || a.tick() > to) continue;
				String what = a.type() == ActionCodec.PACKET
						? names.getOrDefault(varint(a.payload()), "id " + varint(a.payload()))
						: "<" + (a.type() < kinds.length ? kinds[a.type()] : "type " + a.type()) + ">";
				System.out.println(a.tick() + "\t" + what + "\t" + a.payload().length + " bytes");
			}
		}
	}

	static int varint(byte[] b) {
		int value = 0, shift = 0, i = 0;
		while (true) {
			int by = b[i++] & 0xff;
			value |= (by & 0x7f) << shift;
			if ((by & 0x80) == 0) return value;
			shift += 7;
		}
	}
}
