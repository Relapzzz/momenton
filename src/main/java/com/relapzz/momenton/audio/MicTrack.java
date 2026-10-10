package com.relapzz.momenton.audio;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class MicTrack {
	public static final String ENTRY = "audio/mic.wav";

	private MicTrack() {
	}

	public static void attach(Path wav, Path replay) throws IOException {
		try (FileSystem zip = FileSystems.newFileSystem(replay, Map.of("compressionMethod", "STORED"))) {
			Path target = zip.getPath(ENTRY);
			Files.createDirectories(target.getParent());
			Files.copy(wav, target);
		}
		Files.delete(wav);
	}
}
