package com.relapzz.momenton.mixin.client;

import com.relapzz.momenton.record.WorldSnapshot;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.light.LightStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightStorage.class)
public abstract class SnapshotDirtyLightStorageMixin {
	@Shadow
	@Final
	protected ChunkProvider chunkProvider;

	@Shadow
	@Final
	protected LongSet dirtySections;

	@Inject(method = "enqueueSectionData(JLnet/minecraft/world/chunk/ChunkNibbleArray;)V", at = @At("HEAD"))
	private void momenton$lightReceived(long sectionPos, ChunkNibbleArray array, CallbackInfo info) {
		if (chunkProvider instanceof ClientChunkManager) {
			momenton$markSection(sectionPos);
		}
	}

	@Inject(method = "notifyChanges()V", at = @At("HEAD"))
	private void momenton$lightChanged(CallbackInfo info) {
		if (chunkProvider instanceof ClientChunkManager) {
			LongIterator sections = dirtySections.iterator();
			while (sections.hasNext()) {
				momenton$markSection(sections.nextLong());
			}
		}
	}

	private void momenton$markSection(long sectionPos) {
		WorldSnapshot.markDirty(ChunkPos.toLong(ChunkSectionPos.unpackX(sectionPos), ChunkSectionPos.unpackZ(sectionPos)));
	}
}
