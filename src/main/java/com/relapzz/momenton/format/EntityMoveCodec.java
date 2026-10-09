package com.relapzz.momenton.format;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * The payload inside an {@code ENTITY_MOVE} action: {@code [varint count]} then, per entity,
 * {@code [varint id][byte flags][position][rotation]}.
 *
 * <p>Positions are quantised to 1/4096 of a block and rotations to 1/182 of a degree, the precision
 * vanilla's own relative-move packets use. A move that fits in a short is written as a delta from the
 * entity's last recorded pose, so decoding is stateful - the consumer accumulates. Every tracker is
 * reset at a chunk boundary, so the first action of a chunk is absolute and a chunk decodes without
 * the ones before it.
 */
public final class EntityMoveCodec {
	public static final int FLAG_POS_DELTA = 1;
	public static final int FLAG_POS_ABSOLUTE = 2;
	public static final int FLAG_ROTATION = 4;

	public static final double POS_SCALE = 4096.0;
	public static final float ROTATION_SCALE = 65536.0f / 360.0f;

	private EntityMoveCodec() {
	}

	public interface Visitor {
		/**
		 * {@code x/y/z} are absolute blocks under {@code FLAG_POS_ABSOLUTE}, a delta in blocks under
		 * {@code FLAG_POS_DELTA} and zero under neither. The rotations are meaningful only under
		 * {@code FLAG_ROTATION}.
		 */
		void entity(int id, int flags, double x, double y, double z, float yaw, float pitch, float headYaw);
	}

	public static void decode(byte[] payload, Visitor visitor) throws IOException {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
		int count = ActionCodec.readVarInt(in);

		for (int index = 0; index < count; index++) {
			int id = ActionCodec.readVarInt(in);
			int flags = in.readUnsignedByte();

			double x = 0.0;
			double y = 0.0;
			double z = 0.0;
			if ((flags & FLAG_POS_DELTA) != 0) {
				x = in.readShort() / POS_SCALE;
				y = in.readShort() / POS_SCALE;
				z = in.readShort() / POS_SCALE;
			} else if ((flags & FLAG_POS_ABSOLUTE) != 0) {
				x = in.readDouble();
				y = in.readDouble();
				z = in.readDouble();
			}

			float yaw = 0.0f;
			float pitch = 0.0f;
			float headYaw = 0.0f;
			if ((flags & FLAG_ROTATION) != 0) {
				yaw = in.readShort() / ROTATION_SCALE;
				pitch = in.readShort() / ROTATION_SCALE;
				headYaw = in.readShort() / ROTATION_SCALE;
			}

			visitor.entity(id, flags, x, y, z, yaw, pitch, headYaw);
		}
	}
}
