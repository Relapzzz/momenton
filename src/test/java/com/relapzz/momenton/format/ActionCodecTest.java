package com.relapzz.momenton.format;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActionCodecTest {
	private static final int[] TYPES = {ActionCodec.PACKET, ActionCodec.ENTITY_MOVE, ActionCodec.LOCAL_PLAYER, ActionCodec.DIMENSION_CHANGE, ActionCodec.UI_STATE};

	@Test
	void everyTypeRoundTripsWithChunkRelativeTicks() throws IOException {
		int startTick = 1500;
		int[] deltas = {0, 1, 0, 127, 128};
		byte[][] payloads = {new byte[0], {7}, new byte[127], new byte[128], new byte[300]};
		for (byte[] payload : payloads) Arrays.fill(payload, (byte) payload.length);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int i = 0; i < TYPES.length; i++) ActionCodec.write(out, TYPES[i], deltas[i], payloads[i], payloads[i].length);

		ActionCodec.Reader reader = new ActionCodec.Reader(new ByteArrayInputStream(out.toByteArray()), startTick);
		List<ActionCodec.Action> read = new ArrayList<>();
		for (ActionCodec.Action action = reader.next(); action != null; action = reader.next()) read.add(action);

		assertEquals(TYPES.length, read.size());
		int tick = startTick;
		for (int i = 0; i < TYPES.length; i++) {
			tick += deltas[i];
			assertEquals(TYPES[i], read.get(i).type());
			assertEquals(tick, read.get(i).tick());
			assertArrayEquals(payloads[i], read.get(i).payload());
		}
		assertNull(reader.next());
	}

	@Test
	void writeHonoursLength() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ActionCodec.write(out, ActionCodec.PACKET, 3, new byte[]{1, 2, 3, 4, 5}, 2);
		ActionCodec.Action action = new ActionCodec.Reader(new ByteArrayInputStream(out.toByteArray()), 10).next();
		assertEquals(13, action.tick());
		assertArrayEquals(new byte[]{1, 2}, action.payload());
	}

	@Test
	void varintEdgesUseTheExpectedByteCount() throws IOException {
		int[][] cases = {{0, 1}, {127, 1}, {128, 2}, {16383, 2}, {16384, 3}, {2097151, 3}, {2097152, 4}, {268435455, 4}, {268435456, 5}, {Integer.MAX_VALUE, 5}};
		for (int[] c : cases) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ActionCodec.writeVarInt(out, c[0]);
			assertEquals(c[1], out.size(), "bytes for " + c[0]);
			ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
			assertEquals(c[0], ActionCodec.readVarInt(in));
			assertEquals(-1, in.read());
		}
	}

	@Test
	void truncatedStreamsFail() throws IOException {
		assertThrows(EOFException.class, () -> ActionCodec.readVarInt(new ByteArrayInputStream(new byte[0])));
		assertThrows(EOFException.class, () -> ActionCodec.readVarInt(new ByteArrayInputStream(new byte[]{(byte) 0x80})));

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ActionCodec.writeVarInt(out, ActionCodec.PACKET);
		assertThrows(EOFException.class, () -> new ActionCodec.Reader(new ByteArrayInputStream(out.toByteArray()), 0).next());
	}
}
