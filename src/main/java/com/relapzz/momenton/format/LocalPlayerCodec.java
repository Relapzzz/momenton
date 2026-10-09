package com.relapzz.momenton.format;

import io.netty.buffer.Unpooled;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;

import java.util.ArrayList;
import java.util.List;

/**
 * The payload inside a {@code LOCAL_PLAYER} action: {@code [byte flags]} then only the sections the
 * flags name.
 *
 * <p>Change-gated, so most ticks write nothing at all and a tick that changes one thing writes only
 * that thing. Every tracker is reset at a chunk boundary, so the first action of a chunk carries every
 * section and a seek into the middle of a recording still learns the whole state.
 *
 * <p>Item stacks are encoded with the session's own {@link DynamicRegistryManager}, the same one the
 * packet stream is encoded against, so decoding needs playback's registry manager and nothing else.
 */
public final class LocalPlayerCodec {
	public static final int FLAG_VITALS = 1;
	public static final int FLAG_HOTBAR = 2;
	public static final int FLAG_OFFHAND = 4;

	public static final int HOTBAR_SIZE = 9;

	private LocalPlayerCodec() {
	}

	/**
	 * A decoded action. {@code hotbar} and {@code offhand} are null when the action did not carry them;
	 * the vitals have no such marker of their own, so {@code hasVitals} says whether they mean anything.
	 */
	public record State(boolean hasVitals, float health, int food, float saturation, int air,
			int experienceLevel, float experienceProgress, List<ItemStack> hotbar, ItemStack offhand) {
	}

	public static State decode(byte[] payload, DynamicRegistryManager registries) {
		RegistryByteBuf buf = new RegistryByteBuf(Unpooled.wrappedBuffer(payload), registries);
		int flags = buf.readByte();

		float health = 0.0f;
		int food = 0;
		float saturation = 0.0f;
		int air = 0;
		int experienceLevel = 0;
		float experienceProgress = 0.0f;
		if ((flags & FLAG_VITALS) != 0) {
			health = buf.readFloat();
			food = buf.readVarInt();
			saturation = buf.readFloat();
			air = buf.readShort();
			experienceLevel = buf.readVarInt();
			experienceProgress = buf.readFloat();
		}

		List<ItemStack> hotbar = null;
		if ((flags & FLAG_HOTBAR) != 0) {
			hotbar = new ArrayList<>(HOTBAR_SIZE);
			for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
				hotbar.add(ItemStack.OPTIONAL_PACKET_CODEC.decode(buf));
			}
		}

		ItemStack offhand = (flags & FLAG_OFFHAND) != 0 ? ItemStack.OPTIONAL_PACKET_CODEC.decode(buf) : null;
		return new State((flags & FLAG_VITALS) != 0, health, food, saturation, air, experienceLevel,
				experienceProgress, hotbar, offhand);
	}
}
