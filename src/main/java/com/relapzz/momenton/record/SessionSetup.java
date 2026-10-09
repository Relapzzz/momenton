package com.relapzz.momenton.record;

import com.relapzz.momenton.format.ActionCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.listener.ClientConfigurationPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.common.SynchronizeTagsS2CPacket;
import net.minecraft.network.packet.s2c.config.DynamicRegistriesS2CPacket;
import net.minecraft.network.packet.s2c.config.FeaturesS2CPacket;
import net.minecraft.network.packet.s2c.config.ReadyS2CPacket;
import net.minecraft.network.packet.s2c.config.SelectKnownPacksS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.state.ConfigurationStates;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The session-setup packets, buffered from the moment a connection configures rather than from F9.
 *
 * <p>A recording started mid-session is missing everything that arrived before it: the registry
 * manager its own packets were encoded against, and the {@link GameJoinS2CPacket} that builds the
 * world. Neither can be recovered afterwards - the client keeps the registry manager it built, not
 * the data it was built from - so both are held here for the life of the connection.
 *
 * <p>Configuration packets carry no registry-dependent fields, so {@link ConfigurationStates#S2C} is
 * bound once statically and encodes them without a registry manager. The game join does not travel
 * here; it is written as the first action of tick 0 through the play codec, like every other packet.
 */
public final class SessionSetup {
	private static final List<Packet<?>> configuration = new ArrayList<>();
	private static boolean configuring;
	private static GameJoinS2CPacket join;

	private SessionSetup() {
	}

	/** Netty thread. Every clientbound packet passes through here, recording or not. */
	static synchronized void accept(Packet<?> packet) {
		// Features is sent before the registry sync task even starts, so any of these three can be
		// the first packet of a sequence. A server can also send the client back to configuration
		// mid-session, and the registries it then sends replace the ones before, so a fresh sequence
		// starts a fresh buffer.
		if (packet instanceof FeaturesS2CPacket
				|| packet instanceof SelectKnownPacksS2CPacket
				|| packet instanceof DynamicRegistriesS2CPacket) {
			if (!configuring) {
				configuration.clear();
				join = null;
				configuring = true;
			}
			configuration.add(packet);
		} else if (configuring && packet instanceof SynchronizeTagsS2CPacket) {
			// Tags are a common packet: servers also resend them during play, where they are
			// ordinary traffic rather than setup. Only those inside a sequence belong here.
			configuration.add(packet);
		} else if (configuring && packet instanceof ReadyS2CPacket) {
			configuration.add(packet);
			configuring = false;
		} else if (packet instanceof GameJoinS2CPacket game) {
			join = game;
		}
	}

	/** The join packet this session started from, or null if none has arrived yet. */
	@Nullable
	static synchronized GameJoinS2CPacket gameJoin() {
		return join;
	}

	/** The contents of {@code setup.bin}: each configuration packet as {@code [varint length][bytes]}. */
	@SuppressWarnings("unchecked")
	static synchronized byte[] encode() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
		ByteBuf buf = Unpooled.buffer(1 << 16);
		for (Packet<?> packet : configuration) {
			buf.clear();
			ConfigurationStates.S2C.codec().encode(buf, (Packet<? super ClientConfigurationPacketListener>) packet);
			ActionCodec.writeVarInt(out, buf.readableBytes());
			buf.readBytes(out, buf.readableBytes());
		}
		return out.toByteArray();
	}
}
