package com.relapzz.momenton.mixin.client;

import com.relapzz.momenton.record.WorldSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldChunk.class)
public class SnapshotDirtyWorldChunkMixin {
	@Shadow
	@Final
	World world;

	@Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Lnet/minecraft/block/BlockState;", at = @At("HEAD"))
	private void momenton$blockChanged(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> info) {
		if (world.isClient()) {
			WorldSnapshot.markDirtyAround(ChunkSectionPos.getSectionCoord(pos.getX()), ChunkSectionPos.getSectionCoord(pos.getZ()));
		}
	}
}
