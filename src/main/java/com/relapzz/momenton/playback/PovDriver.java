package com.relapzz.momenton.playback;

import com.relapzz.momenton.format.FrameTrack;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Drives the replay camera from {@code frames.bin} instead of from the replayed player.
 *
 * <p>The packet stream only carries player rotation at 20 Hz, so a camera derived from it is smooth
 * but visibly not the one that was recorded. The frame track holds the real pose at frame rate; this
 * is what puts it back on screen.
 *
 * <p>Read from the render thread and written from the client thread, hence the volatiles. Held
 * statically because the camera mixin has no other way to reach the session.
 */
public final class PovDriver {
	private static volatile FrameTrack track;
	private static volatile int tick;
	private static volatile boolean frozen;
	private static volatile float heldProgress;

	private PovDriver() {
	}

	static void play(FrameTrack frames) {
		track = frames;
		tick = 0;
		frozen = false;
	}

	static void stop() {
		track = null;
		frozen = false;
	}

	/** The replay tick now being displayed. */
	static void setTick(int value) {
		tick = value;
	}

	/**
	 * Pins the camera between ticks while paused.
	 *
	 * <p>Pausing stops the replay tick advancing but not the client's, so tick progress keeps
	 * sweeping 0 to 1 and the pose keeps interpolating towards a tick that never arrives - the camera
	 * creeps forward and snaps back, once per tick. Holding the progress of the last frame drawn
	 * freezes it where it actually was rather than at the start of the tick.
	 */
	static void setFrozen(boolean value) {
		frozen = value;
	}

	/** The recorded pose for this frame, or null when nothing is playing. */
	@Nullable
	public static FrameTrack.Pose pose(float tickProgress) {
		FrameTrack current = track;
		if (current == null) {
			return null;
		}
		if (frozen) {
			return current.sample(tick, heldProgress);
		}
		heldProgress = tickProgress;
		return current.sample(tick, tickProgress);
	}

	/** The recorded frame flags at the current tick, or -1 when nothing is playing. */
	public static int flags() {
		FrameTrack current = track;
		FrameTrack.Pose pose = current == null ? null : current.sample(tick, heldProgress);
		return pose == null ? -1 : pose.flags();
	}

	/**
	 * Points the replayed player where the recording was looking, once per rendered frame.
	 *
	 * <p>This has to be frame-rate, not tick-rate, because it was frame-rate live: vanilla turns the
	 * local player from {@code Mouse}, every frame, and the camera is only ever a reading of
	 * {@code player.getYaw(tickProgress)}. Anything derived from that value inherits its smoothness -
	 * the first-person hand sway in {@code HeldItemRenderer} is computed from it directly. Rebuilding
	 * the rotation at 20 Hz instead leaves the hand stepping against a camera that moves smoothly,
	 * which is what a replayed hand shaking on every turn actually is.
	 *
	 * <p>{@code lastYaw} goes with it so that {@code getYaw(tickProgress)} is the recorded angle for
	 * every tickProgress rather than a lerp towards it from the last tick. The recorded angle already
	 * *is* the interpolated one - it was read out of a live camera - so interpolating it again would
	 * smear it a second time.
	 */
	public static void driveLook(Entity focused, float tickProgress) {
		FrameTrack.Pose pose = pose(tickProgress);
		if (pose == null || !(focused instanceof ClientPlayerEntity player)) {
			return;
		}

		// In front view the recorded camera rotation is the player's own, inverted by Camera.update.
		// Putting it back on the player unchanged spins the body 180 degrees - which is exactly what
		// "the character turned around in third person front" was.
		boolean front = (pose.flags() & FrameTrack.FLAG_FRONT_VIEW) != 0;
		float yaw = front ? pose.yaw() + 180.0f : pose.yaw();
		float pitch = front ? -pose.pitch() : pose.pitch();

		player.setYaw(yaw);
		player.setPitch(pitch);
		player.lastYaw = yaw;
		player.lastPitch = pitch;
	}

	/** The perspective the recording was in, which is a recorded fact rather than a viewer setting. */
	static Perspective perspective() {
		int flags = flags();
		if ((flags & FrameTrack.FLAG_THIRD_PERSON) == 0) {
			return Perspective.FIRST_PERSON;
		}
		return (flags & FrameTrack.FLAG_FRONT_VIEW) != 0 ? Perspective.THIRD_PERSON_FRONT : Perspective.THIRD_PERSON_BACK;
	}
}
