package com.relapzz.momenton.format;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntityMoveCodecTest {
	private record Decoded(int id, int flags, double x, double y, double z, float yaw, float pitch, float headYaw) {
	}

	private static byte[] payload() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		ActionCodec.writeVarInt(out, 4);

		ActionCodec.writeVarInt(out, 7);
		out.writeByte(EntityMoveCodec.FLAG_POS_ABSOLUTE | EntityMoveCodec.FLAG_ROTATION);
		out.writeDouble(100.5);
		out.writeDouble(64.0);
		out.writeDouble(-2000.25);
		out.writeShort(16384);
		out.writeShort(-8192);
		out.writeShort(-32768);

		ActionCodec.writeVarInt(out, 300);
		out.writeByte(EntityMoveCodec.FLAG_POS_DELTA);
		out.writeShort(4096);
		out.writeShort(-2048);
		out.writeShort(Short.MAX_VALUE);

		ActionCodec.writeVarInt(out, 1);
		out.writeByte(EntityMoveCodec.FLAG_ROTATION);
		out.writeShort(0);
		out.writeShort(1);
		out.writeShort(-1);

		ActionCodec.writeVarInt(out, 2);
		out.writeByte(EntityMoveCodec.FLAG_POS_DELTA | EntityMoveCodec.FLAG_ROTATION);
		out.writeShort(-1);
		out.writeShort(0);
		out.writeShort(1);
		out.writeShort(8192);
		out.writeShort(0);
		out.writeShort(8192);
		return bytes.toByteArray();
	}

	private static List<Decoded> decode(byte[] payload) throws IOException {
		List<Decoded> decoded = new ArrayList<>();
		EntityMoveCodec.decode(payload, (id, flags, x, y, z, yaw, pitch, headYaw) -> decoded.add(new Decoded(id, flags, x, y, z, yaw, pitch, headYaw)));
		return decoded;
	}

	@Test
	void absoluteAndDeltaPosesDecodeToTheirOwnLength() throws IOException {
		List<Decoded> decoded = decode(payload());
		assertEquals(List.of(
				new Decoded(7, EntityMoveCodec.FLAG_POS_ABSOLUTE | EntityMoveCodec.FLAG_ROTATION, 100.5, 64.0, -2000.25, 16384 / EntityMoveCodec.ROTATION_SCALE, -8192 / EntityMoveCodec.ROTATION_SCALE, -32768 / EntityMoveCodec.ROTATION_SCALE),
				new Decoded(300, EntityMoveCodec.FLAG_POS_DELTA, 1.0, -0.5, Short.MAX_VALUE / EntityMoveCodec.POS_SCALE, 0, 0, 0),
				new Decoded(1, EntityMoveCodec.FLAG_ROTATION, 0, 0, 0, 0, 1 / EntityMoveCodec.ROTATION_SCALE, -1 / EntityMoveCodec.ROTATION_SCALE),
				new Decoded(2, EntityMoveCodec.FLAG_POS_DELTA | EntityMoveCodec.FLAG_ROTATION, -1 / EntityMoveCodec.POS_SCALE, 0, 1 / EntityMoveCodec.POS_SCALE, 8192 / EntityMoveCodec.ROTATION_SCALE, 0, 8192 / EntityMoveCodec.ROTATION_SCALE)
		), decoded);
		assertEquals(90.0f, decoded.getFirst().yaw(), 1e-3f);
		assertEquals(-180.0f, decoded.getFirst().headYaw(), 1e-3f);
	}

	@Test
	void truncatedPayloadFails() throws IOException {
		byte[] payload = payload();
		assertThrows(EOFException.class, () -> decode(Arrays.copyOf(payload, payload.length - 1)));
	}
}
