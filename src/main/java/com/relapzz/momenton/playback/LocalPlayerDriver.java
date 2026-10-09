package com.relapzz.momenton.playback;

import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.format.EntityMoveCodec;
import com.relapzz.momenton.format.FrameTrack;
import com.relapzz.momenton.format.LocalPlayerCodec;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;

/**
 * Walks the replayed local player along the path the {@code ENTITY_MOVE} track recorded.
 *
 * <p>The packet stream never carried this: a server tells a client where every entity is except that
 * client's own player. Without it the player stands wherever the snapshot dropped it while the camera
 * walks away, so nothing drawn about the player - the model in third person, the arm, anything vanilla
 * measures from {@code client.player} - is where it was.
 *
 * <p>It also applies the {@code LOCAL_PLAYER} track - vitals, hotbar and offhand. Most of that also
 * crosses the wire and the two agree; what only lives in the track is an inventory change made through
 * the inventory screen, which the client performs itself and merely reports, so no clientbound packet
 * ever carries it.
 *
 * <p>Position, but not rotation. Rotation is {@link PovDriver}'s, because it was frame-rate live and
 * rebuilding it at tick rate makes the hand shake; and head yaw is not set at all, because
 * {@code PlayerEntity.tickMovement} derives it from the look direction and {@code LivingEntity.baseTick}
 * has already captured {@code lastHeadYaw} by then - writing it here collapses the two into one value
 * and the head snaps once per tick instead of turning.
 *
 * <p>The pose is applied at the head of {@code ClientPlayerEntity.tick}, which is the one point in the
 * tick where it produces the right derived state for free. {@code ClientWorld.tickEntity} has just
 * called {@code resetPosition}, so {@code lastX} holds the previous tick's recorded position; setting
 * the new one here means the movement delta vanilla reads a few lines later is exactly the recorded
 * one, and body yaw and the limb animator come out of vanilla's own code rather than a reimplementation
 * of it. Applying at end of tick instead would render correctly and animate a player sliding with its
 * legs still.
 *
 * <p>Gravity is turned off and the velocity zeroed rather than corrected afterwards: the recorded path
 * is the truth, and a simulated player would otherwise fall a tick's worth of gravity below every pose
 * it was given. That leaves {@code travel} moving the player by nothing, so nothing else has to be
 * suppressed - {@code noClip} in particular cannot be used here, because {@code PlayerEntity.tick}
 * assigns it from {@code isSpectator} on the very next line.
 *
 * <p>Written from the client thread and read from the same thread's entity tick, so no volatiles.
 */
public final class LocalPlayerDriver {
	private static final PlayerInput SNEAKING = new PlayerInput(false, false, false, false, false, true, false);

	/**
	 * The replayed player's input. Never carries movement - the path is applied directly - but it does
	 * carry sneak, because {@code ClientPlayerEntity.isSneaking()} reads nothing else, and
	 * {@code PlayerEntity.updatePose} reads that to decide whether the model crouches.
	 */
	private static final Input INPUT = new Input();

	private static LocalPlayerCodec.State state;
	private static boolean based;
	private static boolean pending;
	private static double x;
	private static double y;
	private static double z;

	private LocalPlayerDriver() {
	}

	static void reset() {
		based = false;
		pending = false;
		state = null;
	}

	/**
	 * Decodes one {@code LOCAL_PLAYER} action and holds it for the next player tick.
	 *
	 * <p>Most of this also arrives as packets, and where it does the two agree. What only lives here is
	 * an inventory change made through the inventory screen: the client moves the stack itself and
	 * tells the server, so nothing clientbound ever carries it and a replay driven by packets alone
	 * shows the item never moving.
	 */
	static void acceptState(byte[] payload, DynamicRegistryManager registries) {
		state = LocalPlayerCodec.decode(payload, registries);
	}

	/** Client thread, head of the replayed player's tick. Applied before vanilla reads any of it. */
	private static void applyState(ClientPlayerEntity player) {
		LocalPlayerCodec.State current = state;
		if (current == null) {
			return;
		}
		state = null;

		if (current.hasVitals()) {
			player.setHealth(current.health());
			player.getHungerManager().setFoodLevel(current.food());
			player.getHungerManager().setSaturationLevel(current.saturation());
			player.setAir(current.air());
			player.experienceLevel = current.experienceLevel();
			player.experienceProgress = current.experienceProgress();
		}

		if (current.hotbar() != null) {
			for (int slot = 0; slot < LocalPlayerCodec.HOTBAR_SIZE; slot++) {
				player.getInventory().setStack(slot, current.hotbar().get(slot));
			}
		}
		if (current.offhand() != null) {
			player.setStackInHand(Hand.OFF_HAND, current.offhand());
		}
	}

	/**
	 * Decodes one {@code ENTITY_MOVE} action and keeps whatever it says about the local player.
	 *
	 * <p>Deltas accumulate onto the last absolute pose, so this has to see every action in order. The
	 * recorder resets its trackers at each chunk boundary, which is what makes the first action after a
	 * snapshot absolute and the accumulation restartable after a seek.
	 */
	static void acceptMove(byte[] payload, int localPlayerId) {
		try {
			EntityMoveCodec.decode(payload, (id, flags, ex, ey, ez, eyaw, epitch, ehead) -> {
				if (id != localPlayerId) {
					return;
				}
				if ((flags & EntityMoveCodec.FLAG_POS_ABSOLUTE) != 0) {
					x = ex;
					y = ey;
					z = ez;
					based = true;
				} else if ((flags & EntityMoveCodec.FLAG_POS_DELTA) != 0 && based) {
					x += ex;
					y += ey;
					z += ez;
				}
				pending = based;
			});
		} catch (IOException e) {
			Momenton.LOGGER.error("Could not decode an entity move action", e);
		}
	}

	/** Client thread, head of the replayed player's tick. */
	public static void drive(ClientPlayerEntity player) {
		int flags = PovDriver.flags();
		if (flags < 0) {
			return;
		}

		// The join wires a fresh player to the keyboard and every snapshot builds another, so this is
		// reasserted every tick rather than once. Without it local keys walk the replay away from what
		// was recorded, and with a bare Input the recorded player can never crouch.
		INPUT.playerInput = (flags & FrameTrack.FLAG_SNEAKING) != 0 ? SNEAKING : PlayerInput.DEFAULT;
		player.input = INPUT;
		applyState(player);

		if (!pending) {
			return;
		}
		pending = false;

		player.setNoGravity(true);
		player.setVelocity(Vec3d.ZERO);
		player.setPosition(x, y, z);
	}

	/**
	 * Puts the player on its pose with no interpolation, for a seek. Rendering lerps from the previous
	 * tick's position, and after a jump that is wherever the replay used to be.
	 */
	static void snap(ClientPlayerEntity player) {
		drive(player);
		player.resetPosition();
	}
}
