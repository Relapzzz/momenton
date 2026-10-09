package com.relapzz.momenton.edit;

public record Clip(int startTick, int endTick) {
	public Clip {
		if (startTick < 0 || endTick <= startTick) throw new IllegalArgumentException("Invalid clip [" + startTick + ", " + endTick + ")");
	}

	public int length() {
		return endTick - startTick;
	}
}
