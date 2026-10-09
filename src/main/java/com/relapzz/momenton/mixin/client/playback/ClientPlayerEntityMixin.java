package com.relapzz.momenton.mixin.client.playback;

import com.relapzz.momenton.playback.LocalPlayerDriver;
import com.relapzz.momenton.playback.UiStateDriver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the recorded state on the replayed player before vanilla's tick reads it.
 *
 * <p>HEAD is the point that matters, not a convenience: {@code ClientWorld.tickEntity} has just called
 * {@code resetPosition}, and {@code LivingEntity.tick} is about to derive body yaw and the limb
 * animation from {@code getX() - lastX}. Setting the pose here makes that difference the recorded
 * movement, so the walk animation and the body turning come from vanilla.
 *
 * <p>One injection driving both, in order, rather than two at the same point: the attack cooldown
 * ramps at a rate that depends on the held item, and the hotbar is what
 * {@link LocalPlayerDriver} restores. Two separate HEAD injections would leave that order to chance.
 */
@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void momenton$driveRecordedState(CallbackInfo info) {
		ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
		LocalPlayerDriver.drive(player);
		UiStateDriver.drive(MinecraftClient.getInstance(), player);
	}
}
