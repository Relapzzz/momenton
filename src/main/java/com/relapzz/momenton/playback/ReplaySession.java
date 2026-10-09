package com.relapzz.momenton.playback;

import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.format.ActionCodec;
import com.relapzz.momenton.format.ReplayMetadata;
import com.relapzz.momenton.format.ReplayReader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.world.ClientWorld;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Plays one replay back: rebuild the world from a snapshot, then feed each tick's packets on the
 * matching client tick.
 *
 * <p>Seeking is the same operation as starting. Every chunk boundary has a snapshot holding a join
 * packet plus the whole world, so landing on any tick is "load the nearest snapshot at or before it,
 * then replay forward" - never a scan from tick zero. Replaying forward is the same code path as
 * normal playback, just run without waiting for client ticks.
 *
 * <p>Two files are read in step. {@code chunk_NNNN.bin} carries the packets, which go to the
 * connection, plus {@code ENTITY_MOVE} and {@code LOCAL_PLAYER}, which go to {@link LocalPlayerDriver}
 * - the local player's own movement and items, which the packet stream never carried. Alongside it
 * {@code ui_NNNN.bin} carries {@code UI_STATE} for {@link UiStateDriver}. Other entities still move on
 * packets alone, which is the last of the client-side tracks to go unapplied.
 */
public final class ReplaySession {
	private static ReplaySession active;
	private static Path pending;
	private static boolean disconnecting;

	private final MinecraftClient client;
	private final ReplayReader reader;
	private final ReplayMetadata metadata;
	private final Perspective viewerPerspective;

	private OfflineConnection connection;
	private ActionCodec.Reader actions;
	private ActionCodec.Action next;
	private ActionCodec.Reader uiActions;
	private ActionCodec.Action nextUi;
	private int uiIndex;
	private int chunkIndex;
	private int tick;
	private boolean paused;
	private boolean advancing;

	private ReplaySession(MinecraftClient client, ReplayReader reader) throws IOException {
		this.client = client;
		this.reader = reader;
		this.metadata = reader.metadata();
		this.viewerPerspective = client.options.getPerspective();
		load(0);
		PovDriver.play(reader.frames());
	}

	public static boolean isPlaying() {
		return active != null;
	}

	/** True while a replay is loaded but not advancing. */
	public static boolean isPaused() {
		ReplaySession session = active;
		return session != null && session.paused;
	}

	public static void togglePause() {
		ReplaySession session = active;
		if (session != null) {
			session.paused = !session.paused;
			PovDriver.setFrozen(session.paused);
		}
	}

	/** The tick now showing, and the recording's length, for a HUD or a scrubber. */
	public static int currentTick() {
		ReplaySession session = active;
		return session == null ? 0 : session.tick;
	}

	public static int durationTicks() {
		ReplaySession session = active;
		return session == null ? 0 : session.metadata.durationTicks();
	}

	/**
	 * Client thread. Opens {@code file}, replacing whatever world the client is in.
	 *
	 * <p>A live world has to go first, because playback builds its own and calls
	 * {@code MinecraftClient.joinWorld}. Use {@code disconnect(Text)} and nothing lower: it calls
	 * {@code world.disconnect} first, which closes the connection and is the only thing that asks an
	 * integrated server to stop. Going straight to {@code disconnect(Screen, boolean)} hangs the game
	 * forever on "Saving game" in {@code while (!integratedServer.isStopping())}.
	 *
	 * <p>That wait re-enters the render loop and so re-enters the tick, which is why the replay opens
	 * on a later tick and why {@code disconnecting} exists.
	 */
	public static void open(MinecraftClient client, Path file) {
		if (disconnecting || pending != null) {
			return;
		}
		if (active != null) {
			stop();
		}
		if (client.world != null) {
			pending = file;
			disconnecting = true;
			try {
				client.disconnect(ClientWorld.QUITTING_MULTIPLAYER_TEXT);
			} finally {
				disconnecting = false;
			}
			return;
		}
		start(client, file);
	}

	private static void start(MinecraftClient client, Path file) {
		ReplayReader reader;
		try {
			reader = new ReplayReader(file);
		} catch (IOException e) {
			Momenton.LOGGER.error("Could not open {}", file, e);
			return;
		}

		if (!reader.hasSetup() || !reader.hasSnapshot(0)) {
			Momenton.LOGGER.error("{} predates the setup and snapshot tracks and cannot be played", file);
			close(reader);
			return;
		}

		try {
			active = new ReplaySession(client, reader);
		} catch (IOException e) {
			Momenton.LOGGER.error("Could not start playback of {}", file, e);
			close(reader);
			return;
		}

		Momenton.LOGGER.info("Playing {} ({} ticks)", file.getFileName(), active.metadata.durationTicks());
	}

	/** Client thread, end of tick. */
	public static void onClientTick(MinecraftClient client) {
		// The wait for the integrated server to stop pumps the render loop, so this runs again from
		// inside the disconnect that started it. Nothing may act until the old session is fully gone.
		if (disconnecting) {
			return;
		}

		if (pending != null && client.world == null && client.getServer() == null) {
			Path file = pending;
			pending = null;
			start(client, file);
			return;
		}

		ReplaySession session = active;
		if (session == null) {
			return;
		}

		// The pause menu's Disconnect tears the session down without telling us, and it does it in two
		// steps: ClientWorld.disconnect closes the connection, and only then does MinecraftClient null
		// the world. In between it renders, and rendering ticks - so a tick lands here with a world
		// still present and a closed channel behind it, and applying a packet to that is a
		// ClosedChannelException on the render thread. Both halves have to be checked, and the
		// connection is the half that goes first.
		if (client.world == null || !session.connection.isOpen()) {
			stop();
			return;
		}
		session.tickPlayback();
	}

	/** Jumps to {@code target}, clamped to the recording. */
	public static void seek(int target) {
		ReplaySession session = active;
		if (session == null) {
			return;
		}
		try {
			session.jump(Math.clamp(target, 0, session.metadata.durationTicks() - 1));
		} catch (IOException e) {
			Momenton.LOGGER.error("Seek failed", e);
			stop();
		}
	}

	public static void stop() {
		ReplaySession session = active;
		if (session == null) {
			return;
		}

		active = null;
		PovDriver.stop();
		LocalPlayerDriver.reset();
		UiStateDriver.reset();
		session.client.options.setPerspective(session.viewerPerspective);
		// Safe from inside a tick, unlike the disconnect in open: a replay has no integrated server,
		// so this takes the branch that does not wait on one. Unload before closing the channel, and
		// only if there is still a world - quitting to title already did it. A closed connection means
		// the viewer disconnected and vanilla is already inside its own teardown; disconnecting again
		// from in there re-enters it.
		if (session.connection.isOpen() && session.client.world != null) {
			session.client.disconnect(new TitleScreen(), false);
		}
		session.connection.close();
		close(session.reader);
	}

	/**
	 * Rebuilds the world at the snapshot covering {@code target}, then replays forward to it without
	 * waiting for client ticks.
	 *
	 * <p>A short forward seek does not rebuild at all. Replaying forward is what the tick loop already
	 * does. Only a backward seek genuinely needs a snapshot - packets cannot be un-applied - and only a
	 * far-forward one is worth one, where replaying would cost more than rebuilding. One chunk's worth
	 * of ticks is that boundary, since that is the most a rebuild ever has to replay.
	 */
	private void jump(int target) throws IOException {
		if (target < tick || target - tick > metadata.chunkTicks()) {
			load(target / metadata.chunkTicks());
		}
		while (next != null && next.tick() <= target) {
			apply(next);
			next = read();
		}
		advanceUi(target);
		UiStateDriver.settle();
		tick = target;
		PovDriver.setTick(target);
		LocalPlayerDriver.snap(client.player);
	}

	/** Loads snapshot {@code index} and positions the action cursor just after it. */
	private void load(int index) throws IOException {
		LocalPlayerDriver.reset();
		UiStateDriver.reset();
		if (connection == null) {
			connection = new OfflineConnection(client);
			connection.applySetup(reader.setup());
		}
		connection.applySnapshot(reader.snapshot(index));

		chunkIndex = index;
		actions = reader.chunk(metadata.chunks().get(index));
		uiIndex = index;
		uiActions = reader.ui(metadata.chunks().get(index));
		nextUi = readUi();
		tick = metadata.chunks().get(index).startTick();

		// Unlike the packets, the UI actions of the snapshot's own tick are wanted: the snapshot does
		// not carry them, and the recorder rewrites the whole UI state at every chunk boundary so that
		// this one action is all a seek needs to know it.
		advanceUi(tick);
		UiStateDriver.settle();

		// A snapshot describes the world *after* its own tick, so the packets of that tick are already
		// in it and replaying them would apply the same tick twice. The client-side tracks still have
		// to be read: they are not in the snapshot, and the deltas after them are relative to them.
		next = read();
		while (next != null && next.tick() <= tick) {
			applyState(next);
			next = read();
		}
	}

	private void tickPlayback() {
		if (advancing || paused) {
			return;
		}

		advancing = true;
		try {
			connection.drainOutbound();

			while (next != null && next.tick() <= tick) {
				apply(next);
				next = read();
			}
			advanceUi(tick);

			if (next == null) {
				Momenton.LOGGER.info("Playback reached the end at tick {}", tick);
				stop();
				return;
			}

			applyPerspective();

			PovDriver.setTick(tick);
			tick++;
		} finally {
			advancing = false;
		}
	}

	/** The perspective is a recorded fact, not a viewer setting: the frame flags carry all three. */
	private void applyPerspective() {
		Perspective recorded = PovDriver.perspective();
		if (client.options.getPerspective() != recorded) {
			client.options.setPerspective(recorded);
		}
	}

	private void apply(ActionCodec.Action action) {
		if (action.type() == ActionCodec.PACKET) {
			connection.apply(action.payload());
		} else {
			applyState(action);
		}
	}

	/**
	 * The client-side tracks, which describe state rather than events and so are safe to apply twice.
	 * That is why {@link #load} feeds them the actions it skips: the pose the snapshot's own tick wrote
	 * is what the deltas after it are relative to.
	 */
	private void applyState(ActionCodec.Action action) {
		if (action.type() == ActionCodec.ENTITY_MOVE) {
			LocalPlayerDriver.acceptMove(action.payload(), client.player.getId());
		} else if (action.type() == ActionCodec.LOCAL_PLAYER) {
			LocalPlayerDriver.acceptState(action.payload(), client.getNetworkHandler().getRegistryManager());
		}
	}

	/**
	 * Drains the UI track up to {@code target}. It is a separate file from the chunk track and is read
	 * in step with it, so a replay tick applies both the packets and the client-side UI of that tick.
	 */
	private void advanceUi(int target) {
		while (nextUi != null && nextUi.tick() <= target) {
			UiStateDriver.accept(nextUi.payload());
			nextUi = readUi();
		}
	}

	/** The next UI action, rolling to the following chunk at the end of the current one. */
	@Nullable
	private ActionCodec.Action readUi() {
		try {
			while (true) {
				ActionCodec.Action action = uiActions.next();
				if (action != null) {
					return action;
				}
				if (uiIndex + 1 >= metadata.chunks().size()) {
					return null;
				}
				uiIndex++;
				uiActions = reader.ui(metadata.chunks().get(uiIndex));
			}
		} catch (IOException e) {
			Momenton.LOGGER.error("Playback failed reading the UI track", e);
			return null;
		}
	}

	/** The next action, rolling to the following chunk at the end of the current one. */
	@Nullable
	private ActionCodec.Action read() {
		try {
			while (true) {
				ActionCodec.Action action = actions.next();
				if (action != null) {
					return action;
				}
				if (chunkIndex + 1 >= metadata.chunks().size()) {
					return null;
				}
				chunkIndex++;
				actions = reader.chunk(metadata.chunks().get(chunkIndex));
			}
		} catch (IOException e) {
			Momenton.LOGGER.error("Playback failed reading the replay", e);
			return null;
		}
	}

	private static void close(ReplayReader reader) {
		try {
			reader.close();
		} catch (IOException e) {
			Momenton.LOGGER.warn("Could not close the replay", e);
		}
	}
}
