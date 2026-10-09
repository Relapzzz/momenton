package com.relapzz.momenton.mixin.client;

import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.record.Recorder;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The recording tap. Packets are taken here, already decoded, because Momenton has to inspect
 * traffic to filter it - the raw {@code ByteBuf} earlier in the netty pipeline would not do.
 */
@Mixin(ClientConnection.class)
public class ClientConnectionMixin {
	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"))
	private void momenton$record(ChannelHandlerContext context, Packet<?> packet, CallbackInfo info) {
		// Playback replays through a real ClientConnection, so this fires for its packets too.
		// Recording a replay is not recording a session.
		if (Boolean.TRUE.equals(context.channel().attr(Momenton.PLAYBACK).get())) {
			return;
		}
		Recorder.onPacket(packet);
	}
}
