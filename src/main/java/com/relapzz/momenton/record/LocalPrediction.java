package com.relapzz.momenton.record;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldEventS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ThreadLocalRandom;

public final class LocalPrediction {
	private static int applyingPackets;

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

	private static boolean predicted(Entity source) {
		return capturing() && source == MinecraftClient.getInstance().player;
	}

	private static boolean capturing() {
		return applyingPackets == 0 && Recorder.isRecording();
	}
}
