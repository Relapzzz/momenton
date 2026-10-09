package com.relapzz.momenton.edit;

import com.relapzz.momenton.edit.TimelineResolver.Position;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimelineResolverTest {
	private static final Timeline EDIT = new Timeline(1000, List.of(new Clip(500, 540), new Clip(100, 120), new Clip(700, 760)));
	private static final TimelineResolver RESOLVER = new TimelineResolver(EDIT);

	@Test
	void zeroIsTheFirstClipsStart() {
		assertEquals(new Position(0, 500, 0), RESOLVER.resolve(0));
	}

	@Test
	void insideAClip() {
		assertEquals(new Position(0, 510, 0), RESOLVER.resolve(0.5));
		assertEquals(new Position(1, 105, 0), RESOLVER.resolve(2.25));
	}

	@Test
	void fractionalTicks() {
		assertEquals(new Position(0, 502, 0.5), RESOLVER.resolve(0.125));
		assertEquals(new Position(2, 701, 0.25), RESOLVER.resolve(3.0625));
	}

	@Test
	void aTimeOnACutBelongsToTheNextClip() {
		assertEquals(new Position(1, 100, 0), RESOLVER.resolve(2));
		assertEquals(new Position(2, 700, 0), RESOLVER.resolve(3));
	}

	@Test
	void justBeforeACutIsTheLastTickOfThePreviousClip() {
		Position position = RESOLVER.resolve(Math.nextDown(2.0));
		assertEquals(0, position.clipIndex());
		assertEquals(539, position.sourceTick());
	}

	@Test
	void theEndIsTheLastClipsExclusiveEnd() {
		assertEquals(new Position(2, 760, 0), RESOLVER.resolve(6));
		assertEquals(new Position(2, 760, 0), RESOLVER.resolve(60));
		assertEquals(new Position(0, 500, 0), RESOLVER.resolve(-1));
	}

	@Test
	void clipBoundariesInSeconds() {
		assertEquals(0, RESOLVER.startSeconds(0));
		assertEquals(2, RESOLVER.endSeconds(0));
		assertEquals(2, RESOLVER.startSeconds(1));
		assertEquals(3, RESOLVER.startSeconds(2));
		assertEquals(6, RESOLVER.endSeconds(2));
		assertEquals(6, RESOLVER.lengthSeconds());
		for (int i = 0; i < EDIT.clips().size(); i++) {
			assertEquals(new Position(i, EDIT.clips().get(i).startTick(), 0), RESOLVER.resolve(RESOLVER.startSeconds(i)));
		}
	}

	@Test
	void exportRangesAreTheClipsInOrder() {
		assertEquals(EDIT.clips(), RESOLVER.exportRanges());
	}
}
