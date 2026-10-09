package com.relapzz.momenton.export;

import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.audio.LoopbackRenderer;
import com.relapzz.momenton.format.ReplayMetadata;
import com.relapzz.momenton.format.ReplayReader;
import com.relapzz.momenton.mixin.client.export.ChunkRenderingDataPreparerAccessor;
import com.relapzz.momenton.playback.ReplaySession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.BlockBufferBuilderPool;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.util.Window;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Future;

public final class ExportSession {
	private static final Identifier CURSOR = Momenton.id("textures/gui/cursor.png");

	private static ExportSession active;

	private final MinecraftClient client;
	private final ExportSettings settings;
	private final FfmpegProcess ffmpeg;
	private final Path video;
	private final LoopbackRenderer audio;
	private final FrameGrabber grabber;
	private final int guiScale;
	private final int frames;

	private boolean settling = true;
	private boolean cancelled;
	private int frame;
	private int target;
	private int reported;
	private RenderTickCounter counter;
	private IOException failure;

	private ExportSession(MinecraftClient client, ExportSettings settings, ReplayMetadata metadata, int frames, FfmpegProcess ffmpeg,
			Path video, LoopbackRenderer audio) {
		this.client = client;
		this.settings = settings;
		this.ffmpeg = ffmpeg;
		this.video = video;
		this.audio = audio;
		this.grabber = new FrameGrabber(settings.width(), settings.height());
		this.guiScale = guiScale(metadata, settings);
		this.frames = frames;
	}

	public static boolean isExporting() {
		return active != null;
	}

	public static boolean drawsCursor() {
		return active != null && active.settings.drawCursor();
	}

	public static void start(MinecraftClient client, ExportSettings settings) {
		if (active != null) {
			return;
		}
		if (client.world != null && !ReplaySession.isPlaying()) {
			report(client, false, "Leave the world before exporting");
			return;
		}

		ReplayMetadata metadata;
		try (ReplayReader reader = new ReplayReader(settings.replay())) {
			metadata = reader.metadata();
		} catch (IOException e) {
			Momenton.LOGGER.error("Could not read {}", settings.replay(), e);
			report(client, false, "Could not read the replay");
			return;
		}

		int end = Math.min(settings.endTick(), metadata.durationTicks() - 1);
		int frames = (int) (((long) (end - settings.startTick()) * settings.fps() + 19) / 20);
		if (frames <= 0) {
			report(client, false, "Nothing to export between those ticks");
			return;
		}

		Path output = settings.output();
		Path video = output.resolveSibling("momenton-video-" + output.getFileName());
		FfmpegProcess ffmpeg;
		try {
			ffmpeg = FfmpegProcess.start(settings.ffmpeg(), settings.width(), settings.height(), settings.fps(), video);
		} catch (IOException e) {
			Momenton.LOGGER.error("Could not start FFmpeg", e);
			report(client, false, "Could not start FFmpeg: " + e.getMessage());
			return;
		}

		LoopbackRenderer audio;
		try {
			audio = LoopbackRenderer.start(client, output.resolveSibling("momenton-audio-" + output.getFileName() + ".pcm"), settings.fps());
		} catch (IOException e) {
			ffmpeg.close();
			delete(video);
			Momenton.LOGGER.error("Could not create the audio file", e);
			report(client, false, "Could not create the audio file: " + e.getMessage());
			return;
		}

		ReplaySession.open(client, settings.replay());
		if (!ReplaySession.isPlaying() || client.player == null) {
			ffmpeg.close();
			audio.close();
			delete(video);
			report(client, false, "Could not open the replay");
			return;
		}
		if (!ReplaySession.isPaused()) {
			ReplaySession.togglePause();
		}
		ReplaySession.seek(settings.startTick());

		active = new ExportSession(client, settings, metadata, frames, ffmpeg, video, audio);
		Window window = client.getWindow();
		window.setVsync(false);
		window.setFramebufferWidth(settings.width());
		window.setFramebufferHeight(settings.height());
		client.onResolutionChanged();
		Momenton.LOGGER.info("Exporting {} frames of {} at {}x{} {} fps to {}", frames, settings.replay().getFileName(),
				settings.width(), settings.height(), settings.fps(), settings.output());
	}

	public static void cancel() {
		if (active != null) {
			active.cancelled = true;
		}
	}

	public static boolean isSettling() {
		return active != null && active.settling;
	}

	public static int ticks(int ticks) {
		ExportSession session = active;
		if (session == null) {
			return ticks;
		}
		if (session.cancelled) {
			session.end(false, "Export cancelled");
			return ticks;
		}
		return session.settling ? ticks : session.beginFrame();
	}

	public static RenderTickCounter counter(RenderTickCounter counter) {
		ExportSession session = active;
		return session == null || session.settling ? counter : session.counter;
	}

	public static void afterRender() {
		ExportSession session = active;
		if (session != null) {
			session.rendered();
		}
	}

	public static int guiScale(int guiScale) {
		ExportSession session = active;
		return session == null ? guiScale : session.guiScale;
	}

	public static void drawCursor(DrawContext context, int x, int y) {
		Window window = MinecraftClient.getInstance().getWindow();
		float scale = window.getFramebufferHeight() / 720.0f / window.getScaleFactor();
		context.createNewRootLayer();
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(x, y);
		context.getMatrices().scale(scale);
		context.drawTexture(RenderPipelines.GUI_TEXTURED, CURSOR, 0, 0, 0, 0, 16, 16, 16, 16);
		context.getMatrices().popMatrix();
	}

	private static int guiScale(ReplayMetadata metadata, ExportSettings settings) {
		if (metadata.guiScale() == null) {
			return 0;
		}
		double ratio = Math.min((double) settings.width() / metadata.framebufferWidth(),
				(double) settings.height() / metadata.framebufferHeight());
		return Math.max(1, (int) Math.round(metadata.guiScale() * ratio));
	}

	private int beginFrame() {
		long ticks = (long) frame * 20;
		target = settings.startTick() + (int) (ticks / settings.fps());
		counter = new FrameCounter((float) (ticks % settings.fps()) / settings.fps(), 20.0f / settings.fps());
		return Math.max(0, target + 1 - ReplaySession.currentTick());
	}

	private void rendered() {
		if (!ReplaySession.isPlaying()) {
			finish();
			return;
		}
		if (settling) {
			if (!(client.currentScreen instanceof LevelLoadingScreen) && terrainBuilt()) {
				ReplaySession.seek(settings.startTick());
				ReplaySession.togglePause();
				audio.stopOneShots();
				settling = false;
			}
			return;
		}
		if (counter == null || ReplaySession.currentTick() != target + 1 || !terrainBuilt()) {
			return;
		}

		grabber.grab(client.getFramebuffer().getColorAttachment(), this::write);
		try {
			audio.render();
		} catch (IOException e) {
			failure = e;
		}
		frame++;
		if (failure != null) {
			fail(failure);
			return;
		}

		int percent = frame * 100 / frames;
		if (percent >= reported + 5) {
			reported = percent - percent % 5;
			Momenton.LOGGER.info("Export {}% ({} of {} frames)", reported, frame, frames);
		}
		if (frame == frames) {
			finish();
		}
	}

	private boolean terrainBuilt() {
		WorldRenderer renderer = client.worldRenderer;
		ChunkRenderingDataPreparerAccessor graph = (ChunkRenderingDataPreparerAccessor) renderer.getChunkRenderingDataPreparer();
		Future<?> update = graph.getTerrainUpdateFuture();
		BlockBufferBuilderPool buffers = client.getBufferBuilders().getBlockBufferBuildersPool();
		return renderer.isTerrainRenderComplete()
				&& buffers.getAvailableBuilderCount() == ((ChunkBufferPool) buffers).momenton$capacity()
				&& !graph.isTerrainUpdateScheduled()
				&& (update == null || update.isDone())
				&& !graph.getNeedsUpdate().get();
	}

	private void write(ByteBuffer rgba) {
		if (failure != null) {
			return;
		}
		try {
			ffmpeg.writeFrame(rgba);
		} catch (IOException e) {
			failure = e;
		}
	}

	private void finish() {
		try {
			grabber.flush();
			if (failure != null) {
				throw failure;
			}
			ffmpeg.finish();
			FfmpegProcess.mux(settings.ffmpeg(), video, audio.path(), settings.output());
		} catch (IOException e) {
			fail(e);
			return;
		}
		end(true, "Saved " + settings.output());
	}

	private void fail(IOException e) {
		Momenton.LOGGER.error("Export failed", e);
		end(false, "Export failed: " + e.getMessage());
	}

	private void end(boolean saved, String message) {
		active = null;
		grabber.close();
		if (!saved) {
			ffmpeg.close();
		}
		audio.close();
		delete(video);

		Window window = client.getWindow();
		int[] width = new int[1];
		int[] height = new int[1];
		GLFW.glfwGetFramebufferSize(window.getHandle(), width, height);
		window.setFramebufferWidth(Math.max(1, width[0]));
		window.setFramebufferHeight(Math.max(1, height[0]));
		client.onResolutionChanged();
		window.setVsync(client.options.getEnableVsync().getValue());

		if (ReplaySession.isPlaying() && !ReplaySession.isPaused()) {
			ReplaySession.togglePause();
		}
		Momenton.LOGGER.info("Export ended after {} of {} frames: {}", frame, frames, message);
		report(client, saved, message);
	}

	private static void delete(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException e) {
			Momenton.LOGGER.warn("Could not delete {}", path, e);
		}
	}

	private static void report(MinecraftClient client, boolean saved, String message) {
		SystemToast.show(client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
				Text.literal(saved ? "Export finished" : "Export stopped"), Text.literal(message));
	}

	private record FrameCounter(float tickProgress, float deltaTicks) implements RenderTickCounter {
		@Override
		public float getDynamicDeltaTicks() {
			return deltaTicks;
		}

		@Override
		public float getTickProgress(boolean ignoreFreeze) {
			return tickProgress;
		}

		@Override
		public float getFixedDeltaTicks() {
			return deltaTicks;
		}
	}
}
