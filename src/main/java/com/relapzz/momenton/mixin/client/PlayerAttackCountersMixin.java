package com.relapzz.momenton.mixin.client;

import com.relapzz.momenton.format.UiStateCodec;
import com.relapzz.momenton.record.UiStateTracker;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntity.class)
public class PlayerAttackCountersMixin {
	@Inject(method = "resetTicksSince", at = @At("HEAD"))
	private void momenton$attackAndEquipReset(CallbackInfo info) {
		UiStateTracker.countersReset((PlayerEntity) (Object) this, UiStateCodec.RESET_ATTACK_AND_EQUIP);
	}

	@Inject(method = "resetTicksSinceLastAttack", at = @At("HEAD"))
	private void momenton$attackReset(CallbackInfo info) {
		UiStateTracker.countersReset((PlayerEntity) (Object) this, UiStateCodec.RESET_ATTACK);
	}
}
