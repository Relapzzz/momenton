package com.relapzz.momenton.mixin.client.playback;

import com.relapzz.momenton.playback.LocalPlayerDriver;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityTailMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void momenton$driveRecordedOverlays(CallbackInfo info) {
		LocalPlayerDriver.driveOverlays((ClientPlayerEntity) (Object) this);
	}
}
