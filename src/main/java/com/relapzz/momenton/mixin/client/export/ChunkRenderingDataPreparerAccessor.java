package com.relapzz.momenton.mixin.client.export;

import net.minecraft.client.render.ChunkRenderingDataPreparer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(ChunkRenderingDataPreparer.class)
public interface ChunkRenderingDataPreparerAccessor {
	@Accessor("terrainUpdateScheduled")
	boolean isTerrainUpdateScheduled();

	@Accessor("terrainUpdateFuture")
	Future<?> getTerrainUpdateFuture();

	@Accessor("needsUpdate")
	AtomicBoolean getNeedsUpdate();
}
