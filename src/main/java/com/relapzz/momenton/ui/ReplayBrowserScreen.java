package com.relapzz.momenton.ui;

import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.format.ReplayMetadata;
import com.relapzz.momenton.format.ReplayReader;
import com.relapzz.momenton.playback.ReplaySession;
import com.relapzz.momenton.ui.editor.EditSession;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Picks a recording to play or export. Vanilla widgets, because the editor's own toolkit is Phase 4 and this
 * only has to be usable.
 */
public class ReplayBrowserScreen extends Screen {
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault());

	private final Screen parent;
	private final List<Entry> entries = new ArrayList<>();

	private int selected = -1;
	private ButtonWidget play;
	private ButtonWidget edit;
	private ButtonWidget export;
	private ButtonWidget delete;

	public ReplayBrowserScreen(Screen parent) {
		super(Text.translatable("screen.momenton.replays"));
		this.parent = parent;
	}

	public static Path directory() {
		return FabricLoader.getInstance().getGameDir().resolve("momenton").resolve("recordings");
	}

	@Override
	protected void init() {
		load();

		int top = 40;
		for (int i = 0; i < entries.size(); i++) {
			Entry entry = entries.get(i);
			int index = i;
			addDrawableChild(ButtonWidget.builder(entry.label(), button -> select(index))
					.dimensions(width / 2 - 155, top + i * 22, 310, 20)
					.build());
		}

		play = addDrawableChild(ButtonWidget.builder(Text.translatable("screen.momenton.play"), button -> play())
				.dimensions(width / 2 - 155, height - 52, 75, 20)
				.build());
		edit = addDrawableChild(ButtonWidget.builder(Text.translatable("screen.momenton.edit"), button -> edit())
				.dimensions(width / 2 - 77, height - 52, 75, 20)
				.build());
		export = addDrawableChild(ButtonWidget.builder(Text.translatable("screen.momenton.export"), button -> export())
				.dimensions(width / 2 + 1, height - 52, 75, 20)
				.build());
		delete = addDrawableChild(ButtonWidget.builder(Text.translatable("screen.momenton.delete"), button -> delete())
				.dimensions(width / 2 + 79, height - 52, 75, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.back"), button -> close())
				.dimensions(width / 2 - 100, height - 28, 200, 20)
				.build());

		updateButtons();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFFFFFFFF);
		if (entries.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer,
					Text.translatable("screen.momenton.empty"), width / 2, height / 2, 0xFFA0A0A0);
		}
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	private void select(int index) {
		selected = index;
		updateButtons();
	}

	private void updateButtons() {
		boolean chosen = selected >= 0 && selected < entries.size();
		play.active = chosen && entries.get(selected).playable();
		edit.active = play.active;
		export.active = play.active;
		delete.active = chosen;
	}

	private void play() {
		Path file = entries.get(selected).file();
		client.setScreen(null);
		ReplaySession.open(client, file);
	}

	private void edit() {
		Entry entry = entries.get(selected);
		EditSession.open(client, entry.file(), entry.durationTicks());
	}

	private void export() {
		Entry entry = entries.get(selected);
		client.setScreen(new ExportScreen(this, entry.file(), entry.durationTicks()));
	}

	private void delete() {
		try {
			Files.deleteIfExists(entries.get(selected).file());
		} catch (IOException e) {
			Momenton.LOGGER.error("Could not delete the replay", e);
		}
		selected = -1;
		clearChildren();
		init();
	}

	/** Reads each replay's metadata so the list can show length and date rather than a filename. */
	private void load() {
		entries.clear();
		try (Stream<Path> files = Files.list(directory())) {
			for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".momenton"))
					.sorted(Comparator.comparing(Path::getFileName).reversed()).toList()) {
				entries.add(describe(file));
			}
		} catch (IOException e) {
			Momenton.LOGGER.warn("Could not list {}", directory(), e);
		}
	}

	private static Entry describe(Path file) {
		try (ReplayReader reader = new ReplayReader(file)) {
			ReplayMetadata metadata = reader.metadata();
			boolean playable = reader.hasSetup() && reader.hasSnapshot(0);
			int seconds = metadata.durationTicks() / 20;
			String summary = String.format("%s   %d:%02d   %s",
					metadata.describeWorld(),
					seconds / 60, seconds % 60,
					WHEN.format(Instant.ofEpochMilli(metadata.createdAtEpochMs())));
			return new Entry(file, Text.literal(summary + (playable ? "" : "   (too old to play)")), playable, metadata.durationTicks());
		} catch (IOException e) {
			return new Entry(file, Text.literal(file.getFileName() + "   (unreadable)"), false, 0);
		}
	}

	private record Entry(Path file, Text label, boolean playable, int durationTicks) {
	}
}
