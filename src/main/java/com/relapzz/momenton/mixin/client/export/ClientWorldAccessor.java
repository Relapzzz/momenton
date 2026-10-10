package com.relapzz.momenton.mixin.client.export;

import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Deque;

@Mixin(ClientWorld.class)
public interface ClientWorldAccessor {
	@Accessor("chunkUpdaters")
	Deque<Runnable> getChunkUpdaters();
}
