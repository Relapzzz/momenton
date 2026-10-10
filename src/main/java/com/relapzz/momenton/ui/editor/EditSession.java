package com.relapzz.momenton.ui.editor;

import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.edit.Clip;
import com.relapzz.momenton.edit.HudToggles;
import com.relapzz.momenton.edit.Project;
import com.relapzz.momenton.edit.Timeline;
import com.relapzz.momenton.edit.TimelineResolver;
import com.relapzz.momenton.edit.UndoStack;
import com.relapzz.momenton.playback.ReplaySession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

public final class EditSession {
	private static EditSession active;

	private final Path replay;
	private final UndoStack edits;
	private final EditorScreen screen;
	private HudToggles hud;
	private TimelineResolver resolver;
	private Timeline resolved;
	private boolean wanted = true;
	private boolean started;
	private int clipIndex;
	private int selected;

	private EditSession(Path replay, Project project) {
		this.replay = replay;
		this.edits = new UndoStack(project.timeline());
		this.hud = project.hud();
		this.screen = new EditorScreen(this);
	}

	public static void open(MinecraftClient client, Path replay, int durationTicks) {
		active = new EditSession(replay, load(replay, durationTicks));
		client.setScreen(null);
		ReplaySession.open(client, replay);
	}

	public static void toggle(MinecraftClient client) {
		if (!ReplaySession.isPlaying()) return;
		if (active == null) {
			Path replay = ReplaySession.file();
			active = new EditSession(replay, load(replay, ReplaySession.durationTicks()));
		}
		EditSession session = active;
		if (client.currentScreen == session.screen) {
			session.screen.close();
		} else {
			client.setScreen(session.screen);
		}
	}

	public static boolean hides(HudToggles.Element element) {
		EditSession session = active;
		return session != null && ReplaySession.isPlaying() && !session.hud.shows(element);
	}

	public static void onClientTick(MinecraftClient client) {
		EditSession session = active;
		if (session == null) return;
		if (!ReplaySession.isPlaying()) {
			if (session.started) active = null;
			return;
		}
		session.started = true;
		Screen current = client.currentScreen;
		if (session.wanted && (current == null || current instanceof HandledScreen<?> || current instanceof ChatScreen)) {
			client.setScreen(session.screen);
		}
	}

	private static Project load(Path replay, int durationTicks) {
		Path file = Project.fileFor(replay);
		if (Files.exists(file)) {
			try {
				return Project.load(file);
			} catch (IOException | RuntimeException e) {
				Momenton.LOGGER.error("Could not read {}, starting a new edit", file, e);
			}
		}
		return Project.create(replay, durationTicks);
	}

	Path replay() {
		return replay;
	}

	Timeline timeline() {
		return edits.timeline();
	}

	HudToggles hud() {
		return hud;
	}

	void toggleHud(HudToggles.Element element) {
		hud = hud.toggle(element);
	}

	int selected() {
		return selected;
	}

	void select(int index) {
		selected = index;
	}

	boolean canUndo() {
		return edits.canUndo();
	}

	boolean canRedo() {
		return edits.canRedo();
	}

	void shown() {
		wanted = true;
		relocate();
	}

	void dismissed() {
		wanted = false;
	}

	boolean save() {
		try {
			new Project(replay.getFileName().toString(), edits.timeline(), hud).save(Project.fileFor(replay));
			return true;
		} catch (IOException e) {
			Momenton.LOGGER.error("Could not save {}", Project.fileFor(replay), e);
			return false;
		}
	}

	void edit(UnaryOperator<Timeline> edit) {
		edits.apply(edit);
		relocate();
	}

	void undo() {
		edits.undo();
		relocate();
	}

	void redo() {
		edits.redo();
		relocate();
	}

	void split() {
		int tick = ReplaySession.currentTick();
		int index = clipIndex;
		edit(timeline -> timeline.split(index, tick));
	}

	void deleteSelected() {
		int index = selected;
		edit(timeline -> timeline.delete(index));
	}

	int length() {
		return edits.timeline().length();
	}

	int start(int index) {
		return (int) Math.round(resolver().startSeconds(index) * TimelineResolver.TICKS_PER_SECOND);
	}

	int end(int index) {
		return (int) Math.round(resolver().endSeconds(index) * TimelineResolver.TICKS_PER_SECOND);
	}

	int playhead() {
		Clip clip = edits.timeline().clips().get(clipIndex);
		return start(clipIndex) + Math.clamp(ReplaySession.currentTick() - clip.startTick(), 0, clip.length() - 1);
	}

	void seek(int position) {
		TimelineResolver.Position target = resolver().resolve(Math.clamp(position, 0, length() - 1) / (double) TimelineResolver.TICKS_PER_SECOND);
		clipIndex = target.clipIndex();
		ReplaySession.seek(target.sourceTick());
	}

	void step(int ticks) {
		if (!ReplaySession.isPaused()) ReplaySession.togglePause();
		seek(playhead() + ticks);
	}

	void togglePlay() {
		List<Clip> clips = edits.timeline().clips();
		if (ReplaySession.isPaused() && clipIndex == clips.size() - 1 && ReplaySession.currentTick() >= last(clips.get(clipIndex))) {
			seek(0);
		}
		ReplaySession.togglePause();
	}

	void follow() {
		List<Clip> clips = edits.timeline().clips();
		Clip clip = clips.get(clipIndex);
		int tick = ReplaySession.currentTick();
		if (tick < clip.startTick()) {
			relocate();
			return;
		}
		if (tick < last(clip) || ReplaySession.isPaused()) return;
		if (clipIndex + 1 < clips.size()) {
			clipIndex++;
			int next = clips.get(clipIndex).startTick();
			if (next != tick + 1) ReplaySession.seek(next - 1);
		} else {
			ReplaySession.togglePause();
		}
	}

	List<Clip> exportRanges() {
		return resolver().exportRanges();
	}

	private void relocate() {
		List<Clip> clips = edits.timeline().clips();
		clipIndex = Math.min(clipIndex, clips.size() - 1);
		selected = Math.min(selected, clips.size() - 1);
		int tick = ReplaySession.currentTick();
		Clip current = clips.get(clipIndex);
		if (contains(current, tick)) return;
		for (int i = 0; i < clips.size(); i++) {
			if (contains(clips.get(i), tick)) {
				clipIndex = i;
				return;
			}
		}
		ReplaySession.seek(current.startTick());
	}

	private static boolean contains(Clip clip, int tick) {
		return tick >= clip.startTick() && tick < clip.endTick();
	}

	private static int last(Clip clip) {
		return clip.endTick() - 1;
	}

	private TimelineResolver resolver() {
		if (resolved != edits.timeline()) {
			resolved = edits.timeline();
			resolver = new TimelineResolver(resolved);
		}
		return resolver;
	}
}
