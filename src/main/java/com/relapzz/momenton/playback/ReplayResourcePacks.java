package com.relapzz.momenton.playback;

import com.relapzz.momenton.format.ReplayMetadata;
import com.relapzz.momenton.format.ReplayReader;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.server.ServerResourcePackLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ReplayResourcePacks {
	private ReplayResourcePacks() {
	}

	static void load(MinecraftClient client, ReplayReader reader) throws IOException {
		List<ReplayMetadata.ServerPack> packs = reader.metadata().serverPacks();
		if (packs == null) {
			return;
		}
		Path cache = FabricLoader.getInstance().getGameDir().resolve("momenton").resolve("cache").resolve("packs");
		Files.createDirectories(cache);
		Map<UUID, Path> files = new LinkedHashMap<>();
		for (ReplayMetadata.ServerPack pack : packs) {
			files.put(UUID.fromString(pack.id()), extract(reader, pack, cache));
		}
		ServerResourcePackLoader loader = client.getServerResourcePackProvider();
		loader.initWorldPack();
		files.forEach(loader::addResourcePack);
	}

	private static Path extract(ReplayReader reader, ReplayMetadata.ServerPack pack, Path cache) throws IOException {
		Path file = cache.resolve(pack.sha256() + ".zip");
		if (Files.notExists(file)) {
			Path part = cache.resolve(pack.sha256() + ".part");
			try (InputStream in = reader.serverPack(pack)) {
				Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
			}
			Files.move(part, file, StandardCopyOption.ATOMIC_MOVE);
		}
		return file;
	}
}
