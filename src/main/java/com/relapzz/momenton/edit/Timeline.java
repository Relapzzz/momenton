package com.relapzz.momenton.edit;

import java.util.ArrayList;
import java.util.List;

public record Timeline(int sourceTicks, List<Clip> clips) {
	public Timeline {
		clips = List.copyOf(clips);
		if (clips.isEmpty()) throw new IllegalArgumentException("A timeline needs at least one clip");
		for (Clip clip : clips) {
			if (clip.endTick() > sourceTicks) throw new IllegalArgumentException(clip + " is outside the replay's " + sourceTicks + " ticks");
		}
	}

	public static Timeline of(int sourceTicks) {
		return new Timeline(sourceTicks, List.of(new Clip(0, sourceTicks)));
	}

	public int length() {
		return clips.stream().mapToInt(Clip::length).sum();
	}

	public Timeline split(int index, int sourceTick) {
		Clip clip = clips.get(index);
		if (sourceTick <= clip.startTick() || sourceTick >= clip.endTick()) return this;
		List<Clip> edited = new ArrayList<>(clips);
		edited.set(index, new Clip(clip.startTick(), sourceTick));
		edited.add(index + 1, new Clip(sourceTick, clip.endTick()));
		return new Timeline(sourceTicks, edited);
	}

	public Timeline trimStart(int index, int sourceTick) {
		Clip clip = clips.get(index);
		return replace(index, new Clip(Math.clamp(sourceTick, 0, clip.endTick() - 1), clip.endTick()));
	}

	public Timeline trimEnd(int index, int sourceTick) {
		Clip clip = clips.get(index);
		return replace(index, new Clip(clip.startTick(), Math.clamp(sourceTick, clip.startTick() + 1, sourceTicks)));
	}

	public Timeline move(int from, int to) {
		List<Clip> edited = new ArrayList<>(clips);
		edited.add(to, edited.remove(from));
		return new Timeline(sourceTicks, edited);
	}

	public Timeline delete(int index) {
		if (clips.size() == 1) return this;
		List<Clip> edited = new ArrayList<>(clips);
		edited.remove(index);
		return new Timeline(sourceTicks, edited);
	}

	private Timeline replace(int index, Clip clip) {
		List<Clip> edited = new ArrayList<>(clips);
		edited.set(index, clip);
		return new Timeline(sourceTicks, edited);
	}
}
