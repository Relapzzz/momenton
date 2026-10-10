package com.relapzz.momenton.format;

import com.relapzz.momenton.Momenton;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public record ReplayDependencies(List<String> missingMods, List<String> addedMods, List<String> changedMods,
		boolean resourcePacksDiffer) {
	private static final Set<String> SKIPPED = Set.of("fabricloader", Momenton.MOD_ID);

	public static ReplayDependencies compare(ReplayMetadata recorded, List<ReplayMetadata.ModEntry> mods, List<String> resourcePacks) {
		List<String> missing = new ArrayList<>();
		List<String> added = new ArrayList<>();
		List<String> changed = new ArrayList<>();
		if (recorded.mods() != null) {
			Map<String, String> then = versions(recorded.mods());
			Map<String, String> now = versions(mods);
			then.forEach((id, version) -> {
				String current = now.get(id);
				if (current == null) {
					missing.add(id);
				} else if (!current.equals(version)) {
					changed.add(id + " " + version + " -> " + current);
				}
			});
			now.keySet().stream().filter(id -> !then.containsKey(id)).forEach(added::add);
		}
		boolean packsDiffer = recorded.resourcePacks() != null && !recorded.resourcePacks().equals(resourcePacks);
		return new ReplayDependencies(missing, added, changed, packsDiffer);
	}

	public boolean modsDiffer() {
		return !missingMods.isEmpty() || !addedMods.isEmpty() || !changedMods.isEmpty();
	}

	public static List<ReplayMetadata.ModEntry> loadedMods() {
		return FabricLoader.getInstance().getAllMods().stream()
				.filter(container -> container.getContainingMod().isEmpty())
				.map(ModContainer::getMetadata)
				.filter(metadata -> !metadata.getType().equals("builtin")
						&& !metadata.containsCustomValue("fabric-api:module-lifecycle")
						&& !SKIPPED.contains(metadata.getId()))
				.sorted(Comparator.comparing(ModMetadata::getId))
				.map(metadata -> new ReplayMetadata.ModEntry(metadata.getId(), metadata.getVersion().getFriendlyString()))
				.toList();
	}

	public static List<String> enabledResourcePacks(MinecraftClient client) {
		return client.getResourcePackManager().getEnabledProfiles().stream()
				.filter(profile -> profile.getSource() != ResourcePackSource.SERVER)
				.map(ResourcePackProfile::getId)
				.toList();
	}

	private static Map<String, String> versions(List<ReplayMetadata.ModEntry> mods) {
		Map<String, String> versions = new TreeMap<>();
		mods.forEach(mod -> versions.put(mod.id(), mod.version()));
		return versions;
	}
}
