package com.relapzz.momenton.record;

import com.mojang.datafixers.util.Pair;
import com.relapzz.momenton.mixin.client.PlayerListS2CPacketAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPosition;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.Leashable;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkRenderDistanceCenterS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAttachS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAttributesS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusEffectS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The world as it stands the moment recording starts, expressed as the packets a server would have
 * sent to produce it.
 *
 * <p>Recording begins mid-session, so every chunk and entity already loaded was sent before F9 and is
 * never sent again. Without this a replay joins an empty world and only fills in where the player
 * happens to walk - and if they walk nowhere, nothing arrives at all: no chunks, and no
 * {@code INITIAL_CHUNKS_COMING}, which leaves the loading screen up forever because
 * {@code ClientChunkLoadProgress.Start} has no timeout of its own.
 *
 * <p>Packets rather than a bespoke format because the replay already carries packets, decodes them
 * with the same codec, and applies them through the same vanilla handlers. There is nothing to write
 * on the playback side at all.
 */
final class WorldSnapshot {
	private WorldSnapshot() {
	}

	/** Every packet needed to rebuild the current world, in the order a fresh join would send them. */
	static List<Packet<?>> capture(MinecraftClient client) {
		ClientWorld world = client.world;
		ClientPlayerEntity player = client.player;
		List<Packet<?>> packets = new ArrayList<>();

		// The loading screen advances out of its first state on this and nothing else.
		packets.add(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.INITIAL_CHUNKS_COMING, 0.0f));
		// Whether time advances is private to ClientWorld with no getter, so assume it does: the
		// recorded SET_TIME packets arrive about once a second and correct it either way.
		packets.add(new WorldTimeUpdateS2CPacket(world.getTime(), world.getTimeOfDay(), true));
		packets.add(PlayerPositionLookS2CPacket.of(0, EntityPosition.fromEntity(player), Set.of()));

		// Must precede the chunks. ClientChunkManager keeps a fixed-size map centred on this, and
		// rejects anything outside it with "Ignoring chunk since it's not in the view range" - which
		// is every chunk in the snapshot, because the map starts centred on 0,0.
		ChunkPos playerChunk = player.getChunkPos();
		packets.add(new ChunkRenderDistanceCenterS2CPacket(playerChunk.x, playerChunk.z));

		// The player's own items are the same class of gap as the chunks: they were put there by
		// CONTAINER_SET_CONTENT and CONTAINER_SET_SLOT packets that arrived long before F9 and are
		// never sent again, so without this a replay renders an empty hotbar and empty hands. Sync id
		// 0 is the player's own screen handler, which is what ClientPlayNetworkHandler.onInventory
		// applies to.
		packets.add(new InventoryS2CPacket(0, player.playerScreenHandler.getRevision(),
				List.copyOf(player.playerScreenHandler.getStacks()), player.currentScreenHandler.getCursorStack()));

		addChunks(client, world, player, packets);
		packets.add(playerList(client.getNetworkHandler()));
		ServerStateSnapshot.capture(client, packets);
		addEntities(world, player, packets);
		return packets;
	}

	private static PlayerListS2CPacket playerList(ClientPlayNetworkHandler handler) {
		Collection<PlayerListEntry> listed = handler.getListedPlayerListEntries();
		PlayerListS2CPacket packet = new PlayerListS2CPacket(EnumSet.of(
				PlayerListS2CPacket.Action.ADD_PLAYER,
				PlayerListS2CPacket.Action.UPDATE_GAME_MODE,
				PlayerListS2CPacket.Action.UPDATE_LISTED,
				PlayerListS2CPacket.Action.UPDATE_LATENCY,
				PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME,
				PlayerListS2CPacket.Action.UPDATE_LIST_ORDER,
				PlayerListS2CPacket.Action.UPDATE_HAT), List.of());
		((PlayerListS2CPacketAccessor) packet).setEntries(handler.getPlayerList().stream()
				.map(entry -> new PlayerListS2CPacket.Entry(
						entry.getProfile().id(),
						entry.getProfile(),
						listed.contains(entry),
						entry.getLatency(),
						entry.getGameMode(),
						entry.getDisplayName(),
						entry.shouldShowHat(),
						entry.getListOrder(),
						null))
				.toList());
		return packet;
	}

	/**
	 * Every chunk the client is holding, nearest first so a seek fills in the view before the horizon.
	 *
	 * <p>The radius is the client's chunk map radius, not its render distance, and the difference is a
	 * bug that took a superflat world to notice. A server sends chunks out to {@code viewDistance + 1}
	 * - measured, not assumed: on a render distance of 5, 59 of 69 streamed chunks arrived at
	 * Chebyshev distance 6 - and {@code ClientChunkManager.getChunkMapRadius} keeps
	 * {@code max(2, viewDistance) + 3}. Scanning only the render distance therefore left the outer
	 * ring out of every snapshot, 48 chunks of the 169 the client actually had. Nothing re-sends them,
	 * because the server sent them before F9, so a replay had a permanent hole at the edge of view
	 * that appeared whenever the camera turned towards it. Chunks that are not loaded come back null
	 * and cost nothing, so the wider scan only ever captures what is really there.
	 *
	 * <p>Serialising a chunk is the expensive half of a snapshot and there is one snapshot per chunk
	 * boundary, so this runs in parallel - but the client thread blocks on it rather than handing the
	 * work off. That is deliberate: the world cannot change while the thread that owns it is waiting,
	 * so parallel readers need no synchronisation, and the caller still gets a consistent snapshot.
	 */
	private static void addChunks(MinecraftClient client, ClientWorld world, ClientPlayerEntity player, List<Packet<?>> packets) {
		int radius = Math.max(2, client.options.getClampedViewDistance()) + 3;
		ChunkPos playerChunk = player.getChunkPos();
		List<WorldChunk> loaded = new ArrayList<>();
		for (int x = playerChunk.x - radius; x <= playerChunk.x + radius; x++) {
			for (int z = playerChunk.z - radius; z <= playerChunk.z + radius; z++) {
				WorldChunk chunk = world.getChunkManager().getChunk(x, z, ChunkStatus.FULL, false);
				if (chunk != null) {
					loaded.add(chunk);
				}
			}
		}

		loaded.sort(Comparator.comparingLong(chunk -> playerChunk.getSquaredDistance(chunk.getPos())));
		LightingProvider light = world.getChunkManager().getLightingProvider();
		packets.addAll(loaded.parallelStream()
				.map(chunk -> (Packet<?>) new ChunkDataS2CPacket(chunk, light, null, null))
				.toList());
	}

	/**
	 * Spawns every tracked entity and follows each with its tracked data, attributes and equipment, the
	 * way vanilla's own pairing bundle does, then links passengers and leashes once every entity exists.
	 * The local player gets no spawn or equipment - the join packet created it and the inventory packet
	 * dressed it - but does get its tracked data, attributes and status effects.
	 */
	private static void addEntities(ClientWorld world, ClientPlayerEntity player, List<Packet<?>> packets) {
		for (Entity entity : world.getEntities()) {
			boolean local = entity.getId() == player.getId();
			if (!local) {
				packets.add(new EntitySpawnS2CPacket(
						entity.getId(),
						entity.getUuid(),
						entity.getX(),
						entity.getY(),
						entity.getZ(),
						entity.getPitch(),
						entity.getYaw(),
						entity.getType(),
						0,
						entity.getVelocity(),
						entity.getHeadYaw()));
			}

			List<DataTracker.SerializedEntry<?>> tracked = entity.getDataTracker().getChangedEntries();
			if (tracked != null) {
				packets.add(new EntityTrackerUpdateS2CPacket(entity.getId(), tracked));
			}

			if (entity instanceof LivingEntity living) {
				Collection<EntityAttributeInstance> attributes = living.getAttributes().getAttributesToSend();
				if (!attributes.isEmpty()) {
					packets.add(new EntityAttributesS2CPacket(entity.getId(), attributes));
				}

				if (!local) {
					List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
					for (EquipmentSlot slot : EquipmentSlot.values()) {
						ItemStack stack = living.getEquippedStack(slot);
						if (!stack.isEmpty()) {
							equipment.add(Pair.of(slot, stack.copy()));
						}
					}
					if (!equipment.isEmpty()) {
						packets.add(new EntityEquipmentUpdateS2CPacket(entity.getId(), equipment));
					}
				}
			}
		}

		for (StatusEffectInstance effect : player.getStatusEffects()) {
			packets.add(new EntityStatusEffectS2CPacket(player.getId(), effect, false));
		}

		for (Entity entity : world.getEntities()) {
			if (entity.hasPassengers()) {
				packets.add(new EntityPassengersSetS2CPacket(entity));
			}
			if (entity instanceof Leashable leashable && leashable.getLeashHolder() != null) {
				packets.add(new EntityAttachS2CPacket(entity, leashable.getLeashHolder()));
			}
		}
	}
}
