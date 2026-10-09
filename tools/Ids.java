import com.relapzz.momenton.format.ActionCodec;
import com.relapzz.momenton.format.ReplayMetadata;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class Ids {
	public static void main(String[] args) throws Exception {
		Map<Integer, String> names = new HashMap<>();
		for (String line : Files.readAllLines(Path.of(args[1]))) {
			String[] p = line.split("\t");
			names.put(Integer.parseInt(p[0]), p[1]);
		}
		int want = args.length > 2 ? Integer.parseInt(args[2]) : -1;

		Map<String, byte[]> e = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(Path.of(args[0])))) {
			for (ZipEntry z = zip.getNextEntry(); z != null; z = zip.getNextEntry())
				e.put(z.getName(), zip.readAllBytes());
		}
		ReplayMetadata m = ReplayMetadata.fromJson(new String(e.get("metadata.json"), StandardCharsets.UTF_8));

		Map<Integer, int[]> stat = new TreeMap<>();
		for (ReplayMetadata.ChunkEntry c : m.chunks()) {
			ActionCodec.Reader r = new ActionCodec.Reader(new ByteArrayInputStream(e.get(String.format("chunk_%04d.bin", c.index()))), c.startTick());
			for (ActionCodec.Action a = r.next(); a != null; a = r.next()) {
				if (a.type() != ActionCodec.PACKET) continue;
				int id = varint(a.payload());
				int[] s = stat.computeIfAbsent(id, k -> new int[]{0, Integer.MAX_VALUE, -1});
				s[0]++; s[1] = Math.min(s[1], a.tick()); s[2] = Math.max(s[2], a.tick());
				if (id == want) {
					System.out.println("  " + names.get(id) + " at tick " + a.tick() + ", " + a.payload().length + " bytes");
					StringBuilder sb = new StringBuilder();
					for (byte b : a.payload()) sb.append(b >= 32 && b < 127 ? (char) b : '.');
					System.out.println("    " + sb);
				}
			}
		}
		System.out.printf("%-38s %7s  %s%n", "packet", "count", "ticks");
		stat.entrySet().stream().sorted((x, y) -> y.getValue()[0] - x.getValue()[0]).forEach(x ->
				System.out.printf("%-38s %7d  %d-%d%n", names.getOrDefault(x.getKey(), "id " + x.getKey()),
						x.getValue()[0], x.getValue()[1], x.getValue()[2]));
		System.out.println("\nabsent, and expected to be:");
		for (String n : new String[]{"PlayPackets.BUNDLE", "PlayPackets.PLAYER_CHAT", "PlayPackets.PLAYER_POSITION", "CommonPackets.CUSTOM_PAYLOAD_S2C"}) {
			int id = names.entrySet().stream().filter(x -> x.getValue().equals(n)).findFirst().orElseThrow().getKey();
			System.out.printf("  %-38s id %3d  %s%n", n, id, stat.containsKey(id) ? "PRESENT (" + stat.get(id)[0] + ")" : "absent");
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
