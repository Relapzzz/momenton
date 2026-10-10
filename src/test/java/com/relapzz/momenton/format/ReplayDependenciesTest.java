package com.relapzz.momenton.format;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDependenciesTest {
	private static final List<ReplayMetadata.ModEntry> MODS = List.of(
			new ReplayMetadata.ModEntry("fabric-api", "0.141.6+1.21.11"),
			new ReplayMetadata.ModEntry("sodium", "0.8.0"),
			new ReplayMetadata.ModEntry("create", "6.0.0"));
	private static final List<String> PACKS = List.of("vanilla", "fabric", "file/Faithful.zip");

	private static ReplayMetadata recorded(List<ReplayMetadata.ModEntry> mods, List<String> packs) {
		return new ReplayMetadata(ReplayMetadata.FORMAT_VERSION, "1.21.11", "1.0.0", 0L, 1, 0L, ReplayWriter.CHUNK_TICKS,
				"World", "minecraft:overworld", List.of(), List.of(), 1920, 1080, 3, null, null, mods, packs);
	}

	@Test
	void oldRecordingsLoadWithoutDependenciesAndNeverWarn() {
		ReplayMetadata old = ReplayMetadata.fromJson("""
				{"formatVersion": 2, "minecraftVersion": "1.21.11", "modVersion": "1.0.0", "durationTicks": 20,
				 "chunks": [], "dimensionChanges": []}""");
		assertNull(old.mods());
		assertNull(old.resourcePacks());
		ReplayDependencies dependencies = ReplayDependencies.compare(old, MODS, PACKS);
		assertFalse(dependencies.modsDiffer());
		assertFalse(dependencies.resourcePacksDiffer());
	}

	@Test
	void dependenciesSurviveJson() {
		ReplayMetadata metadata = ReplayMetadata.fromJson(recorded(MODS, PACKS).toJson());
		assertEquals(MODS, metadata.mods());
		assertEquals(PACKS, metadata.resourcePacks());
	}

	@Test
	void sameGameMatches() {
		ReplayDependencies dependencies = ReplayDependencies.compare(recorded(MODS, PACKS), MODS.reversed(), PACKS);
		assertFalse(dependencies.modsDiffer());
		assertFalse(dependencies.resourcePacksDiffer());
	}

	@Test
	void reportsMissingAddedAndChangedMods() {
		List<ReplayMetadata.ModEntry> now = List.of(
				new ReplayMetadata.ModEntry("fabric-api", "0.141.6+1.21.11"),
				new ReplayMetadata.ModEntry("sodium", "0.8.1"),
				new ReplayMetadata.ModEntry("iris", "1.9.0"));
		ReplayDependencies dependencies = ReplayDependencies.compare(recorded(MODS, PACKS), now, PACKS);
		assertTrue(dependencies.modsDiffer());
		assertEquals(List.of("create"), dependencies.missingMods());
		assertEquals(List.of("iris"), dependencies.addedMods());
		assertEquals(List.of("sodium 0.8.0 -> 0.8.1"), dependencies.changedMods());
		assertFalse(dependencies.resourcePacksDiffer());
	}

	@Test
	void resourcePackOrderMatters() {
		assertTrue(ReplayDependencies.compare(recorded(MODS, PACKS), MODS, List.of("vanilla", "file/Faithful.zip", "fabric")).resourcePacksDiffer());
		assertTrue(ReplayDependencies.compare(recorded(MODS, PACKS), MODS, List.of("vanilla", "fabric")).resourcePacksDiffer());
	}
}
