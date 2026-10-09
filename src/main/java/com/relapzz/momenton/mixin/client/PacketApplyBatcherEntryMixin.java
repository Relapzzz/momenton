package com.relapzz.momenton.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.relapzz.momenton.record.LocalPrediction;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.listener.PacketListener;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.network.PacketApplyBatcher$Entry")
public class PacketApplyBatcherEntryMixin {
	@Shadow
	@Final
	private PacketListener listener;

	@WrapMethod(method = "apply")
	private void momenton$markApplying(Operation<Void> original) {
		if (!(listener instanceof ClientPlayPacketListener)) {
			original.call();
			return;
		}
		LocalPrediction.enterPacket();
		try {
			original.call();
		} finally {
			LocalPrediction.exitPacket();
		}
	}
}
