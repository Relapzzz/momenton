package com.relapzz.momenton.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.relapzz.momenton.record.LocalPrediction;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {
	@Shadow
	@Final
	private MinecraftClient client;

	@WrapOperation(method = "updateBlockBreakingProgress", at = @At(value = "NEW", target = "(Lnet/minecraft/sound/SoundEvent;Lnet/minecraft/sound/SoundCategory;FFLnet/minecraft/util/math/random/Random;Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/client/sound/PositionedSoundInstance;"))
	private PositionedSoundInstance momenton$hitSound(SoundEvent sound, SoundCategory category, float volume, float pitch, Random random, BlockPos pos, Operation<PositionedSoundInstance> original) {
		LocalPrediction.sound(client.player, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, sound, category, volume, pitch);
		return original.call(sound, category, volume, pitch, random, pos);
	}
}
