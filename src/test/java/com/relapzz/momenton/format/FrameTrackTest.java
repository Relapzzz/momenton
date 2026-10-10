package com.relapzz.momenton.format;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameTrackTest {
	private static final int[] TICKS = {10, 10, 11, 12};
	private static final float[] PROGRESS = {0.0f, 0.5f, 0.25f, 0.0f};

	private static byte[] records() {
		ByteBuffer buffer = ByteBuffer.allocate(FrameTrack.STRIDE * TICKS.length).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < TICKS.length; i++) {
			buffer.putInt(TICKS[i]).putFloat(PROGRESS[i])
					.putDouble(i).putDouble(64 + i).putDouble(-i)
					.putFloat(i * 10).putFloat(-i).putFloat(70 + i)
					.putFloat(i / 4f).putFloat(1 - i / 4f).putInt(i);
		}
		return buffer.array();
	}

	private static int indexAt(FrameTrack track, int tick, float progress) {
		return track.sample(tick, progress).flags();
	}

	@Test
	void exactRecordsDecodeEveryField() {
		FrameTrack track = new FrameTrack(records(), ReplayMetadata.FORMAT_VERSION);
		assertEquals(4, track.count());
		assertEquals(new FrameTrack.Pose(11, 0.25f, 2, 66, -2, 20, -2, 72, 0.5f, 0.5f, 2), track.sample(11, 0.25f));
		for (int i = 0; i < TICKS.length; i++) assertEquals(i, indexAt(track, TICKS[i], PROGRESS[i]));
	}

	@Test
	void fractionalTimesPickTheRecordAtOrBefore() {
		FrameTrack track = new FrameTrack(records(), ReplayMetadata.FORMAT_VERSION);
		assertEquals(0, indexAt(track, 10, 0.49f));
		assertEquals(1, indexAt(track, 10, 0.75f));
		assertEquals(1, indexAt(track, 11, 0.1f));
		assertEquals(2, indexAt(track, 11, 0.9f));
	}

	@Test
	void outsideTheTrackClampsToTheEnds() {
		FrameTrack track = new FrameTrack(records(), ReplayMetadata.FORMAT_VERSION);
		assertEquals(0, indexAt(track, 0, 0.0f));
		assertEquals(0, indexAt(track, 9, 0.99f));
		assertEquals(3, indexAt(track, 12, 0.5f));
		assertEquals(3, indexAt(track, 5000, 0.0f));
	}

	@Test
	void emptyAndPartialTracks() {
		assertNull(new FrameTrack(new byte[0], ReplayMetadata.FORMAT_VERSION).sample(0, 0.0f));
		byte[] partial = new byte[FrameTrack.STRIDE + 10];
		System.arraycopy(records(), 0, partial, 0, partial.length);
		assertEquals(1, new FrameTrack(partial, ReplayMetadata.FORMAT_VERSION).count());
	}

	@Test
	void mouseIsNormalisedFromVersionTwo() {
		assertFalse(new FrameTrack(records(), 1).normalisedMouse());
		assertTrue(new FrameTrack(records(), FrameTrack.NORMALISED_MOUSE_VERSION).normalisedMouse());
		assertTrue(new FrameTrack(records(), FrameTrack.NORMALISED_MOUSE_VERSION + 1).normalisedMouse());
	}
}
