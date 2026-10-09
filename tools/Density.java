import com.relapzz.momenton.format.ActionCodec;
import com.relapzz.momenton.format.ReplayMetadata;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class Density {
	public static void main(String[] args) throws Exception {
		Map<String, byte[]> e = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(Path.of(args[0])))) {
			for (ZipEntry z = zip.getNextEntry(); z != null; z = zip.getNextEntry())
				e.put(z.getName(), zip.readAllBytes());
		}
		ReplayMetadata m = ReplayMetadata.fromJson(new String(e.get("metadata.json"), StandardCharsets.UTF_8));
		int[] packets = new int[m.durationTicks() + 1];
		int[] moves = new int[m.durationTicks() + 1];
		int[] moveBytes = new int[m.durationTicks() + 1];
		for (ReplayMetadata.ChunkEntry c : m.chunks()) {
			ActionCodec.Reader r = new ActionCodec.Reader(new ByteArrayInputStream(e.get(String.format("chunk_%04d.bin", c.index()))), c.startTick());
			for (ActionCodec.Action a = r.next(); a != null; a = r.next()) {
				if (a.type() == ActionCodec.PACKET) packets[a.tick()]++;
				if (a.type() == ActionCodec.ENTITY_MOVE) { moves[a.tick()]++; moveBytes[a.tick()] += a.payload().length; }
			}
		}
		System.out.println("tick range   packets/tick   entityMove actions   avg move bytes");
		for (int start = 0; start < m.durationTicks(); start += 25) {
			int end = Math.min(start + 25, m.durationTicks());
			int p = 0, mv = 0, b = 0;
			for (int t = start; t < end; t++) { p += packets[t]; mv += moves[t]; b += moveBytes[t]; }
			System.out.printf("%4d-%4d %10.1f %14d %18.1f%n", start, end - 1, p / (double) (end - start), mv, mv == 0 ? 0.0 : b / (double) mv);
		}
	}
}
