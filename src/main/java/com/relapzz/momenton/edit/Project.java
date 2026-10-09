package com.relapzz.momenton.edit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record Project(String replay, Timeline timeline) {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static Project create(Path replayFile, int durationTicks) {
		return new Project(replayFile.getFileName().toString(), Timeline.of(durationTicks));
	}

	public static Path fileFor(Path replayFile) {
		return replayFile.resolveSibling(replayFile.getFileName() + ".json");
	}

	public static Project load(Path file) throws IOException {
		return GSON.fromJson(Files.readString(file), Project.class);
	}

	public void save(Path file) throws IOException {
		Files.writeString(file, GSON.toJson(this));
	}
}
