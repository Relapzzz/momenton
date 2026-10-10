import com.relapzz.momenton.format.ActionCodec;
import com.relapzz.momenton.format.ReplayMetadata;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Checks the setup track a Phase 2 recording must carry: setup.bin plus a game join at tick 0. */
public class Setup {
	static final int LOGIN = 48;
	static final int CHUNK = 44;
	static final int CENTER = 92;

	/** Configuration S2C ids, in the registration order of ConfigurationStates.S2C_FACTORY. */
	static final String[] CONFIG = {
			"COOKIE_REQUEST", "CUSTOM_PAYLOAD", "DISCONNECT", "READY", "KEEP_ALIVE", "PING",
			"RESET_CHAT", "REGISTRY_DATA", "RESOURCE_PACK_POP", "RESOURCE_PACK_PUSH", "STORE_COOKIE",
			"TRANSFER", "FEATURES", "UPDATE_TAGS", "SELECT_KNOWN_PACKS", "CUSTOM_REPORT_DETAILS",
			"SERVER_LINKS", "CLEAR_DIALOG", "SHOW_DIALOG", "CODE_OF_CONDUCT"};

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

		check("setup.bin is present", e.containsKey("setup.bin"));
		if (e.containsKey("setup.bin")) {
			InputStream in = new ByteArrayInputStream(e.get("setup.bin"));
			List<String> sequence = new ArrayList<>();
			Map<String, Integer> counts = new LinkedHashMap<>();
			while (in.available() > 0) {
				int length = ActionCodec.readVarInt(in);
				byte[] packet = in.readNBytes(length);
				if (packet.length != length) throw new EOFException("truncated setup.bin");
				int id = varint(packet);
				String name = id < CONFIG.length ? CONFIG[id] : "id " + id;
				sequence.add(name);
				counts.merge(name, 1, Integer::sum);
			}
			System.out.println("      setup.bin " + sequence.size() + " packets, "
					+ e.get("setup.bin").length + " bytes, " + counts);
			check("setup.bin frames cleanly and is not empty", !sequence.isEmpty());
			check("it opens with the feature set, which is sent before the registry sync even starts",
					sequence.indexOf("FEATURES") == 0);
			check("known packs are negotiated before any registry arrives",
					sequence.indexOf("SELECT_KNOWN_PACKS") >= 0
							&& sequence.indexOf("SELECT_KNOWN_PACKS") < sequence.indexOf("REGISTRY_DATA"));
			check("registries and tags are both present", counts.containsKey("REGISTRY_DATA")
					&& counts.containsKey("UPDATE_TAGS"));
			check("it ends with ready, which is what transitions playback into the play phase",
					sequence.indexOf("READY") == sequence.size() - 1);
		}

		Map<Integer, String> names = new HashMap<>();
		for (String line : Files.readAllLines(Path.of("tools/s2c_ids.txt"))) {
			String[] p = line.split("\t");
			names.put(Integer.parseInt(p[0]), p[1]);
		}

		// One snapshot per chunk, which is what makes seeking cheap. Each is a standalone rebuild of
		// the world, so every one of them gets the same checks.
		check("there is a snapshot for every chunk, so any tick can be seeked to",
				m.chunks().stream().allMatch(c -> e.containsKey(String.format("snapshot_%04d.bin", c.index()))));

		for (ReplayMetadata.ChunkEntry c : m.chunks()) {
			String name = String.format("snapshot_%04d.bin", c.index());
			if (!e.containsKey(name)) continue;

			List<String> order = new ArrayList<>();
			Map<String, Integer> counts = new LinkedHashMap<>();
			InputStream in = new ByteArrayInputStream(e.get(name));
			while (in.available() > 0) {
				byte[] packet = in.readNBytes(ActionCodec.readVarInt(in));
				String packetName = names.getOrDefault(varint(packet), "id " + varint(packet));
				order.add(packetName);
				counts.merge(packetName, 1, Integer::sum);
			}

			System.out.println("      " + name + " (tick " + c.startTick() + "): "
					+ e.get(name).length + " bytes, " + counts);
			check(name + " leads with the game join, which is what rebuilds the world on a seek",
					!order.isEmpty() && order.get(0).equals("PlayPackets.LOGIN"));
			check(name + " announces INITIAL_CHUNKS_COMING, the only thing that moves the loading "
							+ "screen past its first state",
					counts.containsKey("PlayPackets.GAME_EVENT"));
			check(name + " carries the loaded chunks and the entities",
					counts.getOrDefault("PlayPackets.LEVEL_CHUNK_WITH_LIGHT", 0) > 0
							&& counts.getOrDefault("PlayPackets.ADD_ENTITY", 0) > 0);
			check(name + " carries the player's own inventory, which arrived before F9 and is never "
							+ "sent again - without it the replay renders an empty hotbar and empty hands",
					counts.containsKey("PlayPackets.CONTAINER_SET_CONTENT"));
			check(name + " recentres the chunk map BEFORE any chunk, or the client drops every one "
							+ "of them as out of view range",
					order.indexOf("PlayPackets.SET_CHUNK_CACHE_CENTER") >= 0
							&& order.indexOf("PlayPackets.SET_CHUNK_CACHE_CENTER")
									< order.indexOf("PlayPackets.LEVEL_CHUNK_WITH_LIGHT"));
			if (m.player() != null)
				check(name + " carries the tab list BEFORE any entity, or every player spawn is dropped "
								+ "and every skin falls back to Steve",
						order.indexOf("PlayPackets.PLAYER_INFO_UPDATE") >= 0
								&& order.indexOf("PlayPackets.PLAYER_INFO_UPDATE")
										< order.indexOf("PlayPackets.ADD_ENTITY"));
		}

		// A snapshot must reach at least as far as the server streams, or the outer ring of chunks the
		// client already had is in no snapshot at all and nothing ever re-sends it - a permanent hole
		// at the edge of view. Both radii are measured from the recording rather than assumed, so this
		// stays correct whatever the render distance was.
		int streamed = 0;
		for (ReplayMetadata.ChunkEntry c : m.chunks()) {
			byte[] chunk = e.get(String.format("chunk_%04d.bin", c.index()));
			if (chunk == null) continue;
			ActionCodec.Reader r = new ActionCodec.Reader(new ByteArrayInputStream(chunk), c.startTick());
			int cx = 0, cz = 0;
			for (ActionCodec.Action a = r.next(); a != null; a = r.next()) {
				if (a.type() != ActionCodec.PACKET) continue;
				int[] at = {0};
				int id = varint(a.payload(), at);
				if (id == CENTER) {
					cx = varint(a.payload(), at);
					cz = varint(a.payload(), at);
				} else if (id == CHUNK) {
					streamed = Math.max(streamed, chebyshev(a.payload(), at, cx, cz));
				}
			}
		}

		for (ReplayMetadata.ChunkEntry c : m.chunks()) {
			byte[] snap = e.get(String.format("snapshot_%04d.bin", c.index()));
			if (snap == null) continue;
			InputStream in = new ByteArrayInputStream(snap);
			int cx = 0, cz = 0, reach = 0;
			while (in.available() > 0) {
				byte[] packet = in.readNBytes(ActionCodec.readVarInt(in));
				int[] at = {0};
				int id = varint(packet, at);
				if (id == CENTER) {
					cx = varint(packet, at);
					cz = varint(packet, at);
				} else if (id == CHUNK) {
					reach = Math.max(reach, chebyshev(packet, at, cx, cz));
				}
			}
			check(String.format("snapshot_%04d.bin reaches %d chunks out, at least as far as the %d the "
					+ "server streams - a shorter snapshot leaves a ring nothing ever re-sends",
					c.index(), reach, streamed), reach >= streamed);
		}

		// A dimension change rebuilds the world mid-stream, so it needs the same three things a join
		// does - and gets them from the recorded stream, not from a snapshot. Order matters: the
		// respawn is what creates the new ClientWorld, so nothing before it counts. A second is the
		// window rather than the respawn's own tick: the server sends the placement on the tick after
		// the respawn as often as on the same one, and either way the client is on the terrain screen
		// until it arrives.
		for (ReplayMetadata.DimensionChange d : m.dimensionChanges()) {
			List<String> at = new ArrayList<>();
			int until = d.tick() + 20;
			for (ReplayMetadata.ChunkEntry c : m.chunks()) {
				if (until < c.startTick() || d.tick() >= c.endTick()) continue;
				ActionCodec.Reader r = new ActionCodec.Reader(
						new ByteArrayInputStream(e.get(String.format("chunk_%04d.bin", c.index()))), c.startTick());
				for (ActionCodec.Action a = r.next(); a != null; a = r.next())
					if (a.tick() >= d.tick() && a.tick() <= until && a.type() == ActionCodec.PACKET)
						at.add(names.getOrDefault(varint(a.payload()), "id " + varint(a.payload())));
			}
			String where = d.dimension() + " at tick " + d.tick();
			int respawn = at.indexOf("PlayPackets.RESPAWN");
			check(where + " respawns, which is what builds the new world", respawn >= 0);
			check(where + " places the player after the respawn, or the terrain screen waits out its "
							+ "30-second timeout on chunks that never reach the old position",
					respawn >= 0 && at.subList(respawn, at.size()).contains("PlayPackets.PLAYER_POSITION"));
			check(where + " announces INITIAL_CHUNKS_COMING after the respawn",
					respawn >= 0 && at.subList(respawn, at.size()).contains("PlayPackets.GAME_EVENT"));
		}

		System.out.println("\ndurationTicks " + m.durationTicks() + ", chunks " + m.chunks().size()
				+ ", dimension changes " + m.dimensionChanges().size());
		System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILURES");
		if (failures > 0) System.exit(1);
	}

	static int varint(byte[] b) {
		return varint(b, new int[]{0});
	}

	/** Reads a varint at {@code at[0]} and advances it, so a packet body can be walked field by field. */
	static int varint(byte[] b, int[] at) {
		int value = 0, shift = 0;
		while (true) {
			int by = b[at[0]++] & 0xff;
			value |= (by & 0x7f) << shift;
			if ((by & 0x80) == 0) return value;
			shift += 7;
		}
	}

	/** ChunkDataS2CPacket opens with chunkX and chunkZ as plain ints, so this needs no registries. */
	static int chebyshev(byte[] packet, int[] at, int centerX, int centerZ) {
		int x = ByteBuffer.wrap(packet, at[0], 4).getInt();
		int z = ByteBuffer.wrap(packet, at[0] + 4, 4).getInt();
		return Math.max(Math.abs(x - centerX), Math.abs(z - centerZ));
	}
}
