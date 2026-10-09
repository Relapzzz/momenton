package com.relapzz.momenton.edit;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectTest {
	@TempDir
	Path dir;

	@Test
	void projectFileSitsBesideTheReplay() {
		Path replay = dir.resolve("2026-10-09_12.00.00.momenton");
		assertEquals(dir.resolve("2026-10-09_12.00.00.momenton.json"), Project.fileFor(replay));
		assertEquals(new Project("2026-10-09_12.00.00.momenton", Timeline.of(400)), Project.create(replay, 400));
	}

	@Test
	void jsonRoundTrip() throws IOException {
		Project project = new Project("a.momenton", new Timeline(1000, List.of(new Clip(500, 540), new Clip(100, 120))));
		Path file = Project.fileFor(dir.resolve("a.momenton"));
		project.save(file);
		assertEquals(project, Project.load(file));
		assertEquals(JsonParser.parseString("""
				{"replay": "a.momenton", "timeline": {"sourceTicks": 1000, "clips": [
					{"startTick": 500, "endTick": 540}, {"startTick": 100, "endTick": 120}]}}
				"""), JsonParser.parseString(Files.readString(file)));
	}

	@Test
	void invalidFilesAreRejectedOnLoad() throws IOException {
		Path file = dir.resolve("bad.momenton.json");
		Files.writeString(file, """
				{"replay": "bad.momenton", "timeline": {"sourceTicks": 100, "clips": [{"startTick": 50, "endTick": 50}]}}
				""");
		assertInstanceOf(IllegalArgumentException.class, assertThrows(RuntimeException.class, () -> Project.load(file)).getCause());
	}

	@Test
	void phaseSixGate() throws IOException {
		Path replay = dir.resolve("gate.momenton");
		UndoStack stack = new UndoStack(Project.create(replay, 200).timeline());
		Timeline original = stack.timeline();
		UndoStackTest.GATE_EDITS.forEach(stack::apply);
		Timeline edited = stack.timeline();
		new Project(replay.getFileName().toString(), edited).save(Project.fileFor(replay));

		while (stack.canUndo()) stack.undo();
		assertEquals(original, stack.timeline());

		Project reloaded = Project.load(Project.fileFor(replay));
		assertEquals(edited, reloaded.timeline());
		assertEquals(List.of(new Clip(150, 200), new Clip(60, 120)), new TimelineResolver(reloaded.timeline()).exportRanges());
	}
}
