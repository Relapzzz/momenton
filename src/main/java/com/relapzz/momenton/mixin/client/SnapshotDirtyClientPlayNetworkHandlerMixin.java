package com.relapzz.momenton.mixin.client;

import com.relapzz.momenton.record.WorldSnapshot;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.util.math.ChunkSectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class SnapshotDirtyClientPlayNetworkHandlerMixin {
	@Inject(method = "onBlockEntityUpdate(Lnet/minecraft/network/packet/s2c/play/BlockEntityUpdateS2CPacket;)V", at = @At("RETURN"))
	private void momenton$blockEntityUpdated(BlockEntityUpdateS2CPacket packet, CallbackInfo info) {
		WorldSnapshot.markDirty(ChunkSectionPos.getSectionCoord(packet.getPos().getX()), ChunkSectionPos.getSectionCoord(packet.getPos().getZ()));
	}

	@Inject(method = "onLightUpdate(Lnet/minecraft/network/packet/s2c/play/LightUpdateS2CPacket;)V", at = @At("RETURN"))
	private void momenton$lightUpdated(LightUpdateS2CPacket packet, CallbackInfo info) {
		WorldSnapshot.markDirty(packet.getChunkX(), packet.getChunkZ());
	}
}
