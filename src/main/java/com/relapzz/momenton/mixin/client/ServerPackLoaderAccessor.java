package com.relapzz.momenton.mixin.client;

import net.minecraft.client.resource.server.ServerResourcePackLoader;
import net.minecraft.client.resource.server.ServerResourcePackManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerResourcePackLoader.class)
public interface ServerPackLoaderAccessor {
	@Accessor("manager")
	ServerResourcePackManager momenton$getManager();
}
