# Momenton - Implementation Plan

**This is the durable design document.** It explains what Momenton is, why it is built this way, and
what each phase must deliver. It changes only when a design decision changes.

For what is done and what to do next, see `TASKS.md`. Read that first if you are picking this up
mid-build; come back here for the reasoning behind whatever you are about to write. Lessons learned
while building are at the end of this file.

---

## Context

Momenton is a private Fabric mod for Minecraft 1.21.11 that records and edits gameplay inside the
client, so footage never has to leave Minecraft to become a finished video.

**The product goal, stated precisely:** an exported Momenton video should be indistinguishable from an
OBS screen recording of the same session - same first-person view, same smooth motion, same HUD, same
inventory screens - except that nothing was ever captured as pixels. Recording stores *state*
(Flashback's approach); export re-renders that state into frames.

This is the tiebreaker for design arguments. If a choice makes the export less like an OBS capture of
the session, it is the wrong choice.

### Decisions

| Question | Decision |
|---|---|
| Capture model | Packet/state recording, Flashback style. No pixel capture at record time. |
| Camera | **Exact recorded POV only.** No free camera, no keyframes, no third person beyond what was recorded. |
| GUI fidelity | HUD **and** container screens (inventory, chests, crafting), re-rendered at export. |
| Overlays | **None in v1.** No text, no images, no PiP. |
| Editing | Trim, split, reorder, undo/redo. Nothing else. |
| Preview | The live playback view itself. The editor is an overlay on it, hidden at export. |
| Editor UI | Vanilla `Screen`s and `DrawContext`. No ImGui. |
| Audio | Mic recorded live to WAV; game audio rendered at export via OpenAL loopback. |
| Encoder | FFmpeg from PATH or a configured path, raw frames piped to stdin, `libx264` first. Auto-download and hardware encoders come in hardening. |
| Order | Export comes before the editor: the product is an MP4, so it is built first. |
| Trigger | Manual start/stop only. No always-on buffer, no auto-clipping. |

Scope was deliberately cut twice during planning. Propose less rather than more here.

---

## The three things that make this work

Everything else is ordinary engineering. These three ideas are what make a state recorder produce
OBS-grade output, and getting them wrong is the main way this project fails.

### 1. Smoothness: it is not a slideshow, and the mechanism is vanilla

Live Minecraft is *already* 20 TPS plus interpolation - at 200 fps, the overwhelming majority of
frames are interpolated between two ticks. `RenderTickCounter` drives that, and 1.21.11 exposes
`getTickProgress(boolean)`, `getDynamicDeltaTicks()`, `getFixedDeltaTicks()`, plus the
`RenderTickCounter.Constant` and `.Dynamic` implementations.

During export each output frame gets its own `RenderTickCounter` carrying an exact fractional tick
progress and a delta of `20 / fps` ticks - a small record in `ExportSession`, because
`RenderTickCounter.Constant` returns one value from all three getters and so cannot carry both.
Entity motion, item swing, view bobbing and hurt tilt then interpolate exactly as they do live.
Rendering 60 fps out of 20 TPS data is precisely what the game does every frame anyway.

### 2. Fidelity: the local camera must be recorded at frame rate

This is the one genuine gap in a pure packet recorder. Mouse-look happens at frame rate, but the
network only carries player rotation at 20 Hz. Interpolating from packets produces motion that is
smooth but *not yours* - subtly floaty, and instantly recognisable as wrong.

So Momenton records the local camera pose **every rendered frame** into a fixed-stride binary track.
At 56 bytes per frame, 200 fps for ten minutes is under 7 MB before compression - negligible beside
the packet stream. Fixed-stride records make sampling at any output time a binary search over the
array (`FrameTrack`) rather than a scan or a decode.

The same track carries the mouse cursor position, for the same reason: cursor movement inside an
inventory screen is a frame-rate signal.

### 3. One render path: vanilla's own

Playback already runs real vanilla rendering over a real replayed world, player, HUD and screens, so
there is nothing to compose by hand. The single render path is `GameRenderer.render` over that
replayed state. Preview and export differ only in two things:

- **The clock.** Preview runs on the live clock. Export runs on a fixed clock: it ticks the replay to
  each output frame's tick and renders with a fixed tick progress.
- **The readback.** Export reads the framebuffer back and pipes it to FFmpeg. Preview does not.

There is no `FrameRenderer`, `HudReproducer` or `ScreenReproducer`; they would reimplement what
vanilla already does. Because preview and export draw through the same vanilla call, there is no
class of bug where the preview looked fine but the export drew something else. If something looks
wrong only in the export, the bug is in the clock, the resize or the readback.

---

## Architecture

Packages under `com.relapzz.momenton`. Dependencies run strictly downward - `format` knows nothing
about `ui`, `record` knows nothing about `export`.

```
record/    packet capture + frame track + UI state track   (live gameplay only)
format/    ReplayFile container: chunks, snapshots, tracks, metadata
playback/  ReplayFile -> re-simulated ClientWorld          (drives preview and export)
edit/      Project / Timeline / Clip / UndoStack           (pure data, no rendering)
audio/     mic capture (live) + OpenAL loopback render (export)
export/    fixed-clock loop -> frame readback -> FFmpeg subprocess
ui/        vanilla Screens: replay browser, export options, editor overlay
config/    settings + keybinds
```

`Momenton.MOD_ID` and `Momenton.id(path)` are the intended `Identifier` helpers - use them rather than
`Identifier.of("momenton", ...)` literals.

### Replay file format

A zip, so each part stays independently seekable and inspectable:

```
recording.momenton (zip)
  metadata.json      version, MC/mod version, duration, world name, chunk index, dimension changes,
                     framebufferWidth/Height and guiScale at record start, micStartTick and
                     micSampleRate (all null in older files or without a mic), mods (id and
                     version of each top-level mod, minus builtins, Fabric API modules, Fabric
                     Loader and Momenton) and resourcePacks (enabled ids bottom to top, server
                     packs left out) at record start (null in older files, which never warn),
                     and player (UUID and name the server assigned the recorder; playback
                     builds the replayed player from it, the viewer's own profile when null);
                     the replay browser compares them with the running game
                     (format/ReplayDependencies) and shows a warning line with a details tooltip,
                     never blocking Play
  setup.bin          the configuration-phase packets and the game join, replayed before tick 0
  chunk_0000.bin     length-prefixed actions: [varint type][varint tickDelta][payload]
  snapshot_0000.bin  full world state at that chunk boundary
  ...
  frames.bin         FIXED-STRIDE 56-byte records, one per rendered frame
  ui_0000.bin        tick-rate UI state actions
  audio/mic.wav      48 kHz mono s16 WAV, STORED, added after the zip is finished
  thumb.png          preview still for the replay browser
  resourcepacks/<uuid>.zip  each server or world pack active at F9, STORED; metadata serverPacks lists
                     id and sha256; playback extracts to momenton/cache/packs/<sha256>.zip and
                     loads them with ServerResourcePackLoader.initWorldPack + addResourcePack (no
                     prompt, no download); leaving the replay clears them like leaving a server.
                     Resource pack push/pop packets are dropped from the stream.
```

**`frames.bin` record layout** (fixed stride, little-endian):

```
int    tick            4
float  tickProgress    4
double x, y, z        24
float  yaw, pitch      8
float  fov             4
float  mouseX, mouseY  8   (fraction 0-1 of the window; older formats hold scaled GUI pixels)
int    flags           4   (screen open, chat open, hand, sprinting, sneaking, front view, ...)
                    = 56 bytes
```

The mouse is stored as a fraction of the window so an export at another resolution or GUI scale puts
the cursor in the same place. That change bumps `FORMAT_VERSION`.

Chunk action types: `PACKET`, `ENTITY_MOVE` (delta-compressed), `LOCAL_PLAYER` (health, hunger,
hotbar, offhand, held item, fire ticks), `DIMENSION_CHANGE`, plus `UI_STATE` in the `ui_NNNN.bin`
entries - one framing serves both tracks. Compression comes from the zip entries themselves; do not
hand-roll it. A reader ignores action types it does not know, which keeps old recordings playable.

**`ENTITY_MOVE` records only the local player and its root vehicle.** A server tells a client about
every entity except that client's own, so the packet stream carries everyone else's movement and none
of the player's. Other entities need no track: the live client's view of them *was* those packets plus
vanilla interpolation, and replaying the packets reproduces it exactly. The vehicle the player rides is
the exception, because the client is authoritative for it. See `format/EntityMoveCodec` for the layout.

**Tick deltas are relative to the chunk's first tick, not to tick zero.** That is what makes a chunk
independently readable, and it means a reader has to be seeded with `startTick` from the chunk index.

**A chunk boundary resets every tracker**, for the same reason. Delta compression and change gating
are both relative to the writer's running state, which spans the whole recording, while a seek loads
exactly one chunk: without the reset, a seek into the middle can resolve no delta and never learns a
value last written in an earlier chunk. So the first action of a chunk is always absolute and always
complete. Change-gated records are *partial* (flags say which fields are present), so playback merges
them field by field into an accumulated state; replacing the pending state loses fields during a
forward seek, which applies many ticks with no player tick in between.

**Recording stages loose files, then zips at stop.** `ZipOutputStream` holds one entry open at a time
while the chunks, the UI tracks and `frames.bin` all grow together, so `ReplayWriter` streams loose
files into a `.<name>.staging/` directory and zips them when recording ends. A crash leaves every
completed part on disk rather than a truncated archive. A write failure must finalise what was
recorded, not delete it.

Snapshots exist so seeking is "load nearest snapshot, replay forward" rather than "replay from tick
zero". One chunk per ~500 ticks (25 s), and one snapshot per chunk - including chunk zero, because
recording begins mid-session and without it there is no initial world to replay forward from.

**A snapshot is a list of packets, not a bespoke format:** a `GameJoinS2CPacket` rebuilt from the
live world, then `INITIAL_CHUNKS_COMING`, the world time, the player's position, the chunk-map
recentre, every loaded chunk nearest-first, the whole tab list (`PlayerListS2CPacket`, every entry, before any
spawn), the server state a join sends (`record/ServerStateSnapshot`: teams, objectives, display slots
and scores; boss bars; tab header and footer; world border; rain state and both gradients; abilities;
item cooldowns), every entity as vanilla's `EntityTrackerEntry.sendPackets` pairs it (spawn, tracked
data, attributes, equipment), the recorded player's own tracked data, attributes and status effects,
then passengers and leashes once every entity exists, and the player's inventory
(`InventoryS2CPacket` on sync id 0). The rule: a snapshot is what a server sends a fresh client at
join plus pairing; anything less shows up as missing state until the server happens to resend it.
A seek clears that server state first (`OfflineConnection.clearServerState`): an objective ADD on an
existing name throws. Framing is `[varint length][packet]`, the same as
`setup.bin`. Leading with the join is what makes a snapshot a complete rebuild rather than a patch,
so seeking never has to undo anything. Only the first load applies the join; a later seek keeps the
connection and the `ClientWorld` and clears the world in place instead (see Phase 2).

The playback side needed no code for this at all - same codec, same vanilla handlers. Two orderings
inside it are load-bearing and invisible in the file:

- **The chunk-map recentre must precede the chunks.** `ClientChunkManager` keeps a fixed-size map
  centred wherever `ChunkRenderDistanceCenterS2CPacket` last put it, and silently discards anything
  outside it. At join that centre is 0,0.
- **`INITIAL_CHUNKS_COMING` must be present at all.** It is the only thing that moves
  `ClientChunkLoadProgress` out of `Start`, a state with no timeout, so without it the loading screen
  never lifts however much world data arrives.

**A snapshot's radius is the client's chunk map radius, not its render distance.** A server sends out
to `viewDistance + 1` and `ClientChunkManager.getChunkMapRadius` keeps `max(2, viewDistance) + 3`, so
the snapshot scans that. Unloaded chunks come back null, so the wider scan only captures what is there.

A snapshot describes the world *after* its own tick, so replaying forward from it starts at the next
tick.

**Snapshot threading.** Chunk packets, light data included, are built in a parallel stream on the
common `ForkJoinPool`, nearest first. That is safe because the client thread blocks while it runs: the
world cannot change while the thread that owns it waits, so parallel readers need no synchronisation
and the snapshot stays consistent. The cost was a hitch on the client thread at every boundary (8-29
ms measured in the user's log), so built chunk packets are cached per chunk and reused (Wave 9): a
chunk is rebuilt only if it is new, a different `WorldChunk` instance, or was marked within 5 s
before its cached packet was built. Marks: `WorldChunk.setBlockState` on a client world, chunk load
and unload (chunk and its 8 neighbours, since light crosses one chunk), light update packets, biome
data and block entity updates. Light is computed after the change (vanilla `chunkUpdaters` drains
over many frames), so the 5 s settle window rebuilds a chunk again at the next snapshot when its
light may not have landed. This is engine-independent on purpose: ScalableLux (Starlight) overwrites
the vanilla light provider, so hooks on vanilla `LightStorage` never fire there.

**The snapshot-boundary race.** `MinecraftClient.render` calls `PacketApplyBatcher.apply()` before
`tick()`. Netty stamps a packet with `Recorder.tick`, which only moves to `at+1` at the end of tick
`at`, so a packet arriving during tick `at` is stamped `at` but applied in the next frame - after the
snapshot taken at the end of tick `at`. Playback skips every action with tick <= `at` after loading
that snapshot, so the packet is in neither; a chunk or spawn becomes a permanent hole after a seek.
The fix, at a boundary in `Recorder.recordTick`: move the stamp to `at+1` first, drain
`client.getPacketApplyBatcher().apply()`, then take the snapshot. `submitBatch(at)` leaves entries
stamped `at+1` queued for the next batch instead of capping them. A `tools` check asserts that chunk
and spawn packets stamped exactly at a boundary appear in that snapshot.

**`setup.bin`, and why recording mid-session needs it.** F9 starts recording long after the session
was set up, so a recording made this way is missing two things that arrived before it began, and
neither can be reconstructed at playback:

1. **The registry manager.** Recorded packets are encoded with
   `PlayStateFactories.S2C.bind(RegistryByteBuf.makeFactory(registryManager))`, so without the same
   registries they cannot even be *decoded*. The client receives them as
   `DynamicRegistriesS2CPacket` / `SynchronizeTagsS2CPacket` during configuration and keeps only the
   built result, so there is nothing to serialise afterwards. On a modded server they are not
   guessable at all.
2. **`GameJoinS2CPacket`.** `ClientPlayNetworkHandler.onGameJoin` is what constructs the
   `ClientWorld` and the local player. No join packet means no world for the rest of the stream to
   act on.

So the recorder **buffers the session-setup packets on every connection, not only while recording**:
the configuration-phase clientbound packets (`SelectKnownPacksS2CPacket`, `DynamicRegistriesS2CPacket`,
`SynchronizeTagsS2CPacket`, `FeaturesS2CPacket`, `ReadyS2CPacket`) and the play-phase
`GameJoinS2CPacket`. F9 writes them to `setup.bin`; playback feeds them through a real
`ClientConfigurationNetworkHandler` on the offline channel, which transitions itself into play
exactly as vanilla does, and only then does tick 0 begin.

This is the ReplayMod approach - it records from connect for the same reason - and it is the only one
of the three options that is correct on a modded server. The alternatives were re-deriving the
registry sync from the client's built registry manager (the client holds the result, not the input,
so it does not round-trip) and rebuilding vanilla registries locally at playback (wrong for anything
but vanilla singleplayer). The cost is a few MB of packets retained per connection for the life of
the session. That is the price of F9 being retroactive at all.

`setup.bin` is a header, not a track: a plain length-prefixed packet list with no ticks, since none of
it belongs to a tick. It does **not** replace `snapshot_0000.bin`. Setup builds an *empty* world of
the right shape; the snapshot fills it with the chunks and entities that were already loaded when F9
was pressed and are therefore never re-sent.

### Packet capture: decode, filter, re-encode, synthesise

Capture the already-decoded `Packet<?>` in
`ClientConnection.channelRead0(ChannelHandlerContext, Packet<?>)` rather than the raw `ByteBuf`
earlier in the netty pipeline, because we need to *inspect* traffic to filter it. In singleplayer the
integrated server's connection fires the same hook, so filter on `packet.getPacketType().side()`.
Re-encoding needs a `RegistryByteBuf` carrying the right `DynamicRegistryManager`, captured from the
live `ClientPlayNetworkHandler` when recording starts. Filter rules, learned from Flashback:

- Expand `BundleS2CPacket` into sub-packets so tick boundaries stay meaningful.
- Rewrite signed player-chat as system chat - chat signatures are session-bound and will not validate
  on replay.
- Drop custom-payload packets for mods that are not present (`UnknownCustomPayload` keeps only the id),
  so a replay does not hard-fail.

Player position packets are recorded. A rule that skipped them was wrong and was removed: a dimension
change is a teleport, and the terrain-loading screen waits on the player's position.

**Synthesise the packet the server sent to everyone else for anything the local client predicted.** A
packet recorder replays what the server said, and the server deliberately withholds from a player what
that player's client already did locally. Where the missing thing has a packet shape, the recorder
writes that packet as an ordinary `PACKET` action from the client thread (`Recorder.synthesise`), so
it needs no action type, no codec and no playback code - `OfflineConnection.apply` handles it like any
other packet. The cases:

- **Block break:** `ServerWorld.syncWorldEvent` ends in `PlayerManager.sendToAround`, which skips the
  breaker, who runs the event locally. Synthesise `WorldEventS2CPacket(2001, pos, rawStateId, false)`.
- **Crack overlay:** `setBlockBreakingInfo` for the local player's own id is local. Synthesise
  `BlockDestructionS2CPacket`.
- **Local sounds** (place, mining hits, eating and other self-sounds): synthesise
  `PlaySoundS2CPacket` (or `PlaySoundFromEntityS2CPacket`), captured from `ClientWorld.playSound` and
  `playSoundFromEntity` where the source is the local player. Eating and footsteps go through the
  `ClientPlayerEntity.playSound(SoundEvent, float, float)` override instead, and the mining hit sound
  is a `PositionedSoundInstance` handed straight to `SoundManager.play` in
  `ClientPlayerInteractionManager.updateBlockBreakingProgress` (captured at its construction), so
  each has its own hook. Anything run while a packet is being applied is excluded, or every real sound
  would be recorded twice: a counter wraps `PacketApplyBatcher$Entry.apply` for client play
  listeners, because any handler can reach these paths, not only the three obvious ones.
- **Block changes:** a `ClientWorld.setBlockState` (4-argument override) that changes a block outside
  packet application is synthesised as `BlockUpdateS2CPacket`, so placing and breaking appear on the
  tick they did live rather than a ping later; `handleBlockUpdate` calls `World.setBlockState`
  directly and rollbacks run inside packet application, so neither is captured. Also covers client-only
  blocks such as herosanchoroptimizer's fake anchor.
- **Hidden entities:** once per recorded tick, an entity in the unfiltered entity lookup but missing
  from `ClientWorld.getEntities()` (marlows-crystal-optimizer hides a hit crystal that way) is
  synthesised as destroyed; if it reappears, as a spawn plus tracked data.
- **Screen contents:** `ClickSlotC2SPacket` carries the client's predicted result and the server only
  sends a correction when it disagrees, so item moves in a chest, crafting table or the player's
  inventory never appear clientbound. At the end of each tick, if `player.currentScreenHandler`'s
  stacks or cursor changed, write `InventoryS2CPacket(syncId, revision, stacks, cursor)`.

`record/LocalPrediction` holds the logic; the mixins (`ClientWorldMixin`, `ClientPlayerSoundMixin`,
`ClientPlayerInteractionManagerMixin`, `PacketApplyBatcherEntryMixin`) are one static call each.
Screen contents are `record/ScreenTracker`, run from the client tick.

### HUD and GUI: what is free, and what must be recorded

Playback renders the real vanilla HUD and screens over the replayed player, so most HUD state
reconstructs for free from the replayed player entity and clientbound packets:

**Free** - health, hunger, armour, air, XP, status effects, chat history (`GameMessageS2CPacket`),
scoreboard, boss bars, titles and action bar, vignette, and container screens opened by the server
(`OpenScreenS2CPacket` builds the screen and its `ScreenHandler` exactly as it did live).

**Not free, despite appearances: container contents after the player's own clicks.** Slot-update
packets only fill a container when the server sends them, and it does not send them for the player's
own clicks (see synthesised screen contents above). The pre-F9 inventory comes from the snapshot.

**Must be recorded** - client-side, never crosses the wire, so it needs a `UI_STATE` (or
`LOCAL_PLAYER`) track:

- selected hotbar slot (`UpdateSelectedSlotC2SPacket` is client to server)
- the screen kind: NONE, OWN_INVENTORY, CHAT, CONTAINER or OTHER. Pressing E produces no packet, and
  closing any screen is client-initiated (only a C2S packet), so playback applies the kind: NONE
  closes the screen, OWN_INVENTORY opens `InventoryScreen`, CHAT opens `ChatScreen`, CONTAINER leaves
  the server-opened screen alone, OTHER shows nothing (out of scope)
- the chat draft text, so typing replays
- hand swing starts and attack-cooldown resets, which drive the arm and the crosshair indicator
- chat scroll offset
- portal warp (`nauseaIntensity`, computed from `portalManager.isInPortal()`, never true for a driven
  player) and the local player's fire ticks (set locally, a tick ahead of `SET_ENTITY_DATA`)
- mouse cursor position - carried in `frames.bin`, normalised 0-1, because it is a frame-rate signal

**A replayed screen must ignore the viewer.** Mouse clicks reach `screen.mouseClicked` directly, not
through `handleInputEvents`, so without a block the viewer can move items in a replayed chest and Esc
closes it. Keybinds also do not fire while a screen has focus, so `P`, `[` and `]` would stop working.
Playback blocks mouse and key input to screens through Fabric `ScreenMouseEvents` /
`ScreenKeyboardEvents` (no mixin) and handles `P`, `[`, `]` and Esc there.

**The cursor.** The recorded cursor is fed to the screen's render call through a mixin on the mouse
arguments of `GameRenderer.render`. Minecraft never draws the cursor itself (the OS does), so export
draws a cursor sprite while a screen is open, behind a toggle.

Because this is re-rendered rather than baked, the editor gets HUD element toggles essentially for
free, through an `InGameHudMixin` gating the per-element render methods. That is the main creative
advantage over a real screen recorder and is worth exposing.

*Scope boundary:* pause menu, settings screens, advancements, death screen and the F3 debug overlay
are **not** reproduced.

*Known limitation:* other mods' custom HUD elements render only if those mods are installed at export
time and their state is reconstructible from packets. Client-only HUD mods holding private state will
not reproduce. Lunar Client Rewind has the same limitation.

### Export

`export/ExportSession` does not own a loop. **Each vanilla `MinecraftClient.render` pass is one
output frame**, and export changes only what that pass is fed, through `ExportClientMixin`. The
contract is `ExportSettings(replay, clips, width, height, fps, output, ffmpegCommand, ffmpegArgs,
bitrate, drawCursor)`, built by `ui/ExportScreen`.

**The clip list.** `clips` is an ordered `List<Clip>` of `[startTick, endTick)` source ranges and
the video is their concatenation: `frames = ceil(sum(length) * fps / 20)`. From the browser it is one
clip from the range fields; from the editor it is `TimelineResolver.exportRanges()` and the range
fields are hidden.

**What a tick number means.** `ReplaySession.currentTick()` is the tick on screen everywhere: every
action stamped up to it has been applied and `PovDriver` draws it. A seek to `T` leaves `T`, each
playback tick adds exactly one, and playback ends at `durationTicks - 1`. The editor, the export
clock and the mic sync all read it the same way.

1. **Start.** Read the metadata, start ffmpeg and the audio loopback, open the replay, turn vsync
   off, set the export resolution, then settle on frame 0's target.
2. **Clock.** The `beginRenderTick` result in `render` is replaced, so ticks come from the replay
   instead of the wall clock. Frame `n` sits at timeline offset `floor(n * 20 / fps)`, which
   `locate()` maps through the clip list to a source tick, `target`. Each pass asks for
   `target - currentTick` ticks, and a frame is grabbed only when `currentTick == target`.
   Driving the real client tick also drives `GameRenderer.tick`, which eases the camera's eye height;
   a loop that skipped it would freeze `cameraY` and export every crouch at the wrong height.
   **Settle.** At the start and at every cut where the next clip does not begin where the last one
   ended, the session pauses the replay and seeks to `target - 1`, then lets the client run with its
   own clock until at least `PositionInterpolator.DEFAULT_INTERPOLATION_DURATION` (3) client ticks
   have passed, no `LevelLoadingScreen` is up and the terrain is built. Then it seeks to `target - 1`
   again, unpauses, stops one-shot sounds and clears particles, and the next pass asks for exactly
   one real tick to reach `target`. That one tick is what gives entities and the camera a live
   `last` position to interpolate from, so nothing slides across a cut and nothing from before it
   is heard or seen.
3. **Render** with a fixed tick progress `(n * 20 mod fps) / fps` and delta `20 / fps`, through a
   custom `RenderTickCounter` record passed to `GameRenderer.render` and returned by
   `getRenderTickCounter`. No access widener: `RenderTickCounter.Constant` cannot be used (see the
   three things, 1).
4. **Grab only when the world is fully built**, after `GameRenderer.render`. A pass that is not ready
   is simply rendered again with the same tick and counter. Ready means all of:
   `ClientWorld.chunkUpdaters` empty (it drains at most 10% per frame, so right after a seek or cut
   some snapshot chunks are not even scheduled yet; read through `ClientWorldAccessor`),
   `WorldRenderer.isTerrainRenderComplete()`, every `BlockBufferBuilderPool` builder free (the pool's
   capacity is captured by `BlockBufferBuilderPoolMixin`), and the occlusion graph settled
   (`ChunkRenderingDataPreparer`: no terrain update scheduled or running, `needsUpdate` clear). The
   wait is capped at 600 render passes per frame; after that the frame is grabbed anyway with a
   warning, so a chunk that never finishes cannot stall an export. The idea is ReplayMod's
   `Mixin_BlockOnChunkRebuilds`; Momenton waits by re-rendering instead of blocking.
5. **Resolution.** `Window.setFramebufferWidth/Height` then `MinecraftClient.onResolutionChanged`,
   the way vanilla `takePanorama` does it, so world and GUI both render at the export size into the
   main target. `ExportWindowMixin` cancels `onFramebufferSizeChanged` during export so a window
   resize cannot undo it. At the end the real GLFW framebuffer size and vsync are restored.
6. **GUI scale** = `round(recordedGuiScale * min(width / recordedWidth, height / recordedHeight))`,
   at least 1, injected into `onResolutionChanged`'s `calculateScaleFactor` call. Recordings from
   before Wave 3 have null `framebufferWidth/Height` and `guiScale`, and keep the viewer's scale.
7. **Readback** (`FrameGrabber`), never blocking the render thread on a fence.
   **NV12 on the GPU.** Before the copy, a Blaze3D pass converts the colour attachment to NV12, so
   the readback is 1.5 bytes per pixel (3.1 MB at 1080p) instead of 8.3 MB; the copy out of uncached
   readback memory was the bottleneck (42 ms per frame at about 470 MB/s on the Iris Xe). The pipeline
   `momenton:pipeline/nv12` follows vanilla `RenderPipelines.TRACY_BLIT` and
   `TracyFrameCapturer.capture`: vertex shader `minecraft:core/screenquad` (fullscreen triangle from
   `gl_VertexID`, `VertexFormats.EMPTY`, `TRIANGLES`, `draw(0, 3)`), fragment shader
   `assets/momenton/shaders/core/nv12.fsh` (found by `ShaderLoader`, which loads `shaders/` from every
   namespace), sampler `InSampler` bound to the colour attachment view with the cached NEAREST sampler,
   no depth, drawn with `createRenderPass` into an RGBA8 target of `width / 4` by `height * 3 / 2`
   (`USAGE_RENDER_ATTACHMENT | USAGE_COPY_SRC`), then `copyTextureToBuffer` from that target. Each
   fragment writes four bytes: four Y samples in the first `height` rows, two interleaved UV pairs
   below, which is the NV12 byte layout; RGBA8 rather than RED8 because GL never sets
   `GL_PACK_ALIGNMENT` for readback and RGBA8 is the target format every backend already copies.
   Rows are fetched with `texelFetch` at `height - 1 - row`, the same memory-row flip the CPU did,
   so the result is top-down whatever the backend's origin. Colour is BT.709 limited range:
   `Y = 16 + 219 (0.2126 R + 0.7152 G + 0.0722 B)`, `U = 128 + 224 (B - Y') / 1.8556`,
   `V = 128 + 224 (R - Y') / 1.5748`, rounded by the unorm store. Chroma copies what ffmpeg's
   swscale does for rgba to nv12 (measured with single-pixel impulses): a plain average of the two
   pixels horizontally and a vertical 2:1 bicubic (B 0, C 0.6, eight taps
   `-0.0140625 -0.0421875 0.1203125 0.4359375` mirrored, edge rows clamped). `Nv12ConverterTest` runs
   `export/Nv12Converter` (the same math in integer fixed point, used on the CPU under VulkanMod) against ffmpeg's own conversion of the same RGBA frame (the RGBA
   fallback path): every sample within 1, Y about 69 dB, U and V 67 to 75 dB, 98.8 to 100% exact, and
   the same on real 1080p screenshots. NV12 is used when the arguments contain `%INPUT%`, width is a
   multiple of 4, height is even, and `GpuDevice.precompilePipeline` reports the pipeline valid;
   otherwise (or if building it throws) the export logs once and reads back RGBA as before, flipped on
   the CPU. The chosen format is in the "Exporting" log line.
   Four `GpuBuffer`s of one frame each with `USAGE_MAP_READ | USAGE_HINT_CLIENT_STORAGE | USAGE_COPY_DST` form a
   free pool. A grab takes a free buffer and calls
   `copyTextureToBuffer` with a callback; vanilla queues that callback behind its own fence
   (`RenderSystem.queueFencedTask`) and `RenderSystem.executePendingTasks`, at the start of every
   `MinecraftClient.render` pass, runs it only once `awaitCompletion(0)` reports the fence done, in
   order. The callback maps the buffer and hands the mapped view to `FfmpegProcess.writeFrame`, which
   returns a future for the copy out of it; the view stays mapped while the copy runs off the render
   thread. Each grab and drain check first reclaims, on the render thread, every buffer whose copy is
   done: it closes the view and returns the buffer to the pool. If the pool is empty the pass is a
   readback stall and the same frame is rendered again. `close` waits for copies still in flight
   (bounded by one memory copy, they never block) before closing buffers, so no thread reads a
   deleted mapping.
   Why off the render thread: on the Iris Xe ARB/DSA path `createBuffer` maps readable buffers
   persistently (`GL_MAP_READ_BIT | GL_MAP_PERSISTENT_BIT`, not coherent), `mapBuffer` is a slice of
   that mapping and `Mapped.close` only flushes write mappings, but reading the mapping runs at about
   320 MB/s (25 ms per 1080p frame against 3.3 ms from heap), the profile of uncached memory, and
   `USAGE_HINT_CLIENT_STORAGE` did not change it. Uncached reads are latency bound per core, so the
   copy is split across cores and overlapped with rendering instead of serialised on the render thread.
   Why: `GlGpuFence.awaitCompletion` is `glClientWaitSync` with flags 0 (no
   `GL_SYNC_FLUSH_COMMANDS_BIT`), so waiting on a fence whose commands were never flushed can block
   forever; the old `flush()` did exactly that after the last grab, before any buffer swap, and hung
   an Intel iGPU export at 100%. The only blocking fence waits left in a pass are vanilla's own
   `MappableRingBuffer.getBlocking` calls in `GuiRenderer`; the progress overlay renders the GUI a
   second time per pass, so the three-slot ring rotates twice per pass and the slot it waits on was
   fenced at least one pass earlier, i.e. before a `glfwSwapBuffers`, which flushes.
   **Drain.** After the last grab the session keeps rendering passes with zero ticks and grabs
   nothing until every callback has run, then checks that the delivered frame count equals the
   planned count (logged; a mismatch fails the export) and hands off.
8. **Encode** (`FfmpegProcess`, `EncodingPreset`), ReplayMod's model. The export screen holds an
   ffmpeg command and an argument template, both editable; picking a preset resets the template and
   the output extension. Presets: MP4 hardware (`h264_nvenc`, `h264_amf`, `h264_qsv`, each offered only
   when it works), MP4 default (`libx264 -preset superfast -crf 18`), MP4 custom bitrate, MP4 potato,
   WebM custom bitrate, MKV lossless. The first working hardware preset is the default.
   `EncoderProbe` runs once per game session from `MinecraftClient.<init>` on a background thread:
   for each hardware preset a one-frame `lavfi` encode to `-f null`, 15 s timeout.
   Parameters were chosen by measurement on a 5 s 1080p60 clip panned and zoomed over real Minecraft
   screenshots (SSIM/PSNR against the source in yuv420p, ffmpeg 9.0.2, i5-1235U, Iris Xe):

   | Encoder and settings | CPU s | wall fps | size | SSIM | PSNR dB |
   |---|---|---|---|---|---|
   | libx264 medium crf 18 (old default) | 38.4 | 65 | 5.9 MB | 0.9826 | 45.21 |
   | libx264 veryfast crf 16 | 19.4 | 117 | 8.7 MB | 0.9832 | 45.39 |
   | libx264 superfast crf 18 (software default) | 9.2 | 180 | 12.0 MB | 0.9862 | 46.32 |
   | libx264 ultrafast crf 17 | 4.8 | 198 | 23.1 MB | 0.9857 | 46.55 |
   | h264_qsv medium gq 20 | 3.3 | 111 | 7.8 MB | 0.9803 | 44.63 |
   | h264_qsv medium gq 17 (hardware default) | 2.3 | 119 | 10.8 MB | 0.9841 | 45.58 |
   | h264_qsv medium gq 16 | 3.9 | 96 | 12.5 MB | 0.9861 | 46.25 |

   CPU seconds include about 1.9 s of rgba to yuv conversion and file reading that every row pays.
   The game and the encoder share the CPU, so CPU time per frame is what limits an export; both
   defaults match or beat the old default's SSIM for a quarter (software) or a sixteenth
   (hardware) of its CPU. NVENC (`p5 hq vbr cq 19`) and AMF (`cqp 18/20`) are unmeasured here.
   `%INPUT%` becomes the input description for the frame format, then `%WIDTH% %HEIGHT% %FPS%
   %FILENAME% %BITRATE%` are substituted (FILENAME is the bare name, ffmpeg runs in the output folder,
   BITRATE is bits per second), and the
   string is split on whitespace with double quotes grouping. The command defaults to
   `-Dmomenton.ffmpeg`, then on Windows `<gameDir>/ffmpeg/bin/ffmpeg.exe`, any `ffmpeg.exe` within
   four levels of the game directory (skipping `saves`, `momenton`, `logs`, `assets` and the like) or
   a winget install, on macOS `/usr/local/bin`, `/usr/bin` and the newest Homebrew Cellar build, else
   plain `ffmpeg` from PATH. `writeFrame` takes one of three pooled `byte[]`s (blocking while all three
   are in use: the ffmpeg wait), splits the rows into four bands, copies each band (NV12, already
   top-down) or flips it (RGBA, bottom-up) from the readback straight into the array on a fixed pool of four "Momenton frame copy" threads (one copy)
   and queues the frame with its copy future; it returns that future, and the caller keeps the source
   memory valid until it completes. The writer thread takes frames in queue order, waits for each copy
   and pipes it to stdin, so frames reach ffmpeg complete, in order and top-down; stdout and stderr are merged and
   teed to `<gameDir>/momenton/export.log` (first line `> ` plus the command) and memory, and the
   reader parses `frame=` for progress. A process that cannot be launched is `NotFoundException`; a
   failure before the log shows an `Output #0` line is `StartupException` (rejected arguments), any
   later failure `FfmpegException`; both carry the log. `finish` closes stdin and waits as long as the
   reported frame count keeps rising, killing ffmpeg only after 60 s without progress. `close` never
   waits: it interrupts the writer, stops the copy pool and calls `destroyForcibly`. `discard(files)`
   closes and deletes the temporaries on a "Momenton export cleanup" thread once the process has
   exited, retrying for up to 5 s because a killed h264_qsv ffmpeg keeps its output locked for about
   60 ms after its process handle is signalled. Why: cancel used to call `destroyForcibly().onExit().join()`
   on the render thread; an Esc at frame 1017 of a Quick Sync export logged "Export cancelled" (the
   last line of `restore`) and then hung the game for a minute until it was killed, with the video and
   the already-closed PCM still on disk, so the render thread never returned from that join. The
   render thread must never wait for an external process.
   **Input and colour tags.** NV12 input is
   `-f rawvideo -pix_fmt nv12 -colorspace bt709 -color_primaries bt709 -color_trc bt709 -color_range tv`;
   RGBA input is `-f rawvideo -pix_fmt rgba -color_primaries bt709 -color_trc bt709 ... -i -
   -vf scale=out_color_matrix=bt709:out_range=tv`. The tags go on the input because ffmpeg 9 takes
   the stream's colour properties from the frames: output-only tags were ignored for trc and
   primaries, and on untagged NV12 they made the auto-inserted scaler convert the planes (max error 77).
   With input tags every preset's output is tagged `tv, bt709` and NV12 reaches libx264 bit-exact
   (`-qp 0` round trip). Before this, RGBA was converted with swscale's default BT.601 matrix and left
   untagged, which players show with BT.709, so reds were slightly off. Hardware encoders and x264/vpx
   take NV12 directly or repack it to yuv420p with no colour math. **MKV lossless stays RGBA** (its
   arguments spell out the RGBA input instead of `%INPUT%`), encoded as yuv444p: 4:2:0 halves chroma
   resolution, which smears coloured HUD text and item edges, and a lossless preset should lose only
   8-bit rounding.
   Session memory only: the screen remembers the last preset, command, arguments and bitrate.
9. **Finish off the render thread.** At hand-off the render thread restores the window: GUI scale,
   real framebuffer size, vsync, the sound device (`LoopbackRenderer.close`, which keeps the PCM), and
   leaves the replay paused. `ExportFinisher` then runs `ffmpeg.finish()` and the audio mux on its own
   thread while `ui/ExportFinishingScreen` shows "Finishing video" with a progress bar (encoded frames,
   then the mux's `time=`), the elapsed time and Cancel (Esc too). Cancel kills ffmpeg or the mux
   without waiting; the finishing thread then sees the process end and deletes the temporaries and a
   partial output (with the same retry as `discard`). A cancelled render calls `discard`. When done the screen returns to the screen that was
   open and toasts the result, or shows `ExportFailedScreen`.
10. **Progress overlay** (`ui/ExportProgressOverlay`), after ReplayMod's `GuiVideoRenderer`. The
   window never shows the raw replay: after each pass's grab has been recorded, the overlay clears the
   GUI state, draws into the main target and runs `GuiRenderer.render` a second time (through
   `ExportGameRendererAccessor`), so it is never in the video. It shows a progress bar with frames
   done and total, elapsed, remaining (from the last 50 frames), render fps and a preview of the last
   grabbed frame, copied on the GPU with `copyTextureToTexture` into a texture registered as
   `momenton:export_preview` and drawn flipped. P toggles the preview, Esc cancels. Because
   `presentTexture` blits 1:1, the overlay is laid out inside the part of the export-size target that
   the real window shows.
11. **Instrumentation.** Every 5% the log line carries, per grabbed frame since the last line: fps,
   render passes, wall ms per pass, `GameRenderer.render` ms, settle ms, terrain-wait ms, readback
   stalls, convert ms (render-thread time to record the NV12 pass; the GPU time is not measured,
   because a timer query throws while vanilla's `GlTimer` profiles the frame), map ms (`mapBuffer` on
   the render thread), submit ms (render-thread time in `writeFrame`
   besides the wait), ms blocked waiting for a free ffmpeg buffer, copy ms per frame (wall time from
   submit to the last band done, off the render thread) with its throughput in MB/s of frame bytes (NV12 or RGBA), audio ms
   (`submitAndJoin` plus `alcRenderSamplesSOFT` plus the PCM write) and overlay ms, plus ffmpeg's
   encoded frame count.
12. **Around the frame.** Vsync off and the inactivity fps limit lifted; the pause menu never opens on
   focus loss; toasts are not drawn; keyboard input is cancelled except Esc, which cancels the export,
   and P; the cursor sprite `textures/gui/cursor.png` is drawn after the screen when enabled. Stale
   `momenton-video-<name>` and `momenton-audio-<name>.pcm` from an earlier run with the same output
   name are deleted at start.

```
ffmpeg -y %INPUT% -an \
       -c:v h264_qsv -preset medium -global_quality 17 -look_ahead 0 -pix_fmt nv12 "%FILENAME%"
```

The video is encoded with `-an`; game audio is a separate raw PCM file muxed in afterwards (see
Audio). No frame composition logic belongs in the export package. Export is the playback view on a
fixed clock plus a readback.

### Audio: why game sound is rendered at export

The world is re-simulated, so a waveform captured during play would not match your edits. At export
the sound engine is moved onto an `ALC_SOFT_loopback` device and Momenton drives
`alcRenderSamplesSOFT` itself, requesting **exactly the sample count matching each grabbed frame**.
That gives sample-exact A/V sync no matter how slowly the offline render runs. The design was built
from the 1.21.11 bytecode of `SoundEngine` and `SoundSystem`, not taken from ReplayMod.

- `audio/LoopbackRenderer.start` sets a static `active` flag and calls `SoundManager.reloadSounds()`,
  so vanilla tears the engine down and runs `SoundEngine.init` again. `close` clears the flag, closes
  the PCM file and reloads again, which puts the real device back; `ExportSession.restore` runs it on
  success, cancel and error alike, and the PCM is deleted after the mux or on cancel and error.
- `mixin/client/audio/SoundEngineMixin` wraps `openDeviceOrFallback` to return
  `alcLoopbackOpenDeviceSOFT(null)` while active, and replaces `createAttributes` with 48 kHz, stereo,
  `ALC_SHORT_SOFT`, HRTF as vanilla asked, output limiter on.
- `mixin/client/audio/SoundSystemMixin` joins the static and streamed sound loads in `play` while
  active (a sound that is still loading would otherwise start frames late, or never, because the
  loopback only advances when rendered), and returns false from `shouldReloadSounds`, so the
  device-change poll cannot swap the loopback back to a real device mid-export.
- Per grabbed frame `k`, `render()` first flushes the sound executor's queue
  (`SoundExecutor.submitAndJoin`), then renders `round(k * 48000 / fps) - samplesSoFar` samples, so the
  total never drifts by more than one sample, and appends them to `momenton-audio-<name>.pcm`.
- At a cut, `stopOneShots()` stops every non-repeating sound still playing, so nothing from before
  the cut is heard after it.
- `FfmpegProcess.mux`, on the finishing thread, then runs `ffmpeg -nostdin -y -i video -f s16le -ar 48000 -ac 2 -i pcm
  [-f s16le -ar <micRate> -ac 1 -i mic.pcm] -filter_complex "[1:a]volume=G[a0];[2:a]volume=M[a1]" -map 0:v -map [a0] -map [a1]
  -c:v copy -c:a aac|libopus -b:a 192k -metadata:s:a:N title=... handler_name=... -disposition:a:0 default
  -disposition:a:1 0 -shortest output` (Opus for `.webm`) and appends its output to `export.log`. Game is
  the first, default track titled "Game"; commentary the second, titled "Commentary" (`title` names
  the track in Matroska, `handler_name` in MP4). A volume of 0 drops that input and its track; with
  both at 0 only the video is copied. The temporary `momenton-video-*`, game PCM and
  `momenton-mic-*.pcm` files are deleted however the export ends.
- **Mix mode** (some players only play the first track) replaces the two outputs with
  `[a1]pan=stereo|c0=c0|c1=c0[voice];[a0][voice]amix=inputs=2:duration=first:normalize=0[mix]`, one
  track titled "Game and commentary". The voice lands at unity in both channels and `normalize=0`
  keeps amix from halving the inputs, so the slider values are the levels heard.

**Mic commentary** is recorded live, because a voice cannot be re-simulated.

- `audio/MicRecorder` opens a `TargetDataLine` at 48 kHz mono s16 on its own platform
  thread when recording starts and streams it through `audio/WavWriter` to `.<name>.mic.wav` beside
  the staging directory. The first sample's tick is
  `originTick + (nanoTime at line.start - nanoTime at F9) / 50 ms`, where `originTick` is the next
  frame's fractional tick (`1 + tickProgress`). No microphone logs once and records without one.
- Settings live in `<gameDir>/config/momenton.json` (`config/MomentonConfig`, Gson, read on first
  use): `micEnabled` (default true) and `micDevice` (a mixer name, `""` for the system default).
  The unbound toggle key and `ui/SettingsScreen` (from the replay browser) both flip and save
  `micEnabled`. The screen cycles the capture mixers that support the format and shows a peak meter
  (`audio/MicMeter`, -60 to 0 dB). A saved device that is gone falls back to the default with one
  warning.
- At stop, after `ReplayWriter.finish` has written the zip, `MicTrack.attach` adds the WAV as a
  STORED `audio/mic.wav` entry through a zip `FileSystem` and deletes the loose file; metadata carries
  `micStartTick` and `micSampleRate`. STORED keeps the entry seekable for playback.
- **Sync rule** (`audio/MicPlayback`, client tick, never while exporting): the expected sample is
  `round((currentTick + tickProgress - micStartTick) * sampleRate / 20)`. A playback thread streams
  the entry into a `SourceDataLine`, writing silence before the track starts and after it ends. It
  re-seeks when the tick jumps (anything but the same tick or the next) or when playback drifts more
  than 3 ticks from the expected position, and stops the line while paused.
- **Export** (`ExportFinisher`, after the video encode finishes, when the replay has a mic and its
  volume is above 0): the WAV is cut straight out of the STORED zip entry into a raw mono
  `momenton-mic-<name>.pcm` exactly as long as the game PCM, `round(frames * rate / fps)` samples.
  Clip `i` of the export occupies output samples `[round(T_i * rate / 20), round(T_(i+1) * rate / 20))`,
  where `T_i` is the tick length of the clips before it, so a sample on a cut belongs to the next clip,
  as frames do; the last clip runs to the end of the last frame. Its samples come from the mic at
  `round((clip.startTick - micStartTick) * rate / 20)` onward, the same rule as live playback, and
  anything before the first or past the last recorded sample is silence.
- `ExportScreen` shows a game volume slider, and for recordings with a mic a voice slider and an
  own-track / mixed-in toggle (0-200 % in 5 % steps, 0 = muted), remembered for the game session.
  The volumes travel in `ExportSettings`; the toggle is the static `ExportFinisher.mixCommentary`,
  captured when the finisher starts, because the settings record is shared and fixed. It belongs in
  `ExportSettings` the next time that record changes.

Consequence to accept: the live preview has no mixed game audio - the engine makes its own sound
during playback, and the mic WAV is played back in sync as a preview-only convenience.

LWJGL **OpenAL** is fine to call directly - VulkanMod only replaces the graphics backend.

---

## The constraint that shapes the rendering: VulkanMod

**Export under VulkanMod (Wave 10, from VulkanMod 0.6.8 bytecode).** Blaze3D alone is not enough there.
The main target is the current swapchain image; sampling it from our own render pass makes
`VulkanImage.readOnlyLayout` record its barrier in a separate upload command buffer submitted before
the frame (wrong old layouts: `VK_ERROR_DEVICE_LOST` on Intel, seen in game); and
`copyTextureToBuffer` runs in its own one-shot submit before the frame's draws. So `export/VulkanMod`
(reflection only, no compile dependency) does what VulkanMod's own `ScreenshotUtil` does: call
`Renderer.flushCmds()` before the copy, then reset `Renderer.boundPipelineHandle` (the flush opens a
fresh command buffer without clearing that cache). `ImageUtil.copyImageToBuffer` takes a command
buffer from the graphics pool and never returns it (one buffer, fence and semaphore leaked per call;
the Intel driver crashed in `vkCmdCopyImageToBuffer` after about 8200 frames), so Momenton reads the
pool's next buffer before the copy and calls its `reset()` after (Wave 11). It reads RGBA or BGRA
(swapchain format 44) top-down, at the window size rounded down to even (VulkanMod ignores the
panorama-style resize; the export screen shows the size, not editable), and converts to NV12 on the
copy threads (`Nv12Converter`, a frame split across 4 threads, ~3.5 ms wall per 1080p frame;
ffmpeg measured 142 fps on RGBA input against 224 fps on NV12); MKV lossless stays RGBA. The preview
is built from the CPU frame every 4th frame (`copyTextureToTexture` is a no-op there). Hardware
encoders are not offered (Quick Sync lost the device in 2 of 2 tries, cause unproven). Terrain is
judged with VulkanMod's own `graphNeedsUpdate` and task dispatcher.

VulkanMod (0.6.3 supports 1.21.11, LGPL-3.0) replaces Minecraft's GL backend. Any mod making direct
OpenGL calls is guaranteed broken under it. Minecraft 1.21.6+ introduced the `GpuDevice` /
`CommandEncoder` abstraction in `com.mojang.blaze3d` precisely to hide the backend.

**Hard rule: no `org.lwjgl.opengl` import anywhere in this codebase.** All GPU work goes through
Blaze3D. This makes VulkanMod support real rather than aspirational, and future-proofs the mod against
Mojang's announced move to Vulkan for Vibrant Visuals. Enforced by the `checkNoDirectOpenGl` Gradle
task, which `check` depends on. The editor uses vanilla Screens, so the only Blaze3D-sensitive path
Momenton adds is the export readback, and the Export gate runs once under VulkanMod to prove it.

### Verified 1.21.11 API surface

Checked against the Yarn 1.21.11 javadocs rather than recalled:

- `net.minecraft.network.ClientConnection.channelRead0(ChannelHandlerContext, Packet<?>)`; also
  `send(Packet<?>, ChannelFutureListener, boolean)` and the volatile `packetListener` /
  `prePlayStateListener` fields.
- `com.mojang.blaze3d.systems.CommandEncoder` -
  `copyTextureToBuffer(GpuTexture, GpuBuffer, long, Runnable, int)`, the 9-arg region overload,
  `mapBuffer(GpuBuffer, boolean, boolean)`, `writeToTexture(GpuTexture, NativeImage)`,
  `createRenderPass(Supplier<String>, GpuTextureView, OptionalInt, GpuTextureView, OptionalDouble)`,
  `createFence()`.
- `net.minecraft.client.gl.Framebuffer` - `getColorAttachment()` returns `GpuTexture`,
  `getColorAttachmentView()` returns `GpuTextureView`, public `textureWidth` / `textureHeight`.
- `net.minecraft.client.util.ScreenshotRecorder.takeScreenshot(Framebuffer, Consumer<NativeImage>)`.
- `net.minecraft.client.gui.hud.InGameHud.render(DrawContext, RenderTickCounter)`, plus the private
  per-element methods (`renderHotbar`, `renderStatusBars`, `renderChat`, `renderCrosshair`,
  `renderBossBarHud`, `renderStatusEffectOverlay`, `renderTitleAndSubtitle`, `renderOverlayMessage`)
  - the injection points for HUD element toggles.
- `net.minecraft.client.gui.screen.Screen.render(DrawContext, int mouseX, int mouseY, float deltaTicks)`
  and `renderWithTooltip(...)` with the same signature.
- `net.minecraft.client.render.RenderTickCounter` - `getTickProgress(boolean)`,
  `getDynamicDeltaTicks()`, `getFixedDeltaTicks()`, `RenderTickCounter.Constant`, `.Dynamic`. It is an
  interface, so export implements it directly.
- `net.minecraft.client.sound.SoundEngine.init(String, boolean)`, `getSoundDevices()`,
  `getCurrentDeviceName()`, `updateDeviceSpecifier()`.
- `net.minecraft.network.state.PlayStateFactories` / `NetworkState` / `NetworkStateBuilder`.

Confirmed while building Phase 1, against Yarn 1.21.11+build.6:

- `PlayerInventory.getSelectedSlot()` / `setSelectedSlot(int)` - the known mover, current as of
  +build.6.
- `PlayStateFactories.S2C.bind(RegistryByteBuf.makeFactory(registryManager)).codec()` gives a
  `PacketCodec<ByteBuf, Packet<? super ClientPlayPacketListener>>` that writes packet id and payload
  together. That is the whole re-encode path.
- `LivingEntity.handSwinging`, `handSwingTicks` and `preferredHand` are **public fields**, but hand
  swing is reproduced by replaying `swingHand` rather than by assigning them - vanilla advances them
  itself in `tickHandSwing`, so an assignment at HEAD is advanced a second time on the same tick.
  `swingHand` restarts a running swing only when `handSwingTicks >= getHandSwingDuration() / 2`, which
  means a burst of clicks shows up in a recording as the counter dropping while `handSwinging` stays
  set, never as a rising edge. Both are starts.
- `MessageType.Parameters.applyChatDecoration(Text)` produces the finished chat line, which is what
  makes rewriting signed chat as `GameMessageS2CPacket` lossless.
- An unclaimed custom payload decodes to `UnknownCustomPayload`, which keeps only the id and discards
  the body - so those packets must be dropped, never re-encoded.
- The four Phase 1 mixin targets remap to real intermediary names in the built jar
  (`class_2535.method_10770`, `class_338.field_2066`, `class_757.method_3196`); Loom fails the build
  if a target does not resolve.

Confirmed while building Phase 3, by disassembling the merged jar rather than from the javadocs -
these are about *order inside a method*, which javadocs do not carry:

- `ClientWorld.tickEntity` calls `Entity.resetPosition()` and then `Entity.tick()`.
  `resetPosition` copies the current position and angles into `lastX/lastY/lastZ` and
  `lastYaw/lastPitch`, which are what rendering interpolates from. So the head of
  `ClientPlayerEntity.tick` is the only point where a driven pose both renders interpolated *and*
  presents vanilla with the right movement delta.
- `LivingEntity.tick` derives the target body yaw from `atan2(getZ() - lastZ, getX() - lastX)` and
  hands it to `turnHead`; `LivingEntity.tickMovement` calls `updateLimbs(boolean)`, which measures the
  same difference. Both run inside `tick()`, after the head.
- `PlayerEntity.tick` assigns `this.noClip = this.isSpectator()` as its **first** instruction, so
  `noClip` cannot be used to un-simulate a driven player.
- `Entity.setPosition(double, double, double)` is `setPos` plus the bounding box;
  `Entity.setPos` alone leaves the box behind. `fallDistance`, `noClip`, `lastX/lastY/lastZ` and
  `lastYaw/lastPitch` are public fields; `LivingEntity.limbAnimator`, `bodyYaw` and `headYaw` are too.
- `GameJoinS2CPacket` writes `playerEntityId` as a plain `int`, first, immediately after the packet
  id - which is how `tools/Motion` reads the local player's entity id out of a snapshot without a
  registry manager.
- **The camera's eye height is `Camera` state, eased once per tick.** `Camera.updateEyeHeight()` does
  `cameraY += (focusedEntity.getStandingEyeHeight() - cameraY) * 0.5f` and is called from
  `GameRenderer.tick()`, so a crouch slides 1.62 -> 1.445 -> 1.357 -> ... over about five ticks
  rather than snapping to 1.27. Playback does not care because `CameraMixin` overwrites the camera
  position from `frames.bin`, which has the eased value baked in; export must still drive
  `GameRenderer.tick` (see Export).
- `MinecraftClient.render` calls `PacketApplyBatcher.apply()` before `tick()`, which is the cause of
  the snapshot-boundary race.

Members that needed accessors because they are private:

- `GameRenderer.getFov(Camera, float, boolean)` - `GameRendererInvoker`.
- `ChatHud.scrolledLines` - `ChatHudAccessor`.
- `ChatScreen.chatField`, `TextFieldWidget.editableColor` - `ChatScreenAccessor`,
  `TextFieldWidgetAccessor` (replayed chat).
- `ChunkRenderingDataPreparer.terrainUpdateScheduled`, `terrainUpdateFuture`, `needsUpdate` -
  `ChunkRenderingDataPreparerAccessor` (export chunk wait). `BlockBufferBuilderPool` keeps no
  capacity field, so `BlockBufferBuilderPoolMixin` records the list size at construction.

`RenderTickCounter.Constant(float)` has a package-private constructor, but export does not need it
(see Export), so there is no access widener.

Also note `WorldRenderContext` in fabric-rendering-v1 16.2.10 no longer exposes `camera()` or
`tickCounter()`; the frame track hooks `GameRenderer.render` at TAIL and reads
`gameRenderer.getCamera()` instead.

Yarn names drift between mapping builds. Run `./gradlew genSources` and verify against the decompiled
output, or `javap` against the merged jar, before writing any mixin. Do not trust this list after a
mappings bump.

---

## Source reuse: what we borrow, and from where

This mod is private and will not be distributed, which widens what is usable - but not without limit.
Do not reinvent anything on this list.

| Source | Licence | What to take |
|---|---|---|
| **ReplayMod** (github.com/ReplayMod/ReplayMod) | GPLv3 | The primary reference to lift from. Packet recording and filtering, replay playback over a fake connection, seeking, and its ffmpeg export settings. GPL obligations attach to distribution, so a private mod can borrow freely. |
| **VulkanMod** | LGPL-3.0 | Read its `GpuDevice` implementation to learn which Blaze3D paths it actually supports before relying on one. |
| **Minecraft** (via `./gradlew genSources`) | - | The authority for HUD and screen rendering order, and for every Yarn name above. |
| **Flashback** (github.com/Moulberry/Flashback) | **All rights reserved** | Read for understanding only. Its licence is `Do not redistribute. All rights reserved.`, granting no copying or modification rights; private use does not change that. Its architecture is already documented in this file; reimplement from that, and use ReplayMod when actual code is wanted. |

### Borrowed from ReplayMod

New code carries no comments, so borrowed code is tracked here instead: one line per Momenton file,
naming the ReplayMod source it came from, so the next session knows which parts are ours when
something breaks.

- `export/ExportSession` chunk wait: the idea of ReplayMod's `Mixin_BlockOnChunkRebuilds` (do not
  grab a frame until chunk rebuilds are done). The idea only; no code was copied, and the conditions
  are 1.21.11's own (see Export, step 4).
- `export/EncodingPreset`: `RenderSettings.EncodingPreset` (the preset argument strings, the common
  input part with `bgra` changed to `rgba`, extension, bitrate flag), plus a MP4 default-quality preset.
- `export/FfmpegProcess`: `RenderSettings.findFFmpeg` (discovery order, Homebrew Cellar search) and
  `FFmpegWriter` (placeholder substitution, output folder as working directory, export.log tee,
  `Output #0` startup check, `NoFFmpegException` / `FFmpegStartupException`; its fixed 30 s close then destroy was replaced by a progress watchdog).
  The quote-aware splitter replaces commons-exec `CommandLine.addArguments`.
- `ui/ExportScreen`: `GuiRenderSettings` behaviour (preset resets the arguments and output
  extension, bitrate only for bitrate presets, editable command and arguments).
- `ui/ExportFailedScreen`: `GuiExportFailed` (log in a scrolling list, back to the settings).
- `ui/ExportProgressOverlay`, `export/ExportProgress`: `GuiVideoRenderer` (title, preview fitted
  into the space above the stats, preview toggle, time taken on the left and time left on the right
  above a frames done / total progress bar, remaining time from the average of the last 50 frames
  ignoring the first). The preview is a GPU texture copy instead of ReplayMod's per-pixel CPU upload.

---

## Phases

Each phase ends in something runnable. Do not start a phase before the previous one demonstrably
works. Granular checkboxes live in `TASKS.md`.

### Phase 0 - Foundations (complete)

Strip the Fabric template, add the client entrypoint and mixin block, and put the no-OpenGL guard in
place.

**Done when:** `./gradlew build` produces a jar and `checkNoDirectOpenGl` runs in `check`.

### Phase 1 - Recording (complete)

`record/Recorder.java`, `record/FrameTrackWriter.java`, `record/UiStateTracker.java`,
`record/EntityPositionTracker.java`, `record/LocalPlayerWriter.java`, `format/ReplayWriter.java`,
`format/ReplayMetadata.java`, `format/ActionCodec.java`, `mixin/client/ClientConnectionMixin.java`

Mixin `channelRead0` at HEAD, forwarding to `Recorder.onPacket`. Keep the mixin body to a single
static call. `Recorder` runs IDLE to RECORDING to FINALISING and hands packets to a single-threaded
writer executor - never do IO on the netty thread. `FrameTrackWriter` appends one 56-byte record per
rendered frame; keep it allocation-free, writing into a reused `ByteBuffer` flushed in blocks.
`ReplayWriter` rolls a chunk every 500 ticks, streams each part as a loose file into a staging
directory, and zips the staging directory at stop, so a crash keeps every completed part.

**Done when:** a minute of survival play produces a `.momenton` zip whose `metadata.json` reports a
plausible tick count, whose chunks are non-empty, and whose `frames.bin` size equals
`56 * framesRendered`.

### Phase 2 - Playback (complete)

`playback/ReplaySession.java`, `playback/OfflineConnection.java`, `playback/PovDriver.java`,
`record/WorldSnapshot.java`, `mixin/client/playback/*.java`

Built without the planned `Seeker` and `SnapshotIO` classes. Seeking is a few lines inside
`ReplaySession.jump` - load the snapshot covering the target, replay forward without waiting for
ticks - because loading a snapshot is already what starting playback does. Writing a snapshot is
`record/WorldSnapshot` producing packets, and reading is the packet path that already existed.

**A snapshot is not free, so a seek only loads one when it has to.** Only a *backward* seek needs a
snapshot, because packets cannot be un-applied. A forward seek is playback run without waiting for
ticks, over the world that is already loaded. `jump` loads a snapshot only when the target is behind
the playhead or more than one chunk's worth of ticks ahead.

**A seek reuses the world in place (Wave 4).** Applying a snapshot's join packet means
`MinecraftClient.joinWorld`, a new `ClientWorld` and the terrain-loading screen, so only the first
load does it. `OfflineConnection.applySnapshot` keeps one connection for the whole session and, once
a world exists, removes the join from the snapshot and instead:

- if the snapshot is in the current dimension, clears the world in place: an `UnloadChunkS2CPacket`
  for every chunk it fed that the snapshot does not resend (it tracks fed chunks itself), one
  `EntitiesDestroyS2CPacket` for every entity but the player, all sounds stopped, particles cleared,
  game modes from the join, a `DeathScreen` closed;
- if it is in another dimension, feeds a `PlayerRespawnS2CPacket(spawn, KEEP_ALL)` built from the
  join, which makes the right `ClientWorld` the way a portal does;
- then `ChunkLoadDistanceS2CPacket` with the join's view distance, and the rest of the snapshot.

This also fixed a latent bug: the old rejoin reused the player, so seeking back from the Nether to an
Overworld snapshot loaded Nether chunks into the Overworld world. A seek whose snapshot-to-target span
crosses a dimension change still shows the loading screen, because the recorded respawn builds a new
`ClientWorld` and that resets the renderer; removing it needs a snapshot recorded right after each
dimension change. The editor reopens itself once such a loading screen closes.

**A dimension change is a second join in the middle of the stream, and it needs the same three
things.** `PlayerRespawnS2CPacket` builds a new `ClientWorld`; after it the client needs a player
position, a chunk-map recentre and `INITIAL_CHUNKS_COMING`, and it gets them from the recorded stream.
The player position is the one that is easy to lose - `ClientChunkLoadProgress.LoadChunks.isReady`
waits on `worldRenderer.isRenderingReady(player.getBlockPos())`, so a player left at its old
coordinates holds the terrain screen for its 30-second timeout. `tools/Setup` checks all three per
dimension change, within a one-second window after the respawn.

**Playback needs no world, so the title screen is a valid entry point.** `ReplaySession.open` builds
its own world from the snapshot and only disconnects first if one already exists. The keybind cannot
reach that from the menus - `KeyBinding.wasPressed` never fires while a screen has focus - so
`ui/TitleScreenEntry` adds a button through `ScreenEvents.AFTER_INIT` rather than a mixin. Export and
the editor hang off the same browser, so "open the game, open a recording, export" never joins a
world.

`OfflineConnection` builds a `ClientConnection` over a netty `EmbeddedChannel` with a real
`ClientPlayNetworkHandler` attached, so vanilla handlers do all world reconstruction.

**Done when:** a replay plays back at 1x with entities moving correctly and the camera matching what
you saw, and seeking to an arbitrary tick lands in under two seconds.

### Phase 3 - Replay fidelity

`playback/LocalPlayerDriver.java`, `playback/UiStateDriver.java`, `record/LocalPrediction.java`,
`record/ScreenTracker.java`, `playback/ReplayScreenInput.java`, a `ClientWorld` mixin and a
`GameRenderer` mouse-argument mixin.

**The subject of this phase is client-side prediction.** A packet recorder replays what the *server*
said, and the live client had already drawn a great deal the server never sent, or sent a tick late.
When something looks a beat late or does not happen at all, ask whether the live client predicted it.
If the missing thing has a packet shape, synthesise that packet (see Packet capture). Otherwise record
the value rather than recomputing it - the inputs to the prediction are exactly what a replay does
not have. The test for whether anything is needed at all is not "does the client run this code" but
"does the client run it *and* produce something the wire does not carry" - and answer it by measuring
the recording, not by reading the source.

**Moving the replayed player.** `frames.bin` holds the **camera** pose, which is the player's eye
only in first person, so the player's position comes from `ENTITY_MOVE`. **Where a driven value is
applied is part of the design:**

- **Position** goes on at the head of `ClientPlayerEntity.tick`, because `ClientWorld.tickEntity` has
  just called `resetPosition()` and vanilla is about to derive body yaw and the limb animation from
  `getX() - lastX`.
- **Rotation** goes on once per *frame*, from `frames.bin`, with `lastYaw` set alongside, because
  vanilla turns the local player from `Mouse` once per frame and the recorded camera *is*
  `player.getYaw(tickProgress)`. In third-person front the camera is `(yaw + 180, -pitch)`, so
  `FLAG_FRONT_VIEW` marks those frames and playback undoes the inversion.
- **Head yaw** goes on nowhere. `PlayerEntity.tickMovement` derives it from the look direction, and
  `LivingEntity.baseTick` captures `lastHeadYaw` *during* the tick.
- **Sneak** goes back through `player.input`, because `ClientPlayerEntity.isSneaking()` is an override
  that reads nothing else.
- **Animations** (hand swing, attack cooldown, the held item's dip) are fed as *starts* - `swingHand`,
  and the recorded reset event (`resetTicksSince` resets cooldown and dip, `resetTicksSinceLastAttack`
  only the cooldown) - never as recorded counter values. Old recordings without the event fall back to
  `resetTicksSince` on every recorded cooldown drop.

**A hit is entirely the server's on 1.21.11.** `PlayerEntity.attack` gates its effects on
`target.sidedDamage(...)`, which on a `ClientWorld` is `Entity.clientDamage` - overridden by eight
classes, none of them `LivingEntity` - so against a mob the local call does nothing, and every visible
part of a hit (sweep and damage-indicator particles, `ANIMATE` crit, `DAMAGE_EVENT`, knockback, attack
sounds) is already in the recording. Do not rebuild an `ATTACK` action without first checking
`clientDamage` on the target class.

The general rule: recompute nothing vanilla can recompute for itself, give vanilla the input it would
have had, and apply it where vanilla would have.

**Done when:** a recording with block breaking and placing, a chest, item moves, chat typing and a
portal plays back right, checked visually. Record once on a local `runServer` server as well.

### Phase 4 - Export (video)

`export/ExportSettings.java`, `ExportSession.java`, `FrameGrabber.java`, `FfmpegProcess.java`,
`ChunkBufferPool.java`, `mixin/client/export/*` (client render, game renderer, keyboard, window,
block buffer pool, chunk graph accessor), `ui/ExportScreen.java` reached from an Export button in the
replay browser or the editor (size, fps, range, format, bitrate, output, ffmpeg command and
arguments, cursor toggle), `export/EncodingPreset.java`, `ui/ExportFailedScreen.java`, and the cursor sprite
`assets/momenton/textures/gui/cursor.png`. See the Export section.

**Done when:** 30 seconds exports to 1080p60, plays in VLC, and matches a side-by-side OBS capture of
the same session (camera motion, HUD and screens). Run once under VulkanMod 0.6.3 to prove the
readback.

### Phase 5 - Game audio

`audio/LoopbackRenderer.java`, `mixin/client/audio/SoundEngineMixin.java`, `SoundSystemMixin.java`,
`SoundManagerAccessor.java`, `SoundSystemAccessor.java`, `FfmpegProcess.mux`. The OpenAL loopback
renders exactly the samples for each grabbed frame, muxed by ffmpeg (see Audio).

**Done when:** game audio is in sync at the end of a 30-second export.

### Phase 6 - Editor on vanilla Screens

`edit/Project.java`, `edit/Clip.java`, `edit/Timeline.java`, `edit/TimelineResolver.java`,
`edit/UndoStack.java`, `edit/HudToggles.java`, `ui/editor/EditorScreen.java`,
`ui/editor/EditSession.java`, `mixin/client/hud/InGameHudMixin.java`

`edit/` is pure data with JUnit tests (written in Wave 3). `Clip` is a `[startTick, endTick)` source
range; `Timeline` is immutable, and `split`, `trimStart`, `trimEnd`, `move` and `delete` each return a
new one. `TimelineResolver` maps timeline seconds to `(clipIndex, sourceTick, tickProgress)`, and a
time exactly on a cut belongs to the next clip; it is the one implementation used by preview and
export, and `exportRanges()` gives the clips in order. `UndoStack` stores previous timelines (an
edit that changes nothing is not pushed). `Project` saves the replay's file name and timeline as
`<replay>.momenton.json` beside the recording.

The timeline is an overlay on the playback view and export hides it. `EditorScreen` is a vanilla
`Screen` with no background dim: step `<` `>` one tick (pausing first), play/pause, split at the
playhead, delete, undo/redo (Ctrl+Z, Ctrl+Shift+Z, Ctrl+Y), a HUD panel, save (Ctrl+S, and on close),
and Export, which opens `ExportScreen` with the clip list. Clips are dragged to reorder, their edges
to trim, and a click seeks. `EditSession` holds the `UndoStack`, the HUD toggles and the current clip
index; it is opened by the browser's Edit button or toggled by O on any playing replay (it finds the
file through `ReplaySession.file()`), ticked from `MomentonClient`, and, until dismissed, puts its
screen back whenever no screen, a replayed inventory or a chat screen is up, so it reappears after a
loading screen. While playing it follows the edit: at
the last tick of a clip it moves to the next and, if that clip does not continue the source, seeks to
its `startTick - 1` so the next playback tick lands on its first tick; after the last clip it pauses.
Seeks stay warm because the world is reused in place (see Phase 2).

HUD toggles (`HudToggles`, ten elements, saved in the project) gate the per-element `InGameHud`
methods at HEAD, and the experience bar's `Bar` calls in `renderMainHud` with `@WrapWithCondition`.
They apply only while a replay plays with an edit session, so live play is never affected.

The edited sequence exports through the same `ExportSession` as one video (see Export, the clip list
and the settle at each cut).

**Done when:** you can split a replay into three clips, reorder them, trim two, undo it all, reload
from disk, and play the result back as continuous video that honours the edited order, with HUD
toggles taking effect immediately.

### Phase 7 - Mic commentary

`audio/MicRecorder.java`, `audio/WavWriter.java`, `audio/MicTrack.java`, `audio/MicPlayback.java`,
`ReplayMetadata.micStartTick/micSampleRate`, an unbound mic toggle key (see Audio, mic commentary).
Recording, storage and in-sync playback are written (Wave 5). The device picker, on/off setting and
level meter live in `ui/SettingsScreen` and `config/momenton.json`. Still to come: the mic as a
second audio track in export with per-track gain and mute.

**Done when:** an export carries game audio and commentary on separate tracks, both in sync.

### Phase 8 - Hardening

Run the whole pipeline under VulkanMod 0.6.3. Measure recording overhead (target under 1 ms per tick).
Handle disk full mid-recording (finalise, never delete), dimension change and server kick while
recording. Replays from another Minecraft version are greyed out in the browser with their version
(`ReplayReader.recordedInRunningVersion`, Wave 5). FFmpeg auto-download with a pinned
SHA-256 manifest, hardware encoders (`h264_nvenc`, `h264_amf`, `h264_qsv`) with `libx264` fallback,
and the rest of the multiplayer matrix.

---

## Parallel development

Work from Phase 3 on is done in waves of subagents. Each agent runs in its own git worktree and owns a
disjoint set of files; any agent may make additive edits to `momenton.mixins.json` and `en_us.json`,
which the coordinator merges. Agents follow the conventions (no comments in new code, tabs, Java 21,
no `org.lwjgl.opengl`, Yarn names checked with `javap` against the merged jar), commit with no
attribution trailer, and are done when `./gradlew build` and `checkReplay` (recordings passed as
absolute paths, since `run/` is gitignored) are green. Agents cannot run the game, so nothing they
write is ticked until the user has observed it.

After each wave the coordinator merges the branches in a fixed order (Docs last, so it records what
landed), runs `build` and `checkReplay`, and the user does one in-game pass with numbered steps. A
failure becomes a root-cause fix before the next wave starts.

**Contract stubs.** When agents in one wave depend on each other's classes, the coordinator first
commits stub classes with the fixed public signatures (bodies empty or throwing), then launches the
agents on top of that commit. Each agent fills in its own stubs and codes against the others', so
the branches compile alone and merge without signature conflicts. In Wave 3 the stubs were
`ExportSettings`, `ExportSession`, `FrameGrabber` and `FfmpegProcess` (commit `51b3fca`).
`ExportSettings` is the export contract: the export screen builds it and `ExportSession` consumes it,
so a new export option is a new record component first.

**Wave 0 (coordinator, serial):** `git init` with `run/` and `build/` ignored, a baseline commit, then
remove the imgui dependencies and commit.

**Wave 1 (4 agents):**

| Agent | Task | Owns |
|---|---|---|
| Docs | Rewrite `TASKS.md`, update `IMPLEMENTATION_PLAN.md` | `*.md` |
| Tools | `tools` source set, `checkReplay` task, `Verify` as JUnit, boundary-race check, delete `.class` files | `build.gradle`, `tools/`, `src/test/` |
| Playback fixes | Seek state merge, perspective restore (tasks 2, 4a) | `playback/*` |
| Recorder fixes | Boundary race, snapshot timing log, finalise on write failure, `Recorder.synthesise(Packet<?>)` (tasks 3, 4b) | `Recorder.java`, `ReplayWriter.java` |

**Wave 2 (4 agents, after Wave 1 is merged): Phase 3 fidelity**

| Agent | Task | Owns |
|---|---|---|
| Local prediction | Task 5 | new `record/LocalPrediction.java`, new `ClientWorld` and handler-flag mixins |
| Screens | Tasks 6, 7, 8 | `UiStateTracker`, `UiStateCodec`, `UiStateDriver`, new `record/ScreenTracker.java`, new `playback/ReplayScreenInput.java` |
| Cursor | Task 9 | `FrameTrack`, `FrameTrackWriter`, `Recorder.recordFrame`, `ReplayMetadata` (version bump), new `GameRenderer` mouse mixin |
| Body state | Tasks 10, 11 | `EntityPositionTracker`, `LocalPlayerWriter`/`Codec`, `LocalPlayerDriver`, `EntityMoveCodec` |

**Wave 3 (merged): export and the editor data model.** The coordinator committed the contract stubs
and the export screen first, then three agents:

| Agent | Task | Owns |
|---|---|---|
| Pipeline | `FfmpegProcess`, `FrameGrabber` | those two files, `FfmpegProcessTest` |
| Export loop | `ExportSession`, export mixins, metadata framebuffer size and GUI scale, cursor sprite | `export/` rest, `mixin/client/export/`, `Recorder` metadata |
| Edit model | `edit/*` pure data with JUnit | `edit/`, `src/test/.../edit/` |

**Wave 4 (merged 2026-10-09): three agents, no stubs.**

| Agent | Task | Owns |
|---|---|---|
| Game audio | Loopback render per grabbed frame, mux | `audio/LoopbackRenderer`, `mixin/client/audio/*`, `FfmpegProcess.mux`, audio hooks in `ExportSession` |
| Editor | Timeline overlay, Edit button, key O, HUD toggles saved in the project | `ui/editor/*`, `edit/HudToggles`, `edit/Project`, `mixin/client/hud/InGameHudMixin`, `MomentonKeybinds`, browser button |
| Seek | Reuse the `ClientWorld` on seeks | `playback/OfflineConnection`, `playback/ReplaySession` |

**Wave 5 (merged 2026-10-10).** The coordinator first committed the contract (`5705711`):
`ExportSettings` takes `clips`, `ffmpegCommand`, `ffmpegArgs` and `bitrate`. Then:

| Agent | Task | Owns |
|---|---|---|
| Edited export | Clip-list export with a settle at each cut, `currentTick` as the tick on screen, `ReplaySession.file()`, `EditSession` ticked from `MomentonClient`, editor reopens after a loading screen, `chunkUpdaters` wait and the 600-pass cap | `export/ExportSession`, `ClientWorldAccessor`, `playback/ReplaySession`, `ui/editor/*` |
| ffmpeg | ReplayMod-style presets, templates, discovery, `export.log`, failure screen | `export/EncodingPreset`, `export/FfmpegProcess`, `ui/ExportScreen`, `ui/ExportFailedScreen`, `FfmpegProcessTest` |
| Mic | Record, store and play back commentary | `audio/Mic*`, `audio/WavWriter`, `ReplayMetadata`, mic hooks in `Recorder`, `WavWriterTest` |
| Hardening (committed on `main`) | Cursor only on replay screens, version-mismatched replays greyed out, codec and `HudToggles` tests, `FrameTrack` in double | `ScreenCursorMixin`, `PovDriver`, `ReplayReader`, `ReplayBrowserScreen`, `src/test/.../format`, `HudTogglesTest` |
| Docs | Both state files, after the merge | `*.md` |

The coordinator then wired `ExportFailedScreen` into `ExportSession` failures and moved
`MicPlayback` onto `ReplaySession.file()` and the on-screen tick (`6bbb1be`).

**In-game fix rounds (2026-10-10, between waves).** Each user run produced single-agent fixes,
root-caused from the logs first: the export hang at the end (unflushed GPU fences, `5525484`), the
progress overlay and background finish, Quick Sync and timing logs, the attack dip
(`resetTicksSince`, `d396467`), the simulation distance 0 that froze corpses (`c0abdad`, `ecace64`),
the cancel hang (`107cc5b`) and the 4-thread off-render-thread copy (57 fps, observed).

**Wave 6 (merged 2026-10-10).** The coordinator first added `gameVolume` and `micVolume` to
`ExportSettings` (`7d0122a`). Then:

| Agent | Task | Owns |
|---|---|---|
| NV12 | GPU RGBA-to-NV12 conversion before readback, BT.709 tags, RGBA fallback, `%INPUT%` presets, flaky tests fixed | `FrameGrabber`, `ExportSession`, `EncodingPreset`, `FfmpegProcess` (not `mux`), `FrameFormat`, `ExportTimings`, `shaders/core/nv12.fsh` |
| Commentary | Mic as a second audio track or mixed in, per-clip alignment, volumes | `FfmpegProcess.mux`, `ExportFinisher`, `ExportScreen` |
| Info and settings | Mods and resource packs in metadata with browser warnings; mic settings screen, device picker, level meter | `ReplayMetadata`, `ReplayDependencies`, `Recorder.metadata`, `ReplayBrowserScreen`, `MicRecorder`, `MicMeter`, `config/*`, `SettingsScreen` |

Merging NV12 after Commentary broke one test that called the old `FfmpegProcess.start` (`59b9bd5`):
always rerun the full test suite after merging agents that touched the same class.

**Next:** the consolidated in-game pass in `TASKS.md`, the fixes it produces, then Phase 8
(VulkanMod run, recording overhead, disk full / kick / dimension change mid-recording, Iris clock,
ffmpeg auto-download, 4K60, README).
---

## Risks

| Risk | Mitigation |
|---|---|
| VulkanMod's `GpuDevice` misses a readback path | Readback uses only the path vanilla screenshots use (`copyTextureToBuffer` + `mapBuffer`); the Export gate runs once under VulkanMod 0.6.3 |
| VulkanMod cannot translate or run the NV12 shader | It is plain GLSL 330 in vanilla style (one `sampler2D`, no uniform blocks, vanilla `core/screenquad` vertex shader, RGBA8 target); a pipeline that does not compile falls back to RGBA readback, but a failure only at draw time is not caught; check once under VulkanMod |
| Exported frames have holes from async chunk builds | Export re-renders until `chunkUpdaters`, terrain, block buffer pool and occlusion graph are all settled before grabbing, capped at 600 passes |
| HUD is the wrong size at a different export resolution | GUI-scale rule: metadata stores the recording's window size and GUI scale, export picks the scale that keeps the HUD's relative size |
| Exported motion feels subtly wrong | `frames.bin` at render-frame rate plus a fixed per-frame `RenderTickCounter`; validated against a side-by-side OBS capture at the Export gate |
| Preview and export disagree | Prevented structurally: both are vanilla `GameRenderer.render` over the same playback, and one `TimelineResolver` |
| Preview scrubbing stutters across cuts | Keep the `ReplaySession` warm, re-seek only when the resolved clip changes, no loading screen on backward seeks |
| Client-side UI state missed, HUD looks wrong | The Phase 3 visual gate and the Export OBS comparison are the detectors; extend `UI_STATE` as gaps surface |
| Other mods' HUDs do not reproduce | Accepted limitation, documented |
| Replay desync from server-side or modded behaviour | Accept and log; snapshots bound how far drift propagates |
| Mixin breakage on Minecraft/mappings bump | `defaultRequire = 1` makes it a loud crash; re-run `genSources` and re-verify targets |
| Packet re-encode fails on registry-dependent codecs | Capture the `DynamicRegistryManager` from the live network handler at recording start |
| FFmpeg stdin pipe deadlock | Dedicated writer thread; always drain stderr |

---

## End-to-end verification

Per phase, the "Done when" line is the gate. Before calling the mod finished:

1. `./gradlew build` - compiles, remaps, runs `checkNoDirectOpenGl` and the JUnit tests (`Verify`,
   `ActionCodec` round-trip, `frames.bin` sampling at fractional times, `edit/`).
2. `./gradlew checkReplay -Preplay=<file>` - every offline check ALL PASS on the current recordings.
3. `./gradlew runClient` - record two minutes of singleplayer, then repeat on a multiplayer server
   (`runServer` first). Open a chest, move items, type in chat, switch hotbar slots during both.
4. **The product test:** record a session with OBS running simultaneously, export the same span from
   Momenton, and compare frame by frame. Camera motion, HUD and inventory screens should match.
5. Split into clips, reorder, trim, undo, reload the project, and export the edit.
6. Export 1080p60 and confirm in VLC: correct duration, no A/V drift at the end, both audio tracks.
7. Export the same edit at 4K60 to confirm resolution is genuinely a free parameter.
8. Repeat 3-7 with VulkanMod 0.6.3 installed.

---

## Lessons

Rules discovered while building. Each one cost a failed run or a wrong fix; do not re-learn them.

**Process**
- Put guards in place from the first build (`checkNoDirectOpenGl` moved from Phase 8 to Phase 0).
- Measure the recording, not the API. `tools/Effects` settled the attack question in twenty minutes
  after source reading had produced a wrong `ATTACK` action.
- A count your own loop bounded is not a measurement: 121 snapshot chunks was exactly the most a
  radius-5 scan could return. When a number matches the assumption exactly, check whether the code
  could have returned anything else.
- A check stricter than the thing it checks is worse than none (`tools/Setup` needed a one-second
  window after a respawn, not one tick).
- A filter rule is a claim about playback; check it against playback, and add a `tools/Setup` check
  in the same change. Dropping `PlayerPositionLookS2CPacket` survived two phases because nothing had
  teleported.
- Packet ids can be named offline: number the S2C `PacketType` registrations in
  `PlayStateFactories` from 0, including the interleaved `CommonPackets`, `CookiePackets` and
  `PingPackets` (139 ids in +build.6). Skipping them shifts every id above 23.
- No `contact` block in `fabric.mod.json`; there is no repository URL to point at.
- Agents that depend on each other in one wave build against committed contract stubs (see Parallel
  development); Wave 3's three branches merged without a signature conflict.

**Recording**
- State that existed before F9 is missing from a recording unless captured: the registry manager
  (`setup.bin`), the world (`WorldSnapshot`), the chunk-map centre (recentre first), the camera
  (`PovDriver`), and the inventory (snapshot `InventoryS2CPacket`). Each is checked by `tools/Setup`.
- `ClientConnection.channelRead0` also fires for the integrated server's connection; filter on
  `packet.getPacketType().side()`.
- Netty filters, encodes and queues; the client thread drains at end of tick and submits one ordered
  batch. `ReplayWriter.write` clamps negative deltas to zero.
- A chunk's index means `[startTick, endTick)`; a batch must not push an action outside it (now: leave
  `at+1` entries queued rather than capping them, see the boundary race).
- Chunk tick deltas are relative to the chunk start, so `ActionCodec.Reader` takes `startTick`.
- The zip is assembled at stop from a staging directory; a live zip cannot hold several growing entries.
- Setup packets are buffered on every connection; the client keeps only built registries, which do
  not round-trip.
- `FeaturesS2CPacket` arrives before `SelectKnownPacksS2CPacket`; any of Features, SelectKnownPacks or
  DynamicRegistries opens a configuration sequence. `SynchronizeTagsS2CPacket` counts as setup only
  inside one, because servers resend it during play.
- `GameRenderer.getFov` and `ChatHud.scrolledLines` are private; FOV is worth recording (70 to 80.5).
- A snapshot is an ordered list of packets: the recentre before chunks, `INITIAL_CHUNKS_COMING`
  present. Ask what the client must have been told first, and add the ordering check to `tools/Setup`.
- The client holds more chunks than it renders; scan `max(2, viewDistance) + 3`.
- Every change-gated tracker resets at a chunk boundary, or a seek resolves nothing.
- The tab list is world state too. `ClientPlayNetworkHandler.createEntity` drops a player spawn with
  no `PlayerListEntry` ("Server attempted to add player prior to sending player info") and
  `AbstractClientPlayerEntity.getSkin` falls back to Steve/Alex without one, so a snapshot without it
  lost every other player, Carpet bots and the recorder's own skin and cape. The snapshot sends it
  before the spawns; `PlayerListS2CPacket` has no entry-list constructor, so `entries` is set
  through `PlayerListS2CPacketAccessor`.
- Compare a snapshot against what the server sends a fresh client, not against what looks missing:
  `PlayerManager.onPlayerConnect`, `sendScoreboard`, `sendWorldInfo` and
  `EntityTrackerEntry.sendPackets` (javap) list it. Wave 8 found equipment, attributes, passengers,
  the recorded player's own tracked data (skin layers), scoreboard, boss bars, weather, border and
  cooldowns missing in one pass this way.
- Snapshot packets are built on the client thread and encoded on the writer thread: each must copy
  what it holds (checked: `TeamS2CPacket`, attributes, equipment stacks are copied).
- Recorded is not applied: `frames.bin` carried FOV and the sprint flag from the start and nothing
  read them until Wave 9 (sprint, speed, bow and ok_zoomer FOV were missing from every replay). When
  adding a recorded field, add its reader in the same change.
- Check the user's mod list before hooking vanilla internals: ScalableLux replaces the light engine,
  so a vanilla `LightStorage` hook applied cleanly and never fired.
- The replayed player must carry the recorded profile, not the viewer's: skins are looked up by UUID,
  and the dev username (so the UUID) changes on every launch.
- `frames.bin` and `ENTITY_MOVE` cross-check offline: in first person a frame stamped tick T matches
  `lerp(pose[T-2], pose[T-1])` (`tools/Motion`, offset 0).

**Playback**
- Never call `MinecraftClient.disconnect(Screen, boolean)` directly; it hangs on "Saving game". Use
  `disconnect(Text)`.
- A client tick handler can re-enter itself (disconnect pumps `render`, `onGameJoin` calls
  `setScreenAndRender`); `ReplaySession` guards with `disconnecting` and `advancing`.
- The client already ticks the playback connection via `ClientPlayerInteractionManager.tick`;
  `OfflineConnection` only drains outbound.
- Playback packets pass through the recording tap; the playback channel carries a `Momenton.PLAYBACK`
  attribute (on `Momenton`, so `record` does not depend on `playback`).
- A forward seek needs no snapshot; only a backward seek loads one.
- A seek after the first load must not apply the snapshot's join: rejoining reused the old player and
  loaded the snapshot's chunks into whatever world was current (Nether chunks in the Overworld).
  Clear the world in place, or respawn into the snapshot's dimension, and keep one connection.
- One meaning for a tick number everywhere: `currentTick()` is the tick on screen. When a seek left
  it equal to the target but a normal tick left it one past, export needed `target + 1`, the first
  tick after a seek showed twice, and the editor stepped wrong.
- Playback never needed a world, only a way in: the title-screen button via `ScreenEvents.AFTER_INIT`.
- "Is playback over" asks the connection, which closes first, not `client.world`, which goes second;
  `stop()` skips its own disconnect when the channel is already closed.
- A pause is two clocks: stop the tick and stop the tick progress. Set `MinecraftClient.paused`
  immediately after `render` assigns it, because `render` passes it to `RenderTickCounter.tick` two
  lines later; set at the head of `tick` it is overwritten and everything shakes. `PovDriver` holds
  the last frame's progress as well.
- Borrowing vanilla's pause flag freezes entities, block entities, particles, the HUD tick and the
  interaction manager in one move; fixing one animation at a time would not.
- A singleplayer join carries simulation distance 0: the integrated server only sends the real value
  (`max(2, options.simulationDistance)`) in a `SimulationDistanceS2CPacket` on its first tick, before
  any recording starts. With 0, `ClientWorld.shouldUpdatePostDeath` is false for every corpse outside
  the viewer's chunk, so `deathTime` stops while the renderer still draws `deathTime + tickProgress`:
  the corpse tilts forward within each tick and snaps back at every tick boundary. It was reported
  from an export, but the export clock is not involved; `tools/Deaths` lists the affected deaths.
  `OfflineConnection.applySnapshot` feeds that packet after a join that says 0.

**Driving the replayed player**
- Where a value is applied is as load-bearing as its value; most Phase 3 defects were a value applied
  at a different point in the frame or tick than vanilla applies it.
- Position at the HEAD of `ClientPlayerEntity.tick`; at end of tick the player slides with legs still.
- Rotation per frame from `frames.bin`, with `lastYaw` set too, so `getYaw(tickProgress)` is the
  recorded angle; at 20 Hz the first-person hand shakes.
- Never write `headYaw` at HEAD: `baseTick` sets `lastHeadYaw` during the tick and the head snaps.
  `lastX/Y/Z` and `lastYaw/Pitch` are safe; `lastHeadYaw` and `lastBodyYaw` are not.
- Drive animations by their start, not their frame number; vanilla advances the counters itself.
- An edge is not a start: `swingHand` restarts a running swing with `handSwinging` still set, so watch
  the counter drop too (9 of 46 starts were lost).
- Replay the method vanilla called, not one that moves the same number. An attack (mob or air) calls
  `resetTicksSince`, which restarts `ticksSinceLastAttack` and `ticksSinceHandEquipping`; the second
  is the held item's dip in `HeldItemRenderer.updateHeldItems`. Driving `resetTicksSinceLastAttack`
  got the crosshair right and every sword swing looked like a block hit. Which one ran is recorded as
  an event (`PlayerAttackCountersMixin`, `UI_STATE` `FLAG_RESETS`): a cooldown drop cannot tell, since
  a spear jab resets only the cooldown and an attack-speed change at an item switch drops it with no
  reset at all.
- `isSneaking()` on the client player reads `player.input`; feed a real `Input` with movement false.
- `noClip` is overwritten first thing in `PlayerEntity.tick`; use `setNoGravity(true)` and zero
  velocity.
- The camera is not the player: third-person front records `(yaw + 180, -pitch)`, and the perspective
  has three values, not two.
- Nausea and fire ticks go on at TAIL of the player tick (`ClientPlayerEntityTailMixin`):
  `tickNausea` copies `lastNauseaIntensity` from the current value during the tick, and `baseTick`
  extinguishes a client entity every tick, so a value set at HEAD is copied or cleared before it renders.
- A controlled vehicle's pose is set immediately before and after its own `entity.tick()` inside
  `ClientWorld.tickEntity` (`playback/ClientWorldMixin`), so vanilla's own vehicle movement cannot move
  it. The rider is not positioned while riding; `updatePassengerPosition` places it.

**Local prediction**
- The duplicate guard is a counter around `PacketApplyBatcher$Entry.apply` for client play listeners,
  not a flag per handler: any handler can reach the hooked sound and world-event paths.
- Not every local sound goes through `ClientWorld.playSound`. Eating and footsteps use the
  `ClientPlayerEntity.playSound` override; the mining hit sound is handed straight to `SoundManager.play`
  in `updateBlockBreakingProgress`. Each needs its own hook.

**Export**
- `RenderTickCounter.Constant` returns the same value from all three getters, so it cannot carry a
  tick progress and a frame delta at once. A small record implementing the interface needs no access
  widener.
- Do not write an export loop. Let vanilla `render` run and feed it: replace the `beginRenderTick`
  result with the replay's tick count, pass a fixed counter to `GameRenderer.render`, and grab after
  it. A pass that is not ready is just rendered again.
- "Terrain render complete" is not enough to grab: also wait for every block buffer builder to be
  back in the pool and for the occlusion graph update to finish, or a frame still has holes.
- Resize like vanilla `takePanorama` (`setFramebufferWidth/Height` + `onResolutionChanged`) and cancel
  window resize events, rather than a separate render target.
- A text colour with zero alpha draws nothing on 1.21.11; write colours as `0xFFRRGGBB` (the browser's
  title and empty message were invisible until Wave 3).
- `ClientWorld.chunkUpdaters` drains at most 10% per frame, so "terrain render complete" can be true
  while snapshot chunks are not yet scheduled. Wait for it to empty too, and cap the wait.
- A cut needs a settle: seek to `target - 1`, let at least the entity interpolation duration (3 client
  ticks) pass, seek again, stop one-shot sounds and clear particles, then one real tick to `target`.

**Audio**
- The loopback device needs no engine of its own: set a flag and call `reloadSounds()`, and vanilla
  re-runs `SoundEngine.init` through the mixin. Turn off `shouldReloadSounds` while exporting, or the
  device-change poll swaps the real device back in.
- With a loopback, time only advances when samples are rendered, so an asynchronous sound load can
  start a sound frames late; block on the load while exporting.
