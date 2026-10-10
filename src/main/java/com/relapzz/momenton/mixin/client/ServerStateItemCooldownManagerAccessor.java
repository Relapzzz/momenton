package com.relapzz.momenton.mixin.client;

import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(ItemCooldownManager.class)
public interface ServerStateItemCooldownManagerAccessor {
	@Accessor("entries")
	Map<Identifier, ?> momenton$getEntries();

	@Accessor("tick")
	int momenton$getTick();
}
