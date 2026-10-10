package com.relapzz.momenton.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.entity.player.ItemCooldownManager$Entry")
public interface ServerStateCooldownEntryAccessor {
	@Accessor("endTick")
	int momenton$getEndTick();
}
