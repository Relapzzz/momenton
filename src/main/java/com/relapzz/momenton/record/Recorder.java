package com.relapzz.momenton.record;

import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.audio.MicRecorder;
import com.relapzz.momenton.audio.WavWriter;
import com.relapzz.momenton.format.ActionCodec;
import com.relapzz.momenton.format.FrameTrack;
import com.relapzz.momenton.format.ReplayDependencies;
import com.relapzz.momenton.format.ReplayMetadata;
import com.relapzz.momenton.format.ReplayWriter;
import com.relapzz.momenton.mixin.client.GameRendererInvoker;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.Window;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.BundlePacket;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.CommonPlayerSpawnInfo;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.network.state.PlayStateFactories;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turns a live session into a {@code .momenton} file.
 *
 * <p>Three threads feed it and none of them touch the disk. The netty thread filters and encodes
 * packets into a queue; the client thread drains that queue at the end of every tick, adds the
 * entity, player and UI tracks, and submits the batch to a single writer thread; the render thread
 * appends one record per frame to the frame track. Draining on the client thread rather than letting
 * netty submit directly is what keeps actions in tick order.
 */
public final class Recorder {
	private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private static volatile Recorder active;

	public enum State {
		IDLE, RECORDING, FINALISING
	}

	private final MinecraftClient client;
	private final PacketCodec<ByteBuf, Packet<? super ClientPlayPacketListener>> codec;
	private final ReplayWriter writer;
	private final ExecutorService io;
	private final ConcurrentLinkedQueue<Pending> pending = new ConcurrentLinkedQueue<>();
	private final FrameTrackWriter frames;
	private final EntityPositionTracker entities = new EntityPositionTracker();
	private final LocalPlayerWriter localPlayer;
	private final UiStateTracker ui = new UiStateTracker();

	/** Appended and read only on the writer thread. */
	private final List<ReplayMetadata.DimensionChange> dimensionChanges = new ArrayList<>();

	private final String worldName;
	private final String initialDimension;
	private final int framebufferWidth;
	private final int framebufferHeight;
	private final int guiScale;
	private final List<ReplayMetadata.ModEntry> mods = ReplayDependencies.loadedMods();
	private final List<String> resourcePacks;
	private final Path staging;
	private final Path output;
	private final long startedAtEpochMs = System.currentTimeMillis();

	private volatile State state = State.RECORDING;
	private volatile int tick;
	private volatile boolean failed;
	private boolean encodeErrorLogged;
	private int writtenTicks;
	private MicRecorder mic;

	/** The session join, replayed at the head of every snapshot so a seek rebuilds the world. */
	private GameJoinS2CPacket join;

	private Recorder(MinecraftClient client, ClientPlayNetworkHandler handler, Path staging, Path output) throws IOException {
		this.client = client;
		this.staging = staging;
		this.output = output;
		this.codec = PlayStateFactories.S2C.bind(RegistryByteBuf.makeFactory(handler.getRegistryManager())).codec();
		this.localPlayer = new LocalPlayerWriter(handler.getRegistryManager());
		this.worldName = worldName(client);
		this.initialDimension = client.world.getRegistryKey().getValue().toString();
		Window window = client.getWindow();
		this.framebufferWidth = window.getFramebufferWidth();
		this.framebufferHeight = window.getFramebufferHeight();
		this.guiScale = window.getScaleFactor();
		this.resourcePacks = ReplayDependencies.enabledResourcePacks(client);
		this.writer = new ReplayWriter(staging, 0);
		this.io = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "Momenton-Writer"));
		this.frames = new FrameTrackWriter(io, writer);
	}

	public static State state() {
		Recorder recorder = active;
		return recorder == null ? State.IDLE : recorder.state;
	}

	public static boolean isRecording() {
		return state() == State.RECORDING;
	}

	/** Ticks elapsed in the active recording, for the HUD indicator. */
	public static int elapsedTicks() {
		Recorder recorder = active;
		return recorder == null ? 0 : recorder.tick;
	}

	public static void toggle(MinecraftClient client) {
		Recorder recorder = active;
		if (recorder == null) {
			start(client);
		} else {
			recorder.finish();
		}
	}

	/** Netty thread. Called from {@code ClientConnection.channelRead0} before vanilla handles it. */
	public static void onPacket(Packet<?> packet) {
		// In singleplayer the integrated server owns a second ClientConnection on the same local
		// channel, so this hook also sees every serverbound packet. Only clientbound traffic is a
		// recording; without this check the whole C2S stream is encoded and thrown away each tick.
		if (packet.getPacketType().side() != NetworkSide.CLIENTBOUND) {
			return;
		}

		// Buffered whether or not anything is recording: F9 is retroactive, and the packets that set
		// the session up arrived long before it was pressed.
		SessionSetup.accept(packet);

		Recorder recorder = active;
		if (recorder != null) {
			recorder.capture(packet);
		}
	}

	public static void synthesise(Packet<?> packet) {
		Recorder recorder = active;
		if (recorder == null || recorder.state != State.RECORDING) {
			return;
		}
		byte[] encoded = recorder.encode(packet);
		if (encoded != null) {
			recorder.pending.add(new Pending(recorder.tick, ActionCodec.PACKET, encoded));
		}
	}

	/** Client thread, end of tick. */
	public static void onClientTick(MinecraftClient client) {
		Recorder recorder = active;
		if (recorder != null) {
			recorder.recordTick(client);
		}
	}

	/** Render thread, once the camera has been updated for this frame. */
	public static void onFrame(MinecraftClient client, RenderTickCounter counter) {
		Recorder recorder = active;
		if (recorder != null) {
			recorder.recordFrame(client, counter);
		}
	}

	private static void start(MinecraftClient client) {
		ClientPlayNetworkHandler handler = client.getNetworkHandler();
		if (handler == null || client.world == null || client.player == null) {
			notify(client, "Join a world before recording");
			return;
		}

		GameJoinS2CPacket join = SessionSetup.gameJoin();
		if (join == null) {
			notify(client, "This session's setup was not captured, rejoin the world before recording");
			return;
		}

		String name = NAME_FORMAT.format(LocalDateTime.now());
		Path root = FabricLoader.getInstance().getGameDir().resolve("momenton").resolve("recordings");
		Recorder recorder;
		try {
			recorder = new Recorder(client, handler, root.resolve("." + name + ".staging"), root.resolve(name + ".momenton"));
			recorder.begin(join);
			double nextFrameTick = 1 + client.getRenderTickCounter().getTickProgress(false);
			Path micFile = recorder.staging.resolve(MicRecorder.ENTRY);
			Files.createDirectories(micFile.getParent());
			recorder.mic = MicRecorder.start(micFile, nextFrameTick);
		} catch (IOException e) {
			Momenton.LOGGER.error("Could not start recording", e);
			notify(client, "Could not start recording, see the log");
			return;
		}

		active = recorder;
		Momenton.LOGGER.info("Recording to {}", recorder.output);
		notify(client, "Recording started");
	}

	/**
	 * Writes {@code setup.bin} and queues the join packet as the first action of tick 0. Runs before
	 * the recorder goes live, so nothing the netty thread captures can get ahead of the join.
	 */
	private void begin(GameJoinS2CPacket join) throws IOException {
		this.join = join;
		byte[] setup = SessionSetup.encode();
		io.execute(() -> {
			try {
				writer.writePart("setup.bin", setup);
			} catch (IOException e) {
				failed = true;
				Momenton.LOGGER.error("Recording failed writing the session setup", e);
			}
		});
	}

	/**
	 * Writes {@code snapshot_NNNN.bin}: the whole world as of the end of this tick, as a join packet
	 * followed by the packets that rebuild what the join's empty world was missing.
	 *
	 * <p>One per chunk, which is what makes seeking "load the nearest snapshot and replay forward"
	 * rather than "replay from tick zero". A snapshot describes the state *after* its own tick, so a
	 * seek replays the actions strictly after it.
	 *
	 * <p>Built on the client thread because it reads the world, encoded on the writer thread because
	 * that part needs no world at all - by then every packet holds its own copy of the data.
	 */
	private void snapshot(MinecraftClient client, int index) {
		long started = System.nanoTime();
		List<Packet<?>> packets = new ArrayList<>();
		packets.add(rejoin(client, join));
		packets.addAll(WorldSnapshot.capture(client));
		Momenton.LOGGER.info("Snapshot {} took {} ms on the client thread, {} packets",
				index, (System.nanoTime() - started) / 1_000_000, packets.size());

		io.execute(() -> {
			if (failed) {
				return;
			}
			try {
				writer.writePart(String.format("snapshot_%04d.bin", index), frame(packets));
			} catch (IOException e) {
				failed = true;
				Momenton.LOGGER.error("Recording failed writing a snapshot", e);
			}
		});
	}

	/** Length-prefixed packets, the same framing {@code setup.bin} uses. */
	private byte[] frame(List<Packet<?>> packets) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
		for (Packet<?> packet : packets) {
			byte[] encoded = encode(packet);
			if (encoded != null) {
				ActionCodec.writeVarInt(out, encoded.length);
				out.write(encoded);
			}
		}
		return out.toByteArray();
	}

	/**
	 * The join packet as it would have read had the session started now. Recording can begin in a
	 * dimension the player was not in when they joined, and the join packet is what tells playback
	 * which world to build - replaying the original one would build the wrong one.
	 *
	 * <p>{@code seed} and {@code isFlat} stay as they were: neither is readable from
	 * {@link ClientWorld} and both only affect biome blend and sky rendering.
	 */
	private static GameJoinS2CPacket rejoin(MinecraftClient client, GameJoinS2CPacket join) {
		ClientWorld world = client.world;
		CommonPlayerSpawnInfo spawn = join.commonPlayerSpawnInfo();
		return new GameJoinS2CPacket(
				client.player.getId(),
				join.hardcore(),
				join.dimensionIds(),
				join.maxPlayers(),
				join.viewDistance(),
				world.getSimulationDistance(),
				join.reducedDebugInfo(),
				join.showDeathScreen(),
				join.doLimitedCrafting(),
				new CommonPlayerSpawnInfo(
						world.getDimensionEntry(),
						world.getRegistryKey(),
						spawn.seed(),
						client.interactionManager.getCurrentGameMode(),
						spawn.lastGameMode(),
						world.isDebugWorld(),
						spawn.isFlat(),
						spawn.lastDeathLocation(),
						spawn.portalCooldown(),
						world.getSeaLevel()),
				join.enforcesSecureChat());
	}

	private void capture(Packet<?> packet) {
		if (state != State.RECORDING) {
			return;
		}

		// Bundles are expanded so each sub-packet keeps its own place in the tick stream.
		if (packet instanceof BundlePacket<?> bundle) {
			for (Packet<?> sub : bundle.getPackets()) {
				capture(sub);
			}
			return;
		}

		Packet<?> filtered = PacketFilter.filter(packet);
		if (filtered == null) {
			return;
		}

		String dimension = dimensionOf(filtered);
		byte[] encoded = encode(filtered);
		int at = tick;
		if (dimension != null) {
			pending.add(new Pending(at, ActionCodec.DIMENSION_CHANGE, dimension.getBytes(StandardCharsets.UTF_8)));
		}
		if (encoded != null) {
			pending.add(new Pending(at, ActionCodec.PACKET, encoded));
		}
	}

	@SuppressWarnings("unchecked")
	@Nullable
	private byte[] encode(Packet<?> packet) {
		ByteBuf buf = Unpooled.buffer(256);
		try {
			codec.encode(buf, (Packet<? super ClientPlayPacketListener>) packet);
		} catch (Exception e) {
			// A packet from outside the play phase, or one whose codec needs state we never captured.
			// Dropping it keeps the connection alive, which matters more than the packet does.
			if (!encodeErrorLogged) {
				encodeErrorLogged = true;
				Momenton.LOGGER.warn("Dropping packets that will not re-encode, first was {}", packet.getPacketType(), e);
			}
			return null;
		}

		byte[] out = new byte[buf.readableBytes()];
		buf.readBytes(out);
		return out;
	}

	@Nullable
	private static String dimensionOf(Packet<?> packet) {
		if (packet instanceof PlayerRespawnS2CPacket respawn) {
			return respawn.commonPlayerSpawnInfo().dimension().getValue().toString();
		}
		if (packet instanceof GameJoinS2CPacket join) {
			return join.commonPlayerSpawnInfo().dimension().getValue().toString();
		}
		return null;
	}

	private void recordTick(MinecraftClient client) {
		if (failed) {
			notify(client, "Recording stopped, writing failed");
			finish();
			return;
		}
		if (client.world == null || client.player == null) {
			finish();
			return;
		}

		int at = tick;
		if (at % ReplayWriter.CHUNK_TICKS == 0) {
			tick = at + 1;
			client.getPacketApplyBatcher().apply();
			if (client.world == null || client.player == null) {
				tick = at;
				finish();
				return;
			}
			snapshot(client, at / ReplayWriter.CHUNK_TICKS);

			// A snapshot makes the world independently rebuildable; these make the tracks that follow
			// it independently readable. Both are what "seek loads one chunk" rests on.
			entities.reset();
			localPlayer.reset();
			ui.reset();
		}

		try {
			byte[] moves = entities.capture(client.world);
			if (moves != null) {
				pending.add(new Pending(at, ActionCodec.ENTITY_MOVE, moves));
			}

			byte[] player = localPlayer.capture(client.player);
			if (player != null) {
				pending.add(new Pending(at, ActionCodec.LOCAL_PLAYER, player));
			}

			byte[] uiState = ui.capture(client, client.player);
			if (uiState != null) {
				pending.add(new Pending(at, ActionCodec.UI_STATE, uiState));
			}
		} catch (IOException e) {
			// The tick trackers write to in-memory buffers, so this cannot be a disk failure.
			throw new IllegalStateException("Failed to build a tick action", e);
		}

		submitBatch(at, at);
		tick = at + 1;
	}

	private void recordFrame(MinecraftClient client, RenderTickCounter counter) {
		if (state != State.RECORDING || client.world == null || client.player == null) {
			return;
		}

		Camera camera = client.gameRenderer.getCamera();
		if (!camera.isReady()) {
			return;
		}

		float tickProgress = counter.getTickProgress(false);
		Window window = client.getWindow();
		Vec3d pos = camera.getCameraPos();

		frames.append(tick, tickProgress,
				pos.x, pos.y, pos.z,
				camera.getYaw(), camera.getPitch(),
				((GameRendererInvoker) client.gameRenderer).callGetFov(camera, tickProgress, true),
				(float) (client.mouse.getX() / window.getWidth()),
				(float) (client.mouse.getY() / window.getHeight()),
				frameFlags(client, camera));
	}

	/**
	 * Sneaking is here rather than in the UI track because playback needs it during the tick, and
	 * because it is the same kind of thing as sprinting: a body state the frame track already carries.
	 * {@code ClientPlayerEntity.isSneaking()} reads straight off {@code player.input}, which a replay
	 * silences, so without recording it the replayed player can never crouch.
	 */
	private static int frameFlags(MinecraftClient client, Camera camera) {
		ClientPlayerEntity player = client.player;
		return (client.currentScreen != null ? FrameTrack.FLAG_SCREEN_OPEN : 0)
				| (client.inGameHud.getChatHud().isChatFocused() ? FrameTrack.FLAG_CHAT_OPEN : 0)
				| (player.preferredHand == Hand.OFF_HAND ? FrameTrack.FLAG_OFF_HAND : 0)
				| (player.isSprinting() ? FrameTrack.FLAG_SPRINTING : 0)
				| (camera.isThirdPerson() ? FrameTrack.FLAG_THIRD_PERSON : 0)
				| (player.isUsingItem() ? FrameTrack.FLAG_USING_ITEM : 0)
				| (player.isSneaking() ? FrameTrack.FLAG_SNEAKING : 0)
				| (client.options.getPerspective().isFrontView() ? FrameTrack.FLAG_FRONT_VIEW : 0);
	}

	/**
	 * Takes every queued action stamped up to {@code upTo} and hands it to the writer as one ordered
	 * batch for tick {@code at}; later ones stay queued for their own tick. No action in a batch may be
	 * stamped later than the batch itself, which is what keeps every action inside the
	 * {@code [startTick, endTick)} range its chunk advertises.
	 */
	private void submitBatch(int at, int upTo) {
		List<Pending> batch = new ArrayList<>();
		for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
			Pending action = it.next();
			if (action.tick() <= upTo) {
				it.remove();
				batch.add(action.tick() <= at ? action : new Pending(at, action.type(), action.payload()));
			}
		}
		io.execute(() -> writeBatch(at, batch));
	}

	private void writeBatch(int at, List<Pending> batch) {
		if (failed) {
			return;
		}
		try {
			writer.rollIfNeeded(at);
			for (Pending action : batch) {
				if (action.type() == ActionCodec.DIMENSION_CHANGE) {
					dimensionChanges.add(new ReplayMetadata.DimensionChange(action.tick(),
							new String(action.payload(), StandardCharsets.UTF_8)));
				}
				writer.write(action.tick(), action.type(), action.payload());
			}
			writtenTicks = at + 1;
		} catch (IOException e) {
			failed = true;
			Momenton.LOGGER.error("Recording failed while writing", e);
		}
	}

	private void finish() {
		state = State.FINALISING;
		active = null;

		frames.close();
		if (mic != null) {
			mic.stop();
		}

		// Packets keep arriving between the last recorded tick and the keypress. They belong to the
		// last tick, not to a tick that never happened - otherwise the final chunk holds an action
		// outside the [startTick, endTick) range its own index advertises.
		submitBatch(Math.max(0, tick - 1), Integer.MAX_VALUE);
		long framesRendered = frames.framesWritten();

		io.execute(() -> {
			int durationTicks = writtenTicks;
			Double micStartTick = mic == null ? null : mic.await();
			if (failed) {
				Momenton.LOGGER.warn("Finalising the recording from the {} ticks written before it failed", durationTicks);
			}
			try {
				if (micStartTick == null) {
					Files.deleteIfExists(staging.resolve(MicRecorder.ENTRY));
				}
				writer.finish(durationTicks, chunks -> metadata(durationTicks, framesRendered, chunks, micStartTick), output);
				Momenton.LOGGER.info("Recorded {} ticks and {} frames to {}", durationTicks, framesRendered, output);
				client.execute(() -> notify(client, "Recording saved: " + output.getFileName()));
			} catch (IOException e) {
				Momenton.LOGGER.error("Could not finalise the recording, its parts are left in {}", staging, e);
			}
		});
		io.shutdown();
	}

	private ReplayMetadata metadata(int durationTicks, long framesRendered, List<ReplayMetadata.ChunkEntry> chunks, Double micStartTick) {
		return new ReplayMetadata(
				ReplayMetadata.FORMAT_VERSION,
				version("minecraft"),
				version(Momenton.MOD_ID),
				startedAtEpochMs,
				durationTicks,
				framesRendered,
				ReplayWriter.CHUNK_TICKS,
				worldName,
				initialDimension,
				chunks,
				List.copyOf(dimensionChanges),
				framebufferWidth,
				framebufferHeight,
				guiScale,
				micStartTick,
				micStartTick == null ? null : WavWriter.SAMPLE_RATE,
				mods,
				resourcePacks,
				null);
	}

	/** The save name in singleplayer, the server entry's name otherwise. Vanilla resolves it this way. */
	private static String worldName(MinecraftClient client) {
		if (client.isInSingleplayer()) {
			return client.getServer().getSaveProperties().getLevelName();
		}
		ServerInfo server = client.getCurrentServerEntry();
		return server != null ? server.name : "Unknown world";
	}

	private static String version(String modId) {
		return FabricLoader.getInstance().getModContainer(modId)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}

	private static void notify(MinecraftClient client, String message) {
		if (client.player != null) {
			client.player.sendMessage(Text.literal(message), true);
		}
	}

	private record Pending(int tick, int type, byte[] payload) {
	}
}
