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
	private void momenton$driveRecordedTailState(CallbackInfo info) {
		ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
		LocalPlayerDriver.driveSprint(player);
		LocalPlayerDriver.driveOverlays(player);
	}
}
