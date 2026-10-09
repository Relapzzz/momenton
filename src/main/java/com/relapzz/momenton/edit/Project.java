package com.relapzz.momenton.edit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

public record Project(String replay, Timeline timeline, HudToggles hud) {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().registerTypeAdapter(HudToggles.class, new HiddenElements()).create();

	public Project {
		if (hud == null) hud = HudToggles.ALL_SHOWN;
	}

	public Project(String replay, Timeline timeline) {
		this(replay, timeline, HudToggles.ALL_SHOWN);
	}

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

	private static final class HiddenElements extends TypeAdapter<HudToggles> {
		@Override
		public void write(JsonWriter out, HudToggles hud) throws IOException {
			if (hud.hidden().isEmpty()) {
				out.nullValue();
				return;
			}
			out.beginArray();
			for (HudToggles.Element element : HudToggles.Element.values()) {
				if (!hud.shows(element)) out.value(element.name());
			}
			out.endArray();
		}

		@Override
		public HudToggles read(JsonReader in) throws IOException {
			Set<HudToggles.Element> hidden = EnumSet.noneOf(HudToggles.Element.class);
			in.beginArray();
			while (in.hasNext()) hidden.add(HudToggles.Element.valueOf(in.nextString()));
			in.endArray();
			return new HudToggles(hidden);
		}
	}
}
