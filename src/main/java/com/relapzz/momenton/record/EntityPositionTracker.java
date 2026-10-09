package com.relapzz.momenton.record;

import com.relapzz.momenton.format.ActionCodec;
import com.relapzz.momenton.format.EntityMoveCodec;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Emits one {@code ENTITY_MOVE} action per tick covering every entity whose pose changed, the local
 * player included - the packet stream never carries the local player's own movement, so this track is
 * the only record of where the recorded player went.
 *
 * <p>Poses are compared against the last quantised value rather than a threshold, so nothing drifts
 * and unchanged entities cost nothing. Movement that fits in a short is written as a delta; a teleport
 * falls back to absolute doubles. See {@link EntityMoveCodec} for the layout.
 */
final class EntityPositionTracker {
	private final Map<Entity, Pose> known = new WeakHashMap<>();

	/** The payload for this tick, or null if nothing moved. */
	@Nullable
	byte[] capture(ClientWorld world) throws IOException {
		ByteArrayOutputStream body = new ByteArrayOutputStream(512);
		DataOutputStream out = new DataOutputStream(body);
		int count = 0;

		for (Entity entity : world.getEntities()) {
			if (writeIfMoved(out, entity)) {
				count++;
			}
		}

		if (count == 0) {
			return null;
		}

		ByteArrayOutputStream payload = new ByteArrayOutputStream(body.size() + 4);
		ActionCodec.writeVarInt(payload, count);
		body.writeTo(payload);
		return payload.toByteArray();
	}

	/**
	 * Forgets every entity, so the next capture writes absolute poses.
	 *
	 * <p>Called at a chunk boundary. Deltas are relative to the writer's own running state, so without
	 * this a chunk could only be decoded by reading every chunk before it - and seeking loads one
	 * chunk out of the middle.
	 */
	void reset() {
		known.clear();
	}

	private boolean writeIfMoved(DataOutputStream out, Entity entity) throws IOException {
		long x = Math.round(entity.getX() * EntityMoveCodec.POS_SCALE);
		long y = Math.round(entity.getY() * EntityMoveCodec.POS_SCALE);
		long z = Math.round(entity.getZ() * EntityMoveCodec.POS_SCALE);
		short yaw = quantiseAngle(entity.getYaw());
		short pitch = quantiseAngle(entity.getPitch());
		short headYaw = quantiseAngle(entity.getHeadYaw());

		Pose last = known.get(entity);
		boolean moved = last == null || last.x != x || last.y != y || last.z != z;
		boolean turned = last == null || last.yaw != yaw || last.pitch != pitch || last.headYaw != headYaw;
		if (!moved && !turned) {
			return false;
		}

		boolean shortDelta = moved && last != null
				&& fitsInShort(x - last.x) && fitsInShort(y - last.y) && fitsInShort(z - last.z);
		int flags = (moved ? (shortDelta ? EntityMoveCodec.FLAG_POS_DELTA : EntityMoveCodec.FLAG_POS_ABSOLUTE) : 0)
				| (turned ? EntityMoveCodec.FLAG_ROTATION : 0);

		ActionCodec.writeVarInt(out, entity.getId());
		out.writeByte(flags);
		if (shortDelta) {
			out.writeShort((short) (x - last.x));
			out.writeShort((short) (y - last.y));
			out.writeShort((short) (z - last.z));
		} else if (moved) {
			out.writeDouble(x / EntityMoveCodec.POS_SCALE);
			out.writeDouble(y / EntityMoveCodec.POS_SCALE);
			out.writeDouble(z / EntityMoveCodec.POS_SCALE);
		}
		if (turned) {
			out.writeShort(yaw);
			out.writeShort(pitch);
			out.writeShort(headYaw);
		}

		known.put(entity, new Pose(x, y, z, yaw, pitch, headYaw));
		return true;
	}

	private static boolean fitsInShort(long delta) {
		return delta >= Short.MIN_VALUE && delta <= Short.MAX_VALUE;
	}

	private static short quantiseAngle(float degrees) {
		return (short) Math.round(MathHelper.wrapDegrees(degrees) * EntityMoveCodec.ROTATION_SCALE);
	}

	private record Pose(long x, long y, long z, short yaw, short pitch, short headYaw) {
	}
}
