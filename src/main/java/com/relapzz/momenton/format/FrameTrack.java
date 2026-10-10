package com.relapzz.momenton.format;

import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The read side of {@code frames.bin}: one fixed-stride record per frame the recording rendered.
 *
 * <p>Fixed stride is the whole point. Sampling the track at any output frame rate is a binary search
 * over an array, never a scan, which is what lets a 60 fps export read a 200 fps recording.
 */
public final class FrameTrack {
	public static final int STRIDE = 56;

	public static final int FLAG_SCREEN_OPEN = 1;
	public static final int FLAG_CHAT_OPEN = 2;
	public static final int FLAG_OFF_HAND = 4;
	public static final int FLAG_SPRINTING = 8;
	public static final int FLAG_THIRD_PERSON = 16;
	public static final int FLAG_USING_ITEM = 32;
	public static final int FLAG_SNEAKING = 64;

	/**
	 * Third-person front view, which is not a cosmetic variant of {@link #FLAG_THIRD_PERSON}:
	 * {@code Camera.update} sets the camera to {@code (yaw + 180, -pitch)} for it, so the recorded
	 * camera rotation is the player's own rotation *inverted*. Playback has to undo that before
	 * putting the rotation back on the player, or the body faces backwards.
	 */
	public static final int FLAG_FRONT_VIEW = 128;

	public static final int NORMALISED_MOUSE_VERSION = 2;

	private final ByteBuffer data;
	private final int count;
	private final boolean normalisedMouse;

	public FrameTrack(byte[] bytes, int formatVersion) {
		this.data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		this.count = bytes.length / STRIDE;
		this.normalisedMouse = formatVersion >= NORMALISED_MOUSE_VERSION;
	}

	public int count() {
		return count;
	}

	public boolean normalisedMouse() {
		return normalisedMouse;
	}

	/** The pose recorded at or just before {@code tick + tickProgress}, or null if the track is empty. */
	@Nullable
	public Pose sample(int tick, float tickProgress) {
		return count == 0 ? null : at(search(tick + (double) tickProgress));
	}

	/** Index of the last record whose timestamp is at or before {@code key}. */
	private int search(double key) {
		int low = 0;
		int high = count - 1;
		while (low < high) {
			int mid = (low + high + 1) >>> 1;
			if (keyAt(mid) <= key) {
				low = mid;
			} else {
				high = mid - 1;
			}
		}
		return low;
	}

	private double keyAt(int index) {
		int base = index * STRIDE;
		return data.getInt(base) + data.getFloat(base + 4);
	}

	private Pose at(int index) {
		int base = index * STRIDE;
		return new Pose(
				data.getInt(base),
				data.getFloat(base + 4),
				data.getDouble(base + 8),
				data.getDouble(base + 16),
				data.getDouble(base + 24),
				data.getFloat(base + 32),
				data.getFloat(base + 36),
				data.getFloat(base + 40),
				data.getFloat(base + 44),
				data.getFloat(base + 48),
				data.getInt(base + 52));
	}

	/** One frame's camera pose, matching the record layout in {@code FrameTrackWriter}. */
	public record Pose(
			int tick,
			float tickProgress,
			double x,
			double y,
			double z,
			float yaw,
			float pitch,
			float fov,
			float mouseX,
			float mouseY,
			int flags) {
	}
}
