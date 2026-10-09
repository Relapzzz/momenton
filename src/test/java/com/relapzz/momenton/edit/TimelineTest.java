package com.relapzz.momenton.edit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimelineTest {
	private static final Timeline THREE = new Timeline(300, List.of(new Clip(0, 100), new Clip(100, 200), new Clip(200, 300)));

	@Test
	void newTimelineCoversTheWholeReplay() {
		Timeline timeline = Timeline.of(1200);
		assertEquals(List.of(new Clip(0, 1200)), timeline.clips());
		assertEquals(1200, timeline.length());
	}

	@Test
	void splitInsideAClip() {
		Timeline split = Timeline.of(300).split(0, 120);
		assertEquals(List.of(new Clip(0, 120), new Clip(120, 300)), split.clips());
		assertEquals(300, split.length());
	}

	@Test
	void splitOnABoundaryDoesNothing() {
		assertSame(THREE, THREE.split(1, 100));
		assertSame(THREE, THREE.split(1, 200));
		assertSame(THREE, THREE.split(0, 0));
	}

	@Test
	void trimClampsInsideTheReplay() {
		Timeline trimmed = THREE.trimStart(1, -50).trimEnd(1, 9999);
		assertEquals(new Clip(0, 300), trimmed.clips().get(1));
		assertEquals(500, trimmed.length());
	}

	@Test
	void trimNeverEmptiesAClip() {
		assertEquals(new Clip(199, 200), THREE.trimStart(1, 500).clips().get(1));
		assertEquals(new Clip(100, 101), THREE.trimEnd(1, 0).clips().get(1));
		assertEquals(1, THREE.trimStart(1, 199).clips().get(1).length());
	}

	@Test
	void moveReorders() {
		assertEquals(List.of(new Clip(200, 300), new Clip(0, 100), new Clip(100, 200)), THREE.move(2, 0).clips());
		assertEquals(List.of(new Clip(100, 200), new Clip(200, 300), new Clip(0, 100)), THREE.move(0, 2).clips());
	}

	@Test
	void deleteRipples() {
		Timeline deleted = THREE.delete(1);
		assertEquals(List.of(new Clip(0, 100), new Clip(200, 300)), deleted.clips());
		assertEquals(200, deleted.length());
		assertEquals(new TimelineResolver.Position(1, 200, 0), new TimelineResolver(deleted).resolve(5));
	}

	@Test
	void deleteTheLastClip() {
		assertEquals(List.of(new Clip(0, 100), new Clip(100, 200)), THREE.delete(2).clips());
	}

	@Test
	void theOnlyClipCannotBeDeleted() {
		Timeline single = Timeline.of(100);
		assertSame(single, single.delete(0));
	}

	@Test
	void invalidClipsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new Clip(10, 10));
		assertThrows(IllegalArgumentException.class, () -> new Clip(-1, 10));
		assertThrows(IllegalArgumentException.class, () -> new Timeline(100, List.of(new Clip(0, 101))));
		assertThrows(IllegalArgumentException.class, () -> new Timeline(100, List.of()));
	}
}
