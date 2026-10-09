package com.relapzz.momenton.mixin.client;

import com.relapzz.momenton.record.LocalPrediction;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.sound.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class ClientPlayerSoundMixin {
	@Inject(method = "playSound(Lnet/minecraft/sound/SoundEvent;FF)V", at = @At("HEAD"))
	private void momenton$sound(SoundEvent sound, float volume, float pitch, CallbackInfo info) {
		ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
		LocalPrediction.sound(player, player.getX(), player.getY(), player.getZ(), sound, player.getSoundCategory(), volume, pitch);
	}
}
