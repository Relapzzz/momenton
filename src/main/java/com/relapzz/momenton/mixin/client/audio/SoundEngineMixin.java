package com.relapzz.momenton.mixin.client.audio;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.relapzz.momenton.audio.LoopbackRenderer;
import net.minecraft.client.sound.SoundEngine;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.IntBuffer;

@Mixin(SoundEngine.class)
public class SoundEngineMixin {
	@WrapOperation(method = "init", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/sound/SoundEngine;openDeviceOrFallback(Ljava/lang/String;)J"))
	private long momenton$openLoopback(String deviceSpecifier, Operation<Long> original) {
		return LoopbackRenderer.isActive() ? LoopbackRenderer.openDevice() : original.call(deviceSpecifier);
	}

	@Inject(method = "createAttributes", at = @At("HEAD"), cancellable = true)
	private void momenton$loopbackAttributes(MemoryStack stack, boolean hrtf, CallbackInfoReturnable<IntBuffer> info) {
		if (LoopbackRenderer.isActive()) {
			info.setReturnValue(LoopbackRenderer.attributes(stack, hrtf));
		}
	}
}
