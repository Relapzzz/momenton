package com.relapzz.momenton.mixin.client.audio;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.relapzz.momenton.audio.LoopbackRenderer;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(SoundSystem.class)
public class SoundSystemMixin {
	@ModifyExpressionValue(method = "play(Lnet/minecraft/client/sound/SoundInstance;)Lnet/minecraft/client/sound/SoundSystem$PlayResult;", at = {
			@At(value = "INVOKE", target = "Lnet/minecraft/client/sound/SoundLoader;loadStatic(Lnet/minecraft/util/Identifier;)Ljava/util/concurrent/CompletableFuture;"),
			@At(value = "INVOKE", target = "Lnet/minecraft/client/sound/SoundLoader;loadStreamed(Lnet/minecraft/util/Identifier;Z)Ljava/util/concurrent/CompletableFuture;")})
	private CompletableFuture<?> momenton$loadBeforePlaying(CompletableFuture<?> load) {
		if (LoopbackRenderer.isActive()) {
			load.exceptionally(error -> null).join();
		}
		return load;
	}

	@Inject(method = "shouldReloadSounds", at = @At("HEAD"), cancellable = true)
	private void momenton$keepLoopback(CallbackInfoReturnable<Boolean> info) {
		if (LoopbackRenderer.isActive()) {
			info.setReturnValue(false);
		}
	}
}
