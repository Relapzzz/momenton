package com.relapzz.momenton.mixin.client;

import com.relapzz.momenton.record.WorldSnapshot;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Consumer;

@Mixin(ClientChunkManager.class)
public class SnapshotDirtyClientChunkManagerMixin {
	@Inject(method = "loadChunkFromPacket(IILnet/minecraft/network/PacketByteBuf;Ljava/util/Map;Ljava/util/function/Consumer;)Lnet/minecraft/world/chunk/WorldChunk;", at = @At("HEAD"))
	private void momenton$chunkLoaded(int x, int z, PacketByteBuf buf, Map<Heightmap.Type, long[]> heightmaps, Consumer<ChunkData.BlockEntityVisitor> blockEntities, CallbackInfoReturnable<WorldChunk> info) {
		WorldSnapshot.markDirty(ChunkPos.toLong(x, z));
	}

	@Inject(method = "onChunkBiomeData(IILnet/minecraft/network/PacketByteBuf;)V", at = @At("HEAD"))
	private void momenton$biomesChanged(int x, int z, PacketByteBuf buf, CallbackInfo info) {
		WorldSnapshot.markDirty(ChunkPos.toLong(x, z));
	}
}
