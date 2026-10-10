package com.relapzz.momenton.mixin.client;

import com.relapzz.momenton.record.LocalPrediction;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientWorld.class)
public class PredictionClientWorldMixin {
	@Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z", at = @At("RETURN"))
	private void momenton$blockChanged(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> info) {
		if (info.getReturnValueZ()) {
			LocalPrediction.blockChanged((ClientWorld) (Object) this, pos);
		}
	}
}
