package com.relapzz.momenton.format;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Framing for the action streams inside a replay: {@code [varint type][varint tickDelta][varint length][payload]}.
 *
 * <p>Ticks are stored as deltas from the previous action in the same stream, so a stream is only
 * readable front to back - which is why replays are cut into chunks and seeking loads a snapshot
 * rather than scanning. {@link Reader} accumulates the deltas back into absolute ticks.
 */
public final class ActionCodec {
	public static final int PACKET = 0;
	public static final int ENTITY_MOVE = 1;
	public static final int LOCAL_PLAYER = 2;
	public static final int DIMENSION_CHANGE = 3;
	public static final int UI_STATE = 4;

	private ActionCodec() {
	}

	/** One decoded action. {@code tick} is absolute, already accumulated from the stream's deltas. */
	public record Action(int type, int tick, byte[] payload) {
	}

	public static void write(OutputStream out, int type, int tickDelta, byte[] payload, int length) throws IOException {
		writeVarInt(out, type);
		writeVarInt(out, tickDelta);
		writeVarInt(out, length);
		out.write(payload, 0, length);
	}

	/**
	 * Reads a single action stream, turning tick deltas back into absolute ticks.
	 *
	 * <p>Deltas in a chunk are relative to that chunk's first tick, not to tick zero - that is what
	 * makes a chunk independently readable. Seed {@code startTick} from the chunk index in
	 * {@link ReplayMetadata}.
	 */
	public static final class Reader {
		private final InputStream in;
		private int tick;

		public Reader(InputStream in, int startTick) {
			this.in = in;
			this.tick = startTick;
		}

		/** The next action, or null at end of stream. */
		public Action next() throws IOException {
			int type = readVarIntOrEof(in);
			if (type < 0) {
				return null;
			}
			tick += readVarInt(in);
			byte[] payload = in.readNBytes(readVarInt(in));
			return new Action(type, tick, payload);
		}
	}

	public static void writeVarInt(OutputStream out, int value) throws IOException {
		while ((value & 0xFFFFFF80) != 0) {
			out.write((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.write(value);
	}

	public static int readVarInt(InputStream in) throws IOException {
		int value = readVarIntOrEof(in);
		if (value < 0) {
			throw new EOFException("Truncated action stream");
		}
		return value;
	}

	/** Reads a varint, returning -1 if the stream ended cleanly before the first byte. */
	private static int readVarIntOrEof(InputStream in) throws IOException {
		int first = in.read();
		if (first < 0) {
			return -1;
		}
		int value = first & 0x7F;
		int shift = 7;
		while ((first & 0x80) != 0) {
			first = in.read();
			if (first < 0) {
				throw new EOFException("Truncated varint");
			}
			value |= (first & 0x7F) << shift;
			shift += 7;
		}
		return value;
	}
}
