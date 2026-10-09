package com.relapzz.momenton.record;

import com.relapzz.momenton.format.LocalPlayerCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Emits a {@code LOCAL_PLAYER} action whenever the recorded player's vitals, carried items, portal warp
 * or fire ticks change.
 *
 * <p>Most of this also crosses the wire, but having it as first-class state means a seek can restore
 * the player from a snapshot boundary instead of replaying inventory packets from tick zero.
 */
final class LocalPlayerWriter {
	private static final int FLAG_VITALS = LocalPlayerCodec.FLAG_VITALS;
	private static final int FLAG_HOTBAR = LocalPlayerCodec.FLAG_HOTBAR;
	private static final int FLAG_OFFHAND = LocalPlayerCodec.FLAG_OFFHAND;
	private static final int FLAG_OVERLAYS = LocalPlayerCodec.FLAG_OVERLAYS;

	private static final int HOTBAR_SIZE = LocalPlayerCodec.HOTBAR_SIZE;

	private final DynamicRegistryManager registries;
	private final ItemStack[] hotbar = new ItemStack[HOTBAR_SIZE];

	private float health = Float.NaN;
	private int food = -1;
	private float saturation;
	private int air;
	private int experienceLevel;
	private float experienceProgress;
	private ItemStack offhand = ItemStack.EMPTY;
	private float nauseaIntensity;
	private int fireTicks;
	private boolean forced;

	LocalPlayerWriter(DynamicRegistryManager registries) {
		this.registries = registries;
		Arrays.fill(hotbar, ItemStack.EMPTY);
	}

	/**
	 * Forces the next capture to write everything.
	 *
	 * <p>Called at a chunk boundary. This track is change-gated, so a seek that lands in a later chunk
	 * would otherwise miss every value last written in an earlier one.
	 */
	void reset() {
		forced = true;
	}

	/** The payload for this tick, or null if nothing changed. */
	@Nullable
	byte[] capture(ClientPlayerEntity player) {
		boolean vitalsChanged = forced
				|| player.getHealth() != health
				|| player.getHungerManager().getFoodLevel() != food
				|| player.getHungerManager().getSaturationLevel() != saturation
				|| player.getAir() != air
				|| player.experienceLevel != experienceLevel
				|| player.experienceProgress != experienceProgress;

		boolean hotbarChanged = forced;
		for (int slot = 0; slot < HOTBAR_SIZE && !hotbarChanged; slot++) {
			hotbarChanged = !ItemStack.areEqual(player.getInventory().getStack(slot), hotbar[slot]);
		}

		ItemStack currentOffhand = player.getOffHandStack();
		boolean offhandChanged = forced || !ItemStack.areEqual(currentOffhand, offhand);

		boolean overlaysChanged = forced
				|| player.nauseaIntensity != nauseaIntensity
				|| player.getFireTicks() != fireTicks;

		if (!vitalsChanged && !hotbarChanged && !offhandChanged && !overlaysChanged) {
			return null;
		}
		forced = false;

		RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(64), registries);
		buf.writeByte((vitalsChanged ? FLAG_VITALS : 0) | (hotbarChanged ? FLAG_HOTBAR : 0) | (offhandChanged ? FLAG_OFFHAND : 0)
				| (overlaysChanged ? FLAG_OVERLAYS : 0));

		if (vitalsChanged) {
			health = player.getHealth();
			food = player.getHungerManager().getFoodLevel();
			saturation = player.getHungerManager().getSaturationLevel();
			air = player.getAir();
			experienceLevel = player.experienceLevel;
			experienceProgress = player.experienceProgress;

			buf.writeFloat(health);
			buf.writeVarInt(food);
			buf.writeFloat(saturation);
			buf.writeShort(air);
			buf.writeVarInt(experienceLevel);
			buf.writeFloat(experienceProgress);
		}

		if (hotbarChanged) {
			for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
				hotbar[slot] = player.getInventory().getStack(slot).copy();
				ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, hotbar[slot]);
			}
		}

		if (offhandChanged) {
			offhand = currentOffhand.copy();
			ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, offhand);
		}

		if (overlaysChanged) {
			nauseaIntensity = player.nauseaIntensity;
			fireTicks = player.getFireTicks();
			buf.writeFloat(nauseaIntensity);
			buf.writeVarInt(fireTicks);
		}

		byte[] payload = new byte[buf.readableBytes()];
		buf.readBytes(payload);
		return payload;
	}
}
