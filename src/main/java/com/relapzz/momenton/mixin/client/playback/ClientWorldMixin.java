package com.relapzz.momenton.mixin.client.playback;

import com.relapzz.momenton.playback.LocalPlayerDriver;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientWorld.class)
public class ClientWorldMixin {
	@Inject(method = "tickEntity(Lnet/minecraft/entity/Entity;)V", at = {
			@At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;tick()V"),
			@At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;tick()V", shift = At.Shift.AFTER)})
	private void momenton$driveRecordedVehicle(Entity entity, CallbackInfo info) {
		LocalPlayerDriver.driveVehicle(entity);
	}
}
