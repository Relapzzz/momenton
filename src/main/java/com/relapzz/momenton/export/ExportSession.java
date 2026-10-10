package com.relapzz.momenton.export;

import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.audio.LoopbackRenderer;
import com.relapzz.momenton.edit.Clip;
import com.relapzz.momenton.format.ReplayMetadata;
import com.relapzz.momenton.format.ReplayReader;
import com.relapzz.momenton.mixin.client.export.ChunkRenderingDataPreparerAccessor;
import com.relapzz.momenton.mixin.client.export.ClientWorldAccessor;
import com.relapzz.momenton.playback.ReplaySession;
import com.relapzz.momenton.ui.ExportFailedScreen;
import com.relapzz.momenton.ui.ExportFinishingScreen;
import com.relapzz.momenton.ui.ExportProgressOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.BlockBufferBuilderPool;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.util.Window;
import net.minecraft.entity.PositionInterpolator;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public final class ExportSession {
	private static final Identifier CURSOR = Momenton.id("textures/gui/cursor.png");
	private static final int CHUNK_WAIT_LIMIT = 600;

	private static ExportSession active;

	private final MinecraftClient client;
	private final ExportSettings settings;
	private final FfmpegProcess ffmpeg;
	private final Path video;
	private final LoopbackRenderer audio;
	private final FrameGrabber grabber;
	private final ExportProgressOverlay overlay;
	private final ExportProgress progress;
	private final ExportTimings timings;
	private final int guiScale;
	private final int frames;

	private boolean settling;
	private boolean draining;
	private int settleTick;
	private int settledTicks;
	private int waited;
	private boolean cancelled;
	private int frame;
	private int delivered;
	private int clip;
	private int clipStart;
	private int target;
	private int reported;
	private RenderTickCounter counter;
	private IOException failure;

	private ExportSession(MinecraftClient client, ExportSettings settings, ReplayMetadata metadata, int frames, FrameFormat format,
			FfmpegProcess ffmpeg, Path video, LoopbackRenderer audio) {
		this.client = client;
		this.settings = settings;
		this.ffmpeg = ffmpeg;
		this.video = video;
		this.audio = audio;
		this.timings = new ExportTimings(format.bytes(settings.width(), settings.height()));
		this.grabber = new FrameGrabber(settings.width(), settings.height(), format, timings);
		this.overlay = new ExportProgressOverlay(client, settings.width(), settings.height());
		this.progress = new ExportProgress(frames);
		this.guiScale = guiScale(metadata, settings);
		this.frames = frames;
		locate();
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

		int length = settings.clips().stream().mapToInt(Clip::length).sum();
		int frames = (int) (((long) length * settings.fps() + 19) / 20);

		Path output = settings.output();
		Path video = output.resolveSibling("momenton-video-" + output.getFileName());
		Path pcm = output.resolveSibling("momenton-audio-" + output.getFileName() + ".pcm");
		delete(video);
		delete(pcm);
		FrameFormat format = FrameGrabber.format(settings.ffmpegArgs(), settings.width(), settings.height());
		FfmpegProcess ffmpeg;
		try {
			ffmpeg = FfmpegProcess.start(settings.ffmpegCommand(), settings.ffmpegArgs(), format, settings.width(), settings.height(), settings.fps(), settings.bitrate(), video);
		} catch (IOException e) {
			Momenton.LOGGER.error("Could not start FFmpeg", e);
			ExportFailedScreen.show(client, settings, e);
			return;
		}

		LoopbackRenderer audio;
		try {
			audio = LoopbackRenderer.start(client, pcm, settings.fps());
		} catch (IOException e) {
			ffmpeg.discard(video);
			Momenton.LOGGER.error("Could not create the audio file", e);
			report(client, false, "Could not create the audio file: " + e.getMessage());
			return;
		}

		ReplaySession.open(client, settings.replay());
		if (!ReplaySession.isPlaying() || client.player == null) {
			audio.close();
			ffmpeg.discard(video, pcm);
			report(client, false, "Could not open the replay");
			return;
		}

		active = new ExportSession(client, settings, metadata, frames, format, ffmpeg, video, audio);
		active.settle();
		Window window = client.getWindow();
		window.setVsync(false);
		if (!VulkanMod.LOADED) {
			window.setFramebufferWidth(settings.width());
			window.setFramebufferHeight(settings.height());
		}
		client.onResolutionChanged();
		Momenton.LOGGER.info("Exporting {} frames of {} in {} clips at {}x{} {} fps as {} to {}", frames, settings.replay().getFileName(),
				settings.clips().size(), settings.width(), settings.height(), settings.fps(), format, settings.output());
	}

	public static void cancel() {
		if (active != null) {
			active.cancelled = true;
		}
	}

	public static void togglePreview() {
		if (active != null) {
			active.overlay.togglePreview();
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
			session.abort();
			report(session.client, false, "Export cancelled");
			return ticks;
		}
		if (session.settling) {
			session.settledTicks += ticks;
			return ticks;
		}
		return session.draining ? 0 : Math.max(0, session.target - ReplaySession.currentTick());
	}

	public static RenderTickCounter counter(RenderTickCounter counter) {
		ExportSession session = active;
		return session == null || session.settling ? counter : session.counter;
	}

	public static void beforeRender() {
		ExportSession session = active;
		if (session != null) {
			session.timings.renderStarted();
		}
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

	public static void report(MinecraftClient client, boolean saved, String message) {
		SystemToast.show(client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
				Text.literal(saved ? "Export finished" : "Export stopped"), Text.literal(message));
	}

	private static void delete(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException e) {
			Momenton.LOGGER.warn("Could not delete {}", path, e);
		}
	}

	private static int guiScale(ReplayMetadata metadata, ExportSettings settings) {
		if (metadata.guiScale() == null) {
			return 0;
		}
		double ratio = Math.min((double) settings.width() / metadata.framebufferWidth(),
				(double) settings.height() / metadata.framebufferHeight());
		return Math.max(1, (int) Math.round(metadata.guiScale() * ratio));
	}

	private boolean locate() {
		List<Clip> clips = settings.clips();
		long ticks = (long) frame * 20;
		int offset = (int) (ticks / settings.fps());
		boolean cut = false;
		while (clip + 1 < clips.size() && offset >= clipStart + clips.get(clip).length()) {
			cut |= clips.get(clip).endTick() != clips.get(clip + 1).startTick();
			clipStart += clips.get(clip).length();
			clip++;
		}
		target = clips.get(clip).startTick() + offset - clipStart;
		counter = new FrameCounter((float) (ticks % settings.fps()) / settings.fps(), 20.0f / settings.fps());
		return cut;
	}

	private void settle() {
		settling = true;
		settledTicks = 0;
		settleTick = Math.max(0, target - 1);
		if (!ReplaySession.isPaused()) {
			ReplaySession.togglePause();
		}
		ReplaySession.seek(settleTick);
	}

	private void rendered() {
		if (!ReplaySession.isPlaying()) {
			fail(new IOException("The replay closed before the export finished"));
			return;
		}
		if (failure != null) {
			fail(failure);
			return;
		}
		if (settling) {
			timings.pass(ExportTimings.Pass.SETTLE);
			if (settledTicks >= PositionInterpolator.DEFAULT_INTERPOLATION_DURATION
					&& !(client.currentScreen instanceof LevelLoadingScreen) && client.getOverlay() == null && built()) {
				ReplaySession.seek(settleTick);
				ReplaySession.togglePause();
				audio.stopOneShots();
				client.particleManager.clearParticles();
				settling = false;
			}
		} else if (draining) {
			timings.pass(ExportTimings.Pass.DRAIN);
			if (grabber.idle()) {
				handOff();
				return;
			}
		} else if (ReplaySession.currentTick() != target || client.getOverlay() != null || !built()) {
			timings.pass(ExportTimings.Pass.TERRAIN);
		} else if (!grabber.grab(client.getFramebuffer(), this::write)) {
			timings.pass(ExportTimings.Pass.STALL);
		} else {
			timings.pass(ExportTimings.Pass.GRAB);
			grabbed();
		}
		long start = System.nanoTime();
		overlay.draw(progress);
		timings.overlay(System.nanoTime() - start);
	}

	private void grabbed() {
		if (!VulkanMod.LOADED) {
			overlay.capture(client.getFramebuffer().getColorAttachment());
		}
		long start = System.nanoTime();
		try {
			audio.render();
		} catch (IOException e) {
			failure = e;
		}
		timings.audio(System.nanoTime() - start);
		frame++;
		progress.frameDone();

		int percent = frame * 100 / frames;
		if (percent >= reported + 5) {
			reported = percent - percent % 5;
			Momenton.LOGGER.info("Export {}% ({} of {} frames): {}", reported, frame, frames, timings.report(ffmpeg.encodedFrames()));
		}
		if (frame == frames) {
			draining = true;
		} else if (locate()) {
			settle();
		}
	}

	private boolean built() {
		if (terrainBuilt()) {
			waited = 0;
			return true;
		}
		if (++waited < CHUNK_WAIT_LIMIT) {
			return false;
		}
		Momenton.LOGGER.warn("Terrain still building after {} render passes at tick {}, grabbing anyway", waited, ReplaySession.currentTick());
		waited = 0;
		return true;
	}

	private boolean terrainBuilt() {
		if (VulkanMod.LOADED) {
			return ((ClientWorldAccessor) client.world).getChunkUpdaters().isEmpty() && VulkanMod.terrainBuilt();
		}
		WorldRenderer renderer = client.worldRenderer;
		ChunkRenderingDataPreparerAccessor graph = (ChunkRenderingDataPreparerAccessor) renderer.getChunkRenderingDataPreparer();
		Future<?> update = graph.getTerrainUpdateFuture();
		BlockBufferBuilderPool buffers = client.getBufferBuilders().getBlockBufferBuildersPool();
		return ((ClientWorldAccessor) client.world).getChunkUpdaters().isEmpty()
				&& renderer.isTerrainRenderComplete()
				&& buffers.getAvailableBuilderCount() == ((ChunkBufferPool) buffers).momenton$capacity()
				&& !graph.isTerrainUpdateScheduled()
				&& (update == null || update.isDone())
				&& !graph.getNeedsUpdate().get();
	}

	private CompletableFuture<?> write(ByteBuffer frame) {
		if (failure != null) {
			return CompletableFuture.completedFuture(null);
		}
		long start = System.nanoTime();
		long waitedBefore = ffmpeg.waitedNanos();
		try {
			CompletableFuture<Void> copied = ffmpeg.writeFrame(frame);
			delivered++;
			long waited = ffmpeg.waitedNanos() - waitedBefore;
			timings.submit(System.nanoTime() - start, waited);
			long copyStart = start + waited;
			return copied.whenComplete((ignored, error) -> timings.copied(System.nanoTime() - copyStart));
		} catch (IOException e) {
			failure = e;
			return CompletableFuture.completedFuture(null);
		}
	}

	private void handOff() {
		Momenton.LOGGER.info("Delivered {} of {} frames to ffmpeg in {} s", delivered, frames, progress.elapsedNanos() / 1_000_000_000.0);
		if (delivered != frames) {
			fail(new IOException("Only " + delivered + " of " + frames + " frames reached ffmpeg"));
			return;
		}
		ExportFinisher finisher = ExportFinisher.start(settings, ffmpeg, video, audio.path(), frames);
		restore("rendered, finishing in the background");
		client.setScreen(new ExportFinishingScreen(client.currentScreen, finisher));
	}

	private void abort() {
		restore("cancelled");
		ffmpeg.discard(video, audio.path());
	}

	private void fail(IOException e) {
		Momenton.LOGGER.error("Export failed", e);
		abort();
		ExportFailedScreen.show(client, settings, e);
	}

	private void restore(String outcome) {
		active = null;
		grabber.close();
		overlay.close();
		audio.close();

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
		Momenton.LOGGER.info("Export {} after {} of {} frames", outcome, frame, frames);
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
