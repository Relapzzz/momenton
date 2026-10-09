package com.relapzz.momenton.edit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.UnaryOperator;

public final class UndoStack {
	private final Deque<Timeline> undo = new ArrayDeque<>();
	private final Deque<Timeline> redo = new ArrayDeque<>();
	private Timeline timeline;

	public UndoStack(Timeline timeline) {
		this.timeline = timeline;
	}

	public Timeline timeline() {
		return timeline;
	}

	public void apply(UnaryOperator<Timeline> edit) {
		Timeline edited = edit.apply(timeline);
		if (edited.equals(timeline)) return;
		undo.push(timeline);
		redo.clear();
		timeline = edited;
	}

	public boolean canUndo() {
		return !undo.isEmpty();
	}

	public boolean canRedo() {
		return !redo.isEmpty();
	}

	public void undo() {
		if (undo.isEmpty()) return;
		redo.push(timeline);
		timeline = undo.pop();
	}

	public void redo() {
		if (redo.isEmpty()) return;
		undo.push(timeline);
		timeline = redo.pop();
	}
}
