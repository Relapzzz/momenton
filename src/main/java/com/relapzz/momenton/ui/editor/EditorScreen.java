package com.relapzz.momenton.ui.editor;

import com.relapzz.momenton.config.MomentonKeybinds;
import com.relapzz.momenton.edit.Clip;
import com.relapzz.momenton.edit.HudToggles;
import com.relapzz.momenton.format.FrameTrack;
import com.relapzz.momenton.playback.PovDriver;
import com.relapzz.momenton.playback.ReplaySession;
import com.relapzz.momenton.playback.UiStateDriver;
import com.relapzz.momenton.ui.ExportScreen;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

public class EditorScreen extends Screen {
	private static final int MARGIN = 6;
	private static final int EDGE = 3;
	private static final int TOGGLE_WIDTH = 110;

	private enum Drag {
		NONE, TRIM_START, TRIM_END, MOVE
	}

	private final EditSession session;

	private ButtonWidget play;
	private ButtonWidget delete;
	private ButtonWidget undo;
	private ButtonWidget redo;
	private boolean hudPanel;
	private int savedTicks;
	private Drag drag = Drag.NONE;
	private int dragClip;
	private double pressX;
	private double dragX;
	private boolean moved;

	EditorScreen(EditSession session) {
		super(Text.translatable("screen.momenton.editor"));
		this.session = session;
	}

	@Override
	protected void init() {
		session.shown();
		UiStateDriver.resync(client);
		int y = height - 56;
		int x = MARGIN;
		button(x, y, 20, Text.literal("<"), b -> session.step(-1));
		play = button(x += 22, y, 44, Text.empty(), b -> session.togglePlay());
		button(x += 46, y, 20, Text.literal(">"), b -> session.step(1));
		button(x += 26, y, 40, Text.translatable("screen.momenton.editor.split"), b -> session.split());
		delete = button(x += 42, y, 44, Text.translatable("screen.momenton.delete"), b -> session.deleteSelected());
		undo = button(x += 50, y, 36, Text.translatable("screen.momenton.editor.undo"), b -> session.undo());
		redo = button(x += 38, y, 36, Text.translatable("screen.momenton.editor.redo"), b -> session.redo());
		button(x += 44, y, 36, Text.translatable("screen.momenton.editor.hud"), b -> {
			hudPanel = !hudPanel;
			clearAndInit();
		});
		button(x += 38, y, 36, Text.translatable("screen.momenton.editor.save"), b -> save());
		button(x += 38, y, 44, Text.translatable("screen.momenton.export"), b -> export());

		if (hudPanel) {
			HudToggles.Element[] elements = HudToggles.Element.values();
			for (int i = 0; i < elements.length; i++) {
				HudToggles.Element element = elements[i];
				addDrawableChild(CyclingButtonWidget.onOffBuilder(session.hud().shows(element))
						.build(width - MARGIN - (2 - i % 2) * (TOGGLE_WIDTH + 2), toggleTop() + i / 2 * 22, TOGGLE_WIDTH, 20,
								Text.translatable("screen.momenton.editor.hud." + element.name().toLowerCase(Locale.ROOT)),
								(button, shown) -> session.toggleHud(element)));
			}
		}
	}

	@Override
	public void tick() {
		Screen hosted = session.embedded();
		if (hosted != null) hosted.tick();
		session.follow();
		if (savedTicks > 0) savedTicks--;
	}

	@Override
	public void resize(int width, int height) {
		super.resize(width, height);
		Screen hosted = session.embedded();
		if (hosted != null) hosted.resize(width, height);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		play.setMessage(Text.translatable(ReplaySession.isPaused() ? "screen.momenton.play" : "screen.momenton.editor.pause"));
		delete.active = session.timeline().clips().size() > 1;
		undo.active = session.canUndo();
		redo.active = session.canRedo();

		Screen hosted = session.embedded();
		if (hosted != null) {
			FrameTrack.Pose cursor = PovDriver.screenCursor(hosted, client.getRenderTickCounter().getTickProgress(true));
			hosted.renderWithTooltip(context, cursor == null ? mouseX : (int) (cursor.mouseX() * width),
					cursor == null ? mouseY : (int) (cursor.mouseY() * height), deltaTicks);
			context.createNewRootLayer();
		}
		context.fill(0, height - 60, width, height, 0xB0000000);
		if (hudPanel) {
			context.fill(width - MARGIN - 2 * (TOGGLE_WIDTH + 2) - 2, toggleTop() - 4, width, height - 60, 0xB0000000);
		}
		renderTimeline(context);
		super.render(context, mouseX, mouseY, deltaTicks);

		String time = time(session.playhead()) + " / " + time(session.length());
		context.drawTextWithShadow(textRenderer, time, width - MARGIN - textRenderer.getWidth(time), height - 50, 0xFFFFFFFF);
		if (savedTicks > 0) {
			Text saved = Text.translatable("screen.momenton.editor.saved");
			context.drawTextWithShadow(textRenderer, saved, width - MARGIN - textRenderer.getWidth(saved), height - 72, 0xFF55FF55);
		}
	}

	private void renderTimeline(DrawContext context) {
		int top = stripTop();
		int bottom = top + 22;
		List<Clip> clips = session.timeline().clips();
		context.fill(MARGIN, top, width - MARGIN, bottom, 0xFF202020);
		for (int i = 0; i < clips.size(); i++) {
			int left = x(session.start(i));
			int right = x(session.end(i));
			boolean selected = i == session.selected();
			context.fill(left, top, right, bottom, selected ? 0xFF5A9BE6 : i % 2 == 0 ? 0xFF3C6EB4 : 0xFF2C5590);
			context.fill(left, top, left + 1, bottom, 0xFF101010);
			if (selected) context.drawStrokedRectangle(left, top, right - left, bottom - top, 0xFFFFFFFF);
			String label = seconds(clips.get(i).length());
			if (textRenderer.getWidth(label) + 6 < right - left) {
				context.drawTextWithShadow(textRenderer, label, left + 4, top + 7, 0xFFFFFFFF);
			}
		}

		if (drag == Drag.TRIM_START || drag == Drag.TRIM_END) {
			int at = (int) dragX;
			context.fill(at, top - 2, at + 1, bottom + 2, 0xFFFFFF55);
		} else if (drag == Drag.MOVE && moved) {
			int offset = (int) (dragX - pressX);
			context.fill(x(session.start(dragClip)) + offset, top + 4, x(session.end(dragClip)) + offset, bottom - 4, 0x80FFFFFF);
		}

		int head = x(session.playhead());
		context.fill(head - 1, top - 3, head + 1, bottom + 3, 0xFFFF4040);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (super.mouseClicked(click, doubled)) return true;
		int top = stripTop();
		if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || click.y() < top || click.y() >= top + 22) return false;
		int index = clipAt(click.x());
		if (index < 0) return false;
		session.select(index);
		dragClip = index;
		pressX = dragX = click.x();
		moved = false;
		int left = x(session.start(index));
		int right = x(session.end(index));
		drag = click.x() - left <= EDGE ? Drag.TRIM_START : right - click.x() <= EDGE ? Drag.TRIM_END : Drag.MOVE;
		return true;
	}

	@Override
	public boolean mouseDragged(Click click, double deltaX, double deltaY) {
		if (drag == Drag.NONE) return super.mouseDragged(click, deltaX, deltaY);
		dragX = click.x();
		if (Math.abs(dragX - pressX) > EDGE) moved = true;
		return true;
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (drag == Drag.NONE) return super.mouseReleased(click);
		Drag released = drag;
		drag = Drag.NONE;
		dragX = click.x();
		int index = dragClip;
		Clip clip = session.timeline().clips().get(index);
		int delta = (int) Math.round((dragX - pressX) / scale());
		switch (released) {
			case TRIM_START -> session.edit(timeline -> timeline.trimStart(index, clip.startTick() + delta));
			case TRIM_END -> session.edit(timeline -> timeline.trimEnd(index, clip.endTick() + delta));
			default -> {
				if (moved) {
					int to = insertionIndex(dragX);
					session.edit(timeline -> timeline.move(index, to));
					session.select(to);
				} else {
					session.seek((int) Math.floor((dragX - MARGIN) / scale()));
				}
			}
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (input.hasCtrlOrCmd()) {
			switch (input.key()) {
				case GLFW.GLFW_KEY_Z -> {
					if (input.hasShift()) {
						session.redo();
					} else {
						session.undo();
					}
					return true;
				}
				case GLFW.GLFW_KEY_Y -> {
					session.redo();
					return true;
				}
				case GLFW.GLFW_KEY_S -> {
					save();
					return true;
				}
				default -> {
				}
			}
		}
		if (super.keyPressed(input)) return true;
		MomentonKeybinds.pressInScreen(client, input);
		return false;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public boolean showsStatusEffects() {
		Screen hosted = session.embedded();
		return hosted != null && hosted.showsStatusEffects();
	}

	@Override
	public boolean deferSubtitles() {
		Screen hosted = session.embedded();
		return hosted != null && hosted.deferSubtitles();
	}

	@Override
	public void close() {
		session.dismissed();
		client.setScreen(session.release());
		UiStateDriver.resync(client);
	}

	@Override
	public void removed() {
		drag = Drag.NONE;
		session.save();
		session.drop();
	}

	private void save() {
		if (session.save()) savedTicks = 40;
	}

	private void export() {
		session.dismissed();
		client.setScreen(new ExportScreen(this, session.replay(), session.timeline().sourceTicks(), session.exportRanges()));
	}

	private ButtonWidget button(int x, int y, int buttonWidth, Text text, ButtonWidget.PressAction action) {
		return addDrawableChild(ButtonWidget.builder(text, action).dimensions(x, y, buttonWidth, 20).build());
	}

	private int stripTop() {
		return height - 30;
	}

	private int toggleTop() {
		return height - 64 - 5 * 22;
	}

	private double scale() {
		return (width - 2 * MARGIN) / (double) session.length();
	}

	private int x(int position) {
		return MARGIN + (int) Math.round(position * scale());
	}

	private int clipAt(double mouseX) {
		for (int i = 0; i < session.timeline().clips().size(); i++) {
			if (mouseX >= x(session.start(i)) && mouseX < x(session.end(i))) return i;
		}
		return -1;
	}

	private int insertionIndex(double mouseX) {
		int to = 0;
		for (int i = 0; i < session.timeline().clips().size(); i++) {
			if (i != dragClip && (x(session.start(i)) + x(session.end(i))) / 2.0 < mouseX) to++;
		}
		return to;
	}

	private static String time(int ticks) {
		int centis = ticks * 5;
		return String.format(Locale.ROOT, "%d:%02d.%02d", centis / 6000, centis / 100 % 60, centis % 100);
	}

	private static String seconds(int ticks) {
		return String.format(Locale.ROOT, "%.1fs", ticks / 20.0);
	}
}
