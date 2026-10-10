package com.relapzz.momenton.format;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.List;

/**
 * {@code metadata.json}: everything a reader needs before touching the binary tracks.
 *
 * <p>{@code framesRendered} is counted by {@link com.relapzz.momenton.record.FrameTrackWriter}
 * independently of the bytes written, so {@code frames.bin} being
 * {@code 56 * framesRendered} long is a real consistency check rather than a tautology.
 *
 * <p>{@code worldName} is the save name in singleplayer and the server entry's name otherwise. It is
 * null in recordings made before it existed, so read it through {@link #describeWorld()}.
 */
public record ReplayMetadata(
		int formatVersion,
		String minecraftVersion,
		String modVersion,
		long createdAtEpochMs,
		int durationTicks,
		long framesRendered,
		int chunkTicks,
		String worldName,
		String initialDimension,
		List<ChunkEntry> chunks,
		List<DimensionChange> dimensionChanges,
		Integer framebufferWidth,
		Integer framebufferHeight,
		Integer guiScale,
		Double micStartTick,
		Integer micSampleRate,
		List<ModEntry> mods,
		List<String> resourcePacks,
		PlayerEntry player,
		List<ServerPack> serverPacks) {

	public static final int FORMAT_VERSION = 2;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public ReplayMetadata(int formatVersion, String minecraftVersion, String modVersion, long createdAtEpochMs,
			int durationTicks, long framesRendered, int chunkTicks, String worldName, String initialDimension,
			List<ChunkEntry> chunks, List<DimensionChange> dimensionChanges, Integer framebufferWidth,
			Integer framebufferHeight, Integer guiScale) {
		this(formatVersion, minecraftVersion, modVersion, createdAtEpochMs, durationTicks, framesRendered, chunkTicks,
				worldName, initialDimension, chunks, dimensionChanges, framebufferWidth, framebufferHeight, guiScale,
				null, null, null, null, null, null);
	}

	/** One {@code chunk_NNNN.bin} / {@code ui_NNNN.bin} pair, covering {@code [startTick, endTick)}. */
	public record ChunkEntry(int index, int startTick, int endTick) {
	}

	public record DimensionChange(int tick, String dimension) {
	}

	public record ModEntry(String id, String version) {
	}

	public record PlayerEntry(String uuid, String name) {
	}

	public record ServerPack(String id, String sha256) {
		public String entry() {
			return "resourcepacks/" + id + ".zip";
		}
	}

	/** The world this was recorded in, or a placeholder for recordings made before it was stored. */
	public String describeWorld() {
		return worldName == null ? "Unknown world" : worldName;
	}

	public String toJson() {
		return GSON.toJson(this);
	}

	public static ReplayMetadata fromJson(String json) {
		return GSON.fromJson(json, ReplayMetadata.class);
	}
}
