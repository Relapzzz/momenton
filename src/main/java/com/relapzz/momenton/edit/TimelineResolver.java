package com.relapzz.momenton.edit;

import java.util.List;

public final class TimelineResolver {
	public static final int TICKS_PER_SECOND = 20;

	public record Position(int clipIndex, int sourceTick, double tickProgress) {
	}

	private final List<Clip> clips;
	private final int[] starts;

	public TimelineResolver(Timeline timeline) {
		clips = timeline.clips();
		starts = new int[clips.size() + 1];
		for (int i = 0; i < clips.size(); i++) starts[i + 1] = starts[i] + clips.get(i).length();
	}

	public Position resolve(double seconds) {
		double ticks = Math.clamp(seconds * TICKS_PER_SECOND, 0, starts[clips.size()]);
		int index = 0;
		while (index < clips.size() - 1 && ticks >= starts[index + 1]) index++;
		double offset = ticks - starts[index];
		int whole = (int) Math.floor(offset);
		return new Position(index, clips.get(index).startTick() + whole, offset - whole);
	}

	public double startSeconds(int clipIndex) {
		return (double) starts[clipIndex] / TICKS_PER_SECOND;
	}

	public double endSeconds(int clipIndex) {
		return (double) starts[clipIndex + 1] / TICKS_PER_SECOND;
	}

	public double lengthSeconds() {
		return (double) starts[clips.size()] / TICKS_PER_SECOND;
	}

	public List<Clip> exportRanges() {
		return clips;
	}
}
