package com.relapzz.momenton;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class ExportTemporaries {
	private ExportTemporaries() {
	}

	public static void deleteOrphans() {
		Thread.startVirtualThread(ExportTemporaries::delete);
	}

	private static void delete() {
		Path directory = FabricLoader.getInstance().getGameDir().resolve("momenton").resolve("exports");
		if (!Files.isDirectory(directory)) {
			return;
		}
		try (Stream<Path> files = Files.list(directory)) {
			for (Path file : files.filter(ExportTemporaries::isTemporary).toList()) {
				Files.delete(file);
				Momenton.LOGGER.info("Deleted {} left by an interrupted export", file.getFileName());
			}
		} catch (IOException e) {
			Momenton.LOGGER.warn("Could not clean up {}", directory, e);
		}
	}

	private static boolean isTemporary(Path file) {
		String name = file.getFileName().toString();
		return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
				&& (name.startsWith("momenton-video-")
				|| (name.startsWith("momenton-audio-") || name.startsWith("momenton-mic-")) && name.endsWith(".pcm"));
	}
}
