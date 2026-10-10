package com.relapzz.momenton.record;

import com.google.common.collect.Iterables;
import com.relapzz.momenton.mixin.client.PredictionEntityLookupMixin;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldEventS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class LocalPrediction {
	private static int applyingPackets;
	private static IntSet hidden = new IntOpenHashSet();
	private static IntSet stillHidden = new IntOpenHashSet();

	private LocalPrediction() {
	}

	public static void enterPacket() {
		applyingPackets++;
	}

	public static void exitPacket() {
		applyingPackets--;
	}

	public static void worldEvent(Entity source, int eventId, BlockPos pos, int data) {
		if (predicted(source)) {
			Recorder.synthesise(new WorldEventS2CPacket(eventId, pos, data, false));
		}
	}

	public static void sound(Entity source, double x, double y, double z, RegistryEntry<SoundEvent> sound, SoundCategory category, float volume, float pitch, long seed) {
		if (predicted(source)) {
			Recorder.synthesise(new PlaySoundS2CPacket(sound, category, x, y, z, volume, pitch, seed));
		}
	}

	public static void sound(Entity source, double x, double y, double z, SoundEvent sound, SoundCategory category, float volume, float pitch) {
		sound(source, x, y, z, Registries.SOUND_EVENT.getEntry(sound), category, volume, pitch, ThreadLocalRandom.current().nextLong());
	}

	public static void entitySound(Entity source, Entity entity, RegistryEntry<SoundEvent> sound, SoundCategory category, float volume, float pitch, long seed) {
		if (predicted(source)) {
			Recorder.synthesise(new PlaySoundFromEntityS2CPacket(sound, category, entity, volume, pitch, seed));
		}
	}

	public static void breakingProgress(int entityId, BlockPos pos, int progress) {
		if (capturing() && entityId == MinecraftClient.getInstance().player.getId()) {
			Recorder.synthesise(new BlockBreakingProgressS2CPacket(entityId, pos, progress));
		}
	}

	public static void blockChanged(ClientWorld world, BlockPos pos) {
		if (capturing()) {
			Recorder.synthesise(new BlockUpdateS2CPacket(world, pos));
		}
	}

	public static void hiddenEntities(ClientWorld world, int tick) {
		if (tick == 0) {
			hidden.clear();
		}
		stillHidden.clear();
		int visible = Iterables.size(world.getEntities());
		if (visible != world.getRegularEntityCount()) {
			IntSet shown = new IntOpenHashSet(visible);
			for (Entity entity : world.getEntities()) {
				shown.add(entity.getId());
			}
			for (Entity entity : ((PredictionEntityLookupMixin) world).callGetEntityLookup().iterate()) {
				int id = entity.getId();
				if (!shown.contains(id)) {
					stillHidden.add(id);
					if (!hidden.contains(id)) {
						Recorder.synthesise(new EntitiesDestroyS2CPacket(id));
					}
				}
			}
		}
		for (int id : hidden) {
			Entity entity = world.getEntityById(id);
			if (entity != null && !stillHidden.contains(id)) {
				reveal(entity);
			}
		}
		IntSet swap = hidden;
		hidden = stillHidden;
		stillHidden = swap;
	}

	private static void reveal(Entity entity) {
		Recorder.synthesise(new EntitySpawnS2CPacket(entity.getId(), entity.getUuid(), entity.getX(), entity.getY(), entity.getZ(),
				entity.getPitch(), entity.getYaw(), entity.getType(), 0, entity.getVelocity(), entity.getHeadYaw()));
		List<DataTracker.SerializedEntry<?>> data = entity.getDataTracker().getChangedEntries();
		if (data != null) {
			Recorder.synthesise(new EntityTrackerUpdateS2CPacket(entity.getId(), data));
		}
	}

	private static boolean predicted(Entity source) {
		return capturing() && source == MinecraftClient.getInstance().player;
	}

	private static boolean capturing() {
		return applyingPackets == 0 && Recorder.isRecording();
	}
}
