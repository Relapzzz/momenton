package com.relapzz.momenton.edit;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudTogglesTest {
	@TempDir
	Path dir;

	@Test
	void hiddenElementsRoundTrip() throws IOException {
		HudToggles hud = HudToggles.ALL_SHOWN.toggle(HudToggles.Element.CHAT).toggle(HudToggles.Element.CROSSHAIR);
		Project project = new Project("a.momenton", Timeline.of(400), hud);
		Path file = dir.resolve("a.momenton.json");
		project.save(file);

		Project loaded = Project.load(file);
		assertEquals(project, loaded);
		assertFalse(loaded.hud().shows(HudToggles.Element.CHAT));
		assertFalse(loaded.hud().shows(HudToggles.Element.CROSSHAIR));
		assertTrue(loaded.hud().shows(HudToggles.Element.HOTBAR));
		assertEquals(JsonParser.parseString("[\"CROSSHAIR\", \"CHAT\"]"),
				JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("hud"));
	}

	@Test
	void projectWithoutHudShowsEverything() throws IOException {
		Path file = dir.resolve("old.momenton.json");
		Files.writeString(file, """
				{"replay": "old.momenton", "timeline": {"sourceTicks": 100, "clips": [{"startTick": 0, "endTick": 100}]}}
				""");
		Project loaded = Project.load(file);
		assertEquals(HudToggles.ALL_SHOWN, loaded.hud());
		for (HudToggles.Element element : HudToggles.Element.values()) assertTrue(loaded.hud().shows(element));
	}

	@Test
	void toggleTwiceShowsAgain() {
		HudToggles hud = HudToggles.ALL_SHOWN.toggle(HudToggles.Element.SCOREBOARD);
		assertEquals(Set.of(HudToggles.Element.SCOREBOARD), hud.hidden());
		assertEquals(HudToggles.ALL_SHOWN, hud.toggle(HudToggles.Element.SCOREBOARD));
	}
}
