package com.relapzz.momenton.audio;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MicMeterTest {
	private static byte[] samples(short... values) {
		ByteBuffer buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (short value : values) {
			buffer.putShort(value);
		}
		return buffer.array();
	}

	@Test
	void silenceIsEmpty() {
		byte[] silence = samples((short) 0, (short) 0, (short) 0);
		assertEquals(0, MicMeter.level(silence, silence.length));
	}

	@Test
	void fullScaleIsFull() {
		byte[] loud = samples((short) 10, Short.MIN_VALUE, (short) -3);
		assertEquals(1, MicMeter.level(loud, loud.length), 1e-6);
	}

	@Test
	void levelIsLogarithmicOverSixtyDecibels() {
		byte[] quiet = samples((short) 3277, (short) -100);
		assertEquals(2 / 3.0, MicMeter.level(quiet, quiet.length), 1e-3);
		byte[] faint = samples((short) 20);
		assertEquals(0, MicMeter.level(faint, faint.length));
	}

	@Test
	void readsOnlyTheFilledPart() {
		byte[] buffer = samples((short) 0, Short.MAX_VALUE);
		assertEquals(0, MicMeter.level(buffer, 2));
	}
}
