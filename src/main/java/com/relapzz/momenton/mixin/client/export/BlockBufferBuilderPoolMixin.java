package com.relapzz.momenton.mixin.client.export;

import com.relapzz.momenton.export.ChunkBufferPool;
import net.minecraft.client.render.chunk.BlockBufferAllocatorStorage;
import net.minecraft.client.render.chunk.BlockBufferBuilderPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(BlockBufferBuilderPool.class)
public class BlockBufferBuilderPoolMixin implements ChunkBufferPool {
	@Unique
	private int capacity;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void momenton$captureCapacity(List<BlockBufferAllocatorStorage> builders, CallbackInfo info) {
		capacity = builders.size();
	}

	@Override
	public int momenton$capacity() {
		return capacity;
	}
}
