package com.relapzz.momenton.record;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.UnknownCustomPayload;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.common.ResourcePackRemoveS2CPacket;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/**
 * Decides what goes into the recording, and rewrites what would not survive replay.
 *
 * <p>Rules learned from ReplayMod's {@code PacketListener} and Flashback's documented behaviour.
 * Bundles are expanded by {@link Recorder} before packets reach here, so tick boundaries stay
 * meaningful.
 *
 * <p>{@code PlayerPositionLookS2CPacket} used to be dropped here, on the theory that a replayed
 * teleport would fight the camera. It cannot - the camera comes from {@code frames.bin} and is
 * written at TAIL of {@code Camera.update}, after everything else. Dropping it instead broke every
 * dimension change: the server places the player in the new world with exactly that packet, and
 * without it the replayed player keeps its old coordinates while
 * {@code ClientChunkLoadProgress.LoadChunks} waits on {@code isRenderingReady(player.getBlockPos())}
 * for chunks that will never arrive there. The terrain screen then holds for its full 30-second
 * timeout while the replay runs on behind it.
 */
final class PacketFilter {
	private PacketFilter() {
	}

	/** The packet to record, or null to drop it. */
	@Nullable
	static Packet<?> filter(Packet<?> packet) {
		// A payload for a channel no mod claimed decodes to UnknownCustomPayload, which keeps the
		// id and discards the body - re-encoding it would produce a packet the replay cannot read.
		if (packet instanceof CustomPayloadS2CPacket custom && custom.payload() instanceof UnknownCustomPayload) {
			return null;
		}

		if (packet instanceof ResourcePackSendS2CPacket || packet instanceof ResourcePackRemoveS2CPacket) {
			return null;
		}

		// Chat signatures are bound to the session that received them and will not validate on
		// replay, so the decorated line is kept as a system message instead.
		if (packet instanceof ChatMessageS2CPacket chat) {
			Text content = chat.unsignedContent() != null ? chat.unsignedContent() : Text.literal(chat.body().content());
			return new GameMessageS2CPacket(chat.serializedParameters().applyChatDecoration(content), false);
		}

		return packet;
	}
}
