package com.relapzz.momenton.mixin.client;

import com.relapzz.momenton.record.LocalPrediction;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientWorld.class)
public class ClientWorldMixin {
	@Inject(method = "syncWorldEvent(Lnet/minecraft/entity/Entity;ILnet/minecraft/util/math/BlockPos;I)V", at = @At("HEAD"))
	private void momenton$worldEvent(Entity source, int eventId, BlockPos pos, int data, CallbackInfo info) {
		LocalPrediction.worldEvent(source, eventId, pos, data);
	}

	@Inject(method = "playSound(Lnet/minecraft/entity/Entity;DDDLnet/minecraft/registry/entry/RegistryEntry;Lnet/minecraft/sound/SoundCategory;FFJ)V", at = @At("HEAD"))
	private void momenton$sound(Entity source, double x, double y, double z, RegistryEntry<SoundEvent> sound, SoundCategory category, float volume, float pitch, long seed, CallbackInfo info) {
		LocalPrediction.sound(source, x, y, z, sound, category, volume, pitch, seed);
	}

	@Inject(method = "playSoundFromEntity(Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/Entity;Lnet/minecraft/registry/entry/RegistryEntry;Lnet/minecraft/sound/SoundCategory;FFJ)V", at = @At("HEAD"))
	private void momenton$entitySound(Entity source, Entity entity, RegistryEntry<SoundEvent> sound, SoundCategory category, float volume, float pitch, long seed, CallbackInfo info) {
		LocalPrediction.entitySound(source, entity, sound, category, volume, pitch, seed);
	}

	@Inject(method = "setBlockBreakingInfo(ILnet/minecraft/util/math/BlockPos;I)V", at = @At("HEAD"))
	private void momenton$breakingProgress(int entityId, BlockPos pos, int progress, CallbackInfo info) {
		LocalPrediction.breakingProgress(entityId, pos, progress);
	}
}
