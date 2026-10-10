package com.relapzz.momenton.playback;

import com.mojang.authlib.GameProfile;
import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.format.ActionCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.network.ClientConfigurationNetworkHandler;
import net.minecraft.client.network.ClientConnectionState;
import net.minecraft.client.network.ClientDynamicRegistryType;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientChunkLoadProgress;
import net.minecraft.entity.Entity;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkLoadDistanceS2CPacket;
import net.minecraft.network.packet.s2c.play.CommonPlayerSpawnInfo;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.network.packet.s2c.play.SimulationDistanceS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.network.state.ConfigurationStates;
import net.minecraft.network.state.PlayStateFactories;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.ServerLinks;
import net.minecraft.util.math.ChunkPos;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A real {@link ClientConnection} with no socket behind it, so vanilla's own handlers do every bit of
 * world reconstruction.
 *
 * <p>Packets are fed inbound through an {@link EmbeddedChannel}, which means they take the same route
 * a live packet takes - {@code channelRead0}, listener dispatch, and the state transition that
 * {@code ReadyS2CPacket} performs on itself. Nothing here reimplements a vanilla handler, and nothing
 * here needs to: the recording is the same wire data the server sent.
 *
 * <p>Feed from the client thread. {@code NetworkThreadUtils.forceMainThread} lets a packet apply
 * immediately when it is already on that thread, so a tick's packets land in the order they were
 * recorded rather than being batched to some later frame.
 */
public final class OfflineConnection {
	private final MinecraftClient client;
	private final ClientConnection connection = new ClientConnection(NetworkSide.CLIENTBOUND);
	private final EmbeddedChannel channel = new EmbeddedChannel(connection);
	private final LongSet chunks = new LongOpenHashSet();

	private PacketCodec<ByteBuf, Packet<? super ClientPlayPacketListener>> play;

	public OfflineConnection(MinecraftClient client) {
		this.client = client;
		channel.attr(Momenton.PLAYBACK).set(Boolean.TRUE);
		connection.transitionInbound(ConfigurationStates.S2C,
				new ClientConfigurationNetworkHandler(client, connection, initialState(client)));
	}

	/**
	 * Replays {@code setup.bin}. The last packet in it is {@code ReadyS2CPacket}, whose handler builds
	 * the registry manager and swaps itself for a {@link ClientPlayNetworkHandler} - so on return
	 * there is a play listener, and the codec every recorded packet was encoded against.
	 */
	public void applySetup(byte[] setup) throws IOException {
		InputStream in = new ByteArrayInputStream(setup);
		while (in.available() > 0) {
			byte[] encoded = in.readNBytes(ActionCodec.readVarInt(in));
			channel.writeInbound(ConfigurationStates.S2C.codec().decode(Unpooled.wrappedBuffer(encoded)));
		}

		if (!(connection.getPacketListener() instanceof ClientPlayNetworkHandler handler)) {
			throw new IOException("The setup track ended without reaching the play phase");
		}
		play = PlayStateFactories.S2C.bind(RegistryByteBuf.makeFactory(handler.getRegistryManager())).codec();
	}

	/** Replays one recorded play packet. */
	public void apply(byte[] encoded) {
		feed(play.decode(Unpooled.wrappedBuffer(encoded)));
	}

	/** Replays a length-prefixed run of play packets, which is what a snapshot part holds. */
	public void applySnapshot(byte[] snapshot) throws IOException {
		List<Packet<? super ClientPlayPacketListener>> packets = new ArrayList<>();
		InputStream in = new ByteArrayInputStream(snapshot);
		while (in.available() > 0) {
			packets.add(play.decode(Unpooled.wrappedBuffer(in.readNBytes(ActionCodec.readVarInt(in)))));
		}
		GameJoinS2CPacket join = (GameJoinS2CPacket) packets.getFirst();
		if (client.world != null) {
			packets.removeFirst();
			reuseWorld(join, packets);
		}
		packets.forEach(this::feed);
		if (join.simulationDistance() == 0) {
			feed(new SimulationDistanceS2CPacket(Math.max(2, client.options.getSimulationDistance().getValue())));
		}
	}

	private void reuseWorld(GameJoinS2CPacket join, List<Packet<? super ClientPlayPacketListener>> snapshot) {
		CommonPlayerSpawnInfo spawn = join.commonPlayerSpawnInfo();
		if (spawn.dimension() == client.world.getRegistryKey()) {
			clearWorld(spawn, snapshot);
		} else {
			feed(new PlayerRespawnS2CPacket(spawn, PlayerRespawnS2CPacket.KEEP_ALL));
		}
		feed(new ChunkLoadDistanceS2CPacket(join.viewDistance()));
	}

	private void clearWorld(CommonPlayerSpawnInfo spawn, List<Packet<? super ClientPlayPacketListener>> snapshot) {
		LongSet stale = new LongOpenHashSet(chunks);
		for (Packet<?> packet : snapshot) {
			if (packet instanceof ChunkDataS2CPacket chunk) {
				stale.remove(ChunkPos.toLong(chunk.getChunkX(), chunk.getChunkZ()));
			}
		}
		for (long pos : stale) {
			feed(new UnloadChunkS2CPacket(new ChunkPos(pos)));
		}

		IntList entities = new IntArrayList();
		for (Entity entity : client.world.getEntities()) {
			if (entity != client.player) {
				entities.add(entity.getId());
			}
		}
		feed(new EntitiesDestroyS2CPacket(entities));

		client.getSoundManager().stopAll();
		client.particleManager.clearParticles();
		client.interactionManager.setGameModes(spawn.gameMode(), spawn.lastGameMode());
		if (client.currentScreen instanceof DeathScreen) {
			client.setScreen(null);
		}
	}

	private void feed(Packet<? super ClientPlayPacketListener> packet) {
		if (packet instanceof ChunkDataS2CPacket chunk) {
			chunks.add(ChunkPos.toLong(chunk.getChunkX(), chunk.getChunkZ()));
		} else if (packet instanceof UnloadChunkS2CPacket unload) {
			chunks.remove(unload.pos().toLong());
		}
		channel.writeInbound(packet);
	}

	/**
	 * Drops whatever the replayed handlers tried to send. A replayed {@code ClientPlayNetworkHandler}
	 * still answers keep-alives and reports the player's movement; there is nothing on the other end,
	 * so the outbound queue would only grow.
	 *
	 * <p>The connection itself is not ticked here. Once the join packet has been applied the client
	 * owns a {@code ClientPlayerInteractionManager} bound to this handler, and its own tick already
	 * calls {@code getConnection().tick()} - doing it again would tick every packet listener twice.
	 */
	public void drainOutbound() {
		channel.outboundMessages().clear();
	}

	/**
	 * False once anything has closed the channel, which is not only {@link #close}: the pause menu's
	 * Disconnect goes through {@code ClientWorld.disconnect}, which disconnects this connection like
	 * any other.
	 */
	public boolean isOpen() {
		return channel.isOpen();
	}

	public void close() {
		channel.close();
	}

	/**
	 * What the client hands a fresh configuration handler at login, minus the server: base registries
	 * that {@code setup.bin} is about to replace, vanilla features, and no server identity.
	 */
	private static ClientConnectionState initialState(MinecraftClient client) {
		GameProfile profile = client.getGameProfile();
		return new ClientConnectionState(
				new ClientChunkLoadProgress(),
				profile,
				client.getTelemetryManager().createWorldSession(false, null, null),
				ClientDynamicRegistryType.createCombinedDynamicRegistries().getCombinedRegistryManager(),
				FeatureFlags.DEFAULT_ENABLED_FEATURES,
				null,
				null,
				null,
				Map.of(),
				null,
				Map.of(),
				ServerLinks.EMPTY,
				Map.of(),
				false);
	}
}
