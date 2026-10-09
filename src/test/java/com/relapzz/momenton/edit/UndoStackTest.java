package com.relapzz.momenton.edit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoStackTest {
	static final List<UnaryOperator<Timeline>> GATE_EDITS = List.of(
			t -> t.split(0, 60),
			t -> t.split(1, 140),
			t -> t.move(2, 0),
			t -> t.trimStart(0, 150),
			t -> t.trimEnd(2, 120),
			t -> t.delete(1));

	@Test
	void undoAllThenRedoAll() {
		Timeline original = Timeline.of(200);
		UndoStack stack = new UndoStack(original);
		List<Timeline> history = new ArrayList<>(List.of(original));
		for (UnaryOperator<Timeline> edit : GATE_EDITS) {
			stack.apply(edit);
			history.add(stack.timeline());
		}
		assertEquals(List.of(new Clip(150, 200), new Clip(60, 120)), stack.timeline().clips());

		for (int i = history.size() - 2; i >= 0; i--) {
			stack.undo();
			assertEquals(history.get(i), stack.timeline());
		}
		assertFalse(stack.canUndo());
		for (int i = 1; i < history.size(); i++) {
			stack.redo();
			assertEquals(history.get(i), stack.timeline());
		}
		assertFalse(stack.canRedo());
	}

	@Test
	void aNewEditClearsRedo() {
		UndoStack stack = new UndoStack(Timeline.of(200));
		stack.apply(t -> t.split(0, 100));
		stack.undo();
		assertTrue(stack.canRedo());
		stack.apply(t -> t.split(0, 50));
		assertFalse(stack.canRedo());
		assertEquals(List.of(new Clip(0, 50), new Clip(50, 200)), stack.timeline().clips());
	}

	@Test
	void editsThatChangeNothingAreNotRecorded() {
		UndoStack stack = new UndoStack(Timeline.of(200));
		stack.apply(t -> t.split(0, 0));
		stack.apply(t -> t.delete(0));
		assertFalse(stack.canUndo());
	}

	@Test
	void undoAndRedoOnEmptyStacksDoNothing() {
		Timeline original = Timeline.of(200);
		UndoStack stack = new UndoStack(original);
		stack.undo();
		stack.redo();
		assertEquals(original, stack.timeline());
	}
}
