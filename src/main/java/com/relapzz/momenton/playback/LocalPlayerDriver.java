package com.relapzz.momenton.playback;

import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.format.EntityMoveCodec;
import com.relapzz.momenton.format.FrameTrack;
import com.relapzz.momenton.format.LocalPlayerCodec;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
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
 * <p>It also applies the {@code LOCAL_PLAYER} track - vitals, hotbar, offhand, portal warp and fire
 * ticks. Most of that also crosses the wire and the two agree; what only lives in the track is what
 * the client does for itself - an inventory change made through the inventory screen, the warp of
 * standing in a portal, the fire of touching lava - which no clientbound packet ever carries.
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

	private static final Int2ObjectMap<Pose> VEHICLES = new Int2ObjectOpenHashMap<>();

	private static LocalPlayerCodec.State state;
	private static LocalPlayerCodec.State overlays;
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
		overlays = null;
		VEHICLES.clear();
	}

	/**
	 * Decodes one {@code LOCAL_PLAYER} action and holds it for the next player tick.
	 *
	 * <p>Most of this also arrives as packets, and where it does the two agree. What only lives here is
	 * what the client predicts for itself. An inventory change made through the inventory screen: the
	 * client moves the stack itself and tells the server, so nothing clientbound ever carries it and a
	 * replay driven by packets alone shows the item never moving. The portal warp and the fire of
	 * touching lava: the client sets both from its own collisions, which the driven player never has.
	 */
	static void acceptState(byte[] payload, DynamicRegistryManager registries) {
		state = merge(state, LocalPlayerCodec.decode(payload, registries));
	}

	private static LocalPlayerCodec.State merge(LocalPlayerCodec.State earlier, LocalPlayerCodec.State later) {
		if (earlier == null) {
			return later;
		}
		LocalPlayerCodec.State vitals = later.hasVitals() ? later : earlier;
		LocalPlayerCodec.State overlay = later.hasOverlays() ? later : earlier;
		return new LocalPlayerCodec.State(vitals.hasVitals(), vitals.health(), vitals.food(), vitals.saturation(),
				vitals.air(), vitals.experienceLevel(), vitals.experienceProgress(),
				later.hotbar() != null ? later.hotbar() : earlier.hotbar(),
				later.offhand() != null ? later.offhand() : earlier.offhand(),
				overlay.hasOverlays(), overlay.nauseaIntensity(), overlay.fireTicks());
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
		if (current.hasOverlays()) {
			overlays = current;
		}
	}

	public static void driveSprint(ClientPlayerEntity player) {
		int flags = PovDriver.flags();
		if (flags >= 0) {
			player.setSprinting((flags & FrameTrack.FLAG_SPRINTING) != 0);
		}
	}

	public static void driveOverlays(ClientPlayerEntity player) {
		if (overlays == null) {
			return;
		}
		player.nauseaIntensity = overlays.nauseaIntensity();
		player.setFireTicks(overlays.fireTicks());
	}

	/**
	 * Decodes one {@code ENTITY_MOVE} action and keeps whatever it says about the local player and the
	 * vehicles it rides.
	 *
	 * <p>Deltas accumulate onto the last absolute pose, so this has to see every action in order. The
	 * recorder resets its trackers at each chunk boundary, which is what makes the first action after a
	 * snapshot absolute and the accumulation restartable after a seek.
	 */
	static void acceptMove(byte[] payload, int localPlayerId) {
		try {
			EntityMoveCodec.decode(payload, (id, flags, ex, ey, ez, eyaw, epitch, ehead) -> {
				if (id != localPlayerId) {
					acceptVehicleMove(id, flags, ex, ey, ez, eyaw, epitch);
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

	private static void acceptVehicleMove(int id, int flags, double x, double y, double z, float yaw, float pitch) {
		Pose pose = (flags & EntityMoveCodec.FLAG_POS_ABSOLUTE) != 0
				? VEHICLES.computeIfAbsent(id, key -> new Pose())
				: VEHICLES.get(id);
		if (pose == null) {
			return;
		}
		if ((flags & EntityMoveCodec.FLAG_POS_ABSOLUTE) != 0) {
			pose.x = x;
			pose.y = y;
			pose.z = z;
		} else if ((flags & EntityMoveCodec.FLAG_POS_DELTA) != 0) {
			pose.x += x;
			pose.y += y;
			pose.z += z;
		}
		if ((flags & EntityMoveCodec.FLAG_ROTATION) != 0) {
			pose.yaw = yaw;
			pose.pitch = pitch;
		}
	}

	public static void driveVehicle(Entity vehicle) {
		Pose pose = VEHICLES.get(vehicle.getId());
		if (pose == null) {
			return;
		}
		if (MinecraftClient.getInstance().player.getRootVehicle() != vehicle) {
			VEHICLES.remove(vehicle.getId());
			return;
		}
		vehicle.setVelocity(Vec3d.ZERO);
		vehicle.setPosition(pose.x, pose.y, pose.z);
		vehicle.setYaw(pose.yaw);
		vehicle.setPitch(pose.pitch);
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

		if (!pending || player.hasVehicle()) {
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
		Entity vehicle = player.getRootVehicle();
		if (vehicle != player) {
			driveVehicle(vehicle);
			vehicle.resetPosition();
			vehicle.updatePassengerPosition(player);
		}
		player.resetPosition();
		driveSprint(player);
		driveOverlays(player);
		player.lastNauseaIntensity = player.nauseaIntensity;
	}

	private static final class Pose {
		private double x;
		private double y;
		private double z;
		private float yaw;
		private float pitch;
	}
}
