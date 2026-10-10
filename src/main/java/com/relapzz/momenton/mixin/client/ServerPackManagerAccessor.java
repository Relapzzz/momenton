package com.relapzz.momenton.mixin.client;

import net.minecraft.client.resource.server.ServerResourcePackManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ServerResourcePackManager.class)
public interface ServerPackManagerAccessor {
	@Accessor("packs")
	List<?> momenton$getPacks();
}
