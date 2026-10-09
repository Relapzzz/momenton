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

During export we build a `RenderTickCounter.Constant` per output frame carrying an exact fractional
tick progress (its constructor is package-private, so an access widener opens it). Entity motion,
item swing, view bobbing and hurt tilt then interpolate exactly as they do live. Rendering 60 fps out
of 20 TPS data is precisely what the game does every frame anyway.

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
                     recording window size and GUI scale
  setup.bin          the configuration-phase packets and the game join, replayed before tick 0
  chunk_0000.bin     length-prefixed actions: [varint type][varint tickDelta][payload]
  snapshot_0000.bin  full world state at that chunk boundary
  ...
  frames.bin         FIXED-STRIDE 56-byte records, one per rendered frame
  ui_0000.bin        tick-rate UI state actions
  audio/mic.wav      48 kHz PCM, first-sample tick recorded in metadata
  thumb.png          preview still for the replay browser
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
recentre, every loaded chunk nearest-first, a spawn plus tracked data per entity, and the player's
inventory (`InventoryS2CPacket` on sync id 0). Framing is `[varint length][packet]`, the same as
`setup.bin`. Leading with the join is what makes a snapshot a complete rebuild rather than a patch,
so seeking never has to undo anything: drop the connection, build a new one, apply setup and the
snapshot, replay forward.

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
and the snapshot stays consistent. The cost is a hitch on the client thread at every boundary (every
500 ticks), which has to be measured.

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
  `PlaySoundS2CPacket`, captured from `ClientWorld.playSound` where the source is the local player.
  Calls made from inside `ClientPlayNetworkHandler` handlers (`onPlaySound`, `onWorldEvent`,
  `onBlockBreakingProgress`) are excluded with a flag set around them, or every real sound would be
  recorded twice.
- **Screen contents:** `ClickSlotC2SPacket` carries the client's predicted result and the server only
  sends a correction when it disagrees, so item moves in a chest, crafting table or the player's
  inventory never appear clientbound. At the end of each tick, if `player.currentScreenHandler`'s
  stacks or cursor changed, write `InventoryS2CPacket(syncId, revision, stacks, cursor)`.

One recording-side mixin on `ClientWorld` covers the first three. Verify the targets against
`genSources` before writing it.

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

`export/ExportSession` takes over the frame loop. Per output frame at time `t`:

1. **Clock.** Call `client.tick()` until the replay reaches `floor(t * 20)`. Driving the real client
   tick also drives `GameRenderer.tick`, which eases the camera's eye height; a loop that skips it
   freezes `cameraY` and every crouch exports at the wrong height.
2. **Render** with a fixed tick progress `t * 20 - floor(t * 20)`, through a `RenderTickCounter.Constant`
   (access widener on its constructor).
3. **Wait for the chunk builder to be idle** before grabbing. Chunk meshes build asynchronously, so
   without the wait a frame after a seek, or wherever chunks stream in, has holes.
4. **Resolution.** Resize the main framebuffer, with a mixin overriding the `Window` framebuffer size,
   because vanilla world rendering assumes the main target.
5. **GUI scale.** Choose the GUI scale so the HUD keeps its recorded size relative to the frame.
   Metadata stores the recording's window size and GUI scale for this.
6. **Readback** (`FrameGrabber`). Prove the path with `ScreenshotRecorder.takeScreenshot` first, then
   optimise: a `GpuBuffer` with `USAGE_COPY_DST | USAGE_MAP_READ`, `copyTextureToBuffer`, then
   `mapBuffer`, rotating two or three buffers with `createFence()` so readback does not stall the loop.
7. **Encode** (`FfmpegProcess`). FFmpeg from PATH or a configured path, `libx264`, raw frames on stdin
   written from a dedicated thread, stderr always drained so the pipe never deadlocks.

```
ffmpeg -f rawvideo -pix_fmt bgra -s WxH -r <fps> -i pipe:0 \
       -i game.pcm -i mic.wav \
       -c:v libx264 -pix_fmt yuv420p \
       -filter_complex "[1:a][2:a]amix=inputs=2" out.mp4
```

No frame composition logic belongs in the export package. Export is the playback view on a fixed
clock plus a readback.

### Audio: why game sound is rendered at export

The world is re-simulated, so a waveform captured during play would not match your edits. At export we
mixin `SoundEngine.init(String deviceSpecifier, boolean directionalAudio)` to open an
`ALC_SOFT_loopback` device and drive `alcRenderSamplesSOFT` ourselves, requesting **exactly the sample
count matching each rendered frame's duration**. That gives sample-exact A/V sync no matter how slowly
the offline render runs, and it is how ReplayMod Audio Render works. The tick-stamped mic WAV is muxed
in as a second track.

Consequence to accept: the live preview has no mixed game audio - the engine makes its own sound
during playback, and the mic WAV is played back in sync as a preview-only convenience.

LWJGL **OpenAL** is fine to call directly - VulkanMod only replaces the graphics backend.

---

## The constraint that shapes the rendering: VulkanMod

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
  `getDynamicDeltaTicks()`, `getFixedDeltaTicks()`, `RenderTickCounter.Constant`, `.Dynamic`.
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

Three members needed accessors because they are private or package-private:

- `GameRenderer.getFov(Camera, float, boolean)` is **private** - `GameRendererInvoker`.
- `ChatHud.scrolledLines` is **private** - `ChatHudAccessor`.
- `RenderTickCounter.Constant(float)` has a **package-private constructor** - export opens it with an
  access widener.

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
| **ReplayMod** (github.com/ReplayMod/ReplayMod) | GPLv3 | The primary reference to lift from. Packet recording and filtering, replay playback over a fake connection, seeking, and especially its OpenAL loopback audio render. GPL obligations attach to distribution, so a private mod can borrow freely. |
| **VulkanMod** | LGPL-3.0 | Read its `GpuDevice` implementation to learn which Blaze3D paths it actually supports before relying on one. |
| **Minecraft** (via `./gradlew genSources`) | - | The authority for HUD and screen rendering order, and for every Yarn name above. |
| **Flashback** (github.com/Moulberry/Flashback) | **All rights reserved** | Read for understanding only. Its licence is `Do not redistribute. All rights reserved.`, granting no copying or modification rights; private use does not change that. Its architecture is already documented in this file; reimplement from that, and use ReplayMod when actual code is wanted. |

### Borrowed from ReplayMod

New code carries no comments, so borrowed code is tracked here instead: one line per Momenton file,
naming the ReplayMod source it came from, so the next session knows which parts are ours when
something breaks.

- (none yet)

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

**A snapshot is not free, so a seek only loads one when it has to.** Loading one means a join packet,
which means `MinecraftClient.joinWorld`, which means the viewer watches the terrain-loading screen.
Only a *backward* seek needs a snapshot, because packets cannot be un-applied. A forward seek is
playback run without waiting for ticks, over the world that is already loaded. `jump` rebuilds only
when the target is behind the playhead or more than one chunk's worth of ticks ahead. Removing the
screen from a backward seek needs playback to reuse the existing `ClientWorld` instead of rejoining;
that belongs to the editor phase, because the scrubber seeks constantly.

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
- **Animations** (hand swing, attack cooldown) are fed as *starts* - `swingHand`, and
  `resetTicksSinceLastAttack` when the recorded cooldown drops - never as recorded counter values.

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

`export/ExportSession.java`, `export/FrameGrabber.java`, `export/FfmpegProcess.java`, an access
widener for `RenderTickCounter.Constant`, a `Window` framebuffer-size mixin, and an export options
screen in the replay browser (whole recording, resolution, fps, output). See the Export section.

**Done when:** 30 seconds exports to 1080p60, plays in VLC, and matches a side-by-side OBS capture of
the same session (camera motion, HUD and screens). Run once under VulkanMod 0.6.3 to prove the
readback.

### Phase 5 - Game audio

`mixin/client/SoundEngineMixin.java`, `audio/LoopbackRenderer.java`. The OpenAL loopback renders
exactly the samples for each frame, muxed by ffmpeg.

**Done when:** game audio is in sync at the end of a 30-second export.

### Phase 6 - Editor on vanilla Screens

`edit/Project.java`, `edit/Clip.java`, `edit/Timeline.java`, `edit/TimelineResolver.java`,
`edit/UndoStack.java`, a timeline overlay screen, `mixin/client/hud/InGameHudMixin.java`

`edit/` is pure data with JUnit tests. `TimelineResolver` maps a timeline position to
`(clip, sourceReplayTick, tickProgress)` and is the one implementation used by preview and export.
The timeline is an overlay on the playback view (trim, split, reorder, undo) and export hides it.
Seeking across a cut is the expensive case: keep the `ReplaySession` warm and only re-seek when the
resolved clip changes. Backward seeks must no longer show the loading screen, so playback reuses the
existing `ClientWorld`. HUD toggles gate the per-element `InGameHud` methods. The edit exports through
the same `ExportSession`.

**Done when:** you can split a replay into three clips, reorder them, trim two, undo it all, reload
from disk, and play the result back as continuous video that honours the edited order, with HUD
toggles taking effect immediately.

### Phase 7 - Mic commentary

`audio/MicRecorder.java`, `audio/WavWriter.java`. `TargetDataLine` at 48 kHz on its own thread, the
first sample's tick in metadata, a device picker, and a second audio track in export.

**Done when:** an export carries game audio and commentary on separate tracks, both in sync.

### Phase 8 - Hardening

Run the whole pipeline under VulkanMod 0.6.3. Measure recording overhead (target under 1 ms per tick).
Handle disk full mid-recording (finalise, never delete), dimension change, server kick while
recording, and replays from a mismatched Minecraft version. FFmpeg auto-download with a pinned
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

**Wave 3 (3 agents, after Wave 2 is merged): export and the editor data model**

| Agent | Task | Owns |
|---|---|---|
| Pipeline | `FfmpegProcess`, `FrameGrabber` | new files only |
| Export loop | `ExportSession`, access widener, Window framebuffer-size mixin, metadata window size and GUI scale, export screen in the browser | the new export files, `ReplayBrowserScreen` |
| Edit model | `edit/*` pure data with JUnit | `edit/`, `src/test/` |

**Wave 4 (2 agents):** game audio loopback, and the editor timeline overlay with HUD toggles.
**Wave 5:** mic commentary, then hardening.

---

## Risks

| Risk | Mitigation |
|---|---|
| VulkanMod's `GpuDevice` misses a readback path | `ScreenshotRecorder` first; the Export gate runs once under VulkanMod 0.6.3 |
| Exported frames have holes from async chunk builds | Export waits for the chunk builder to be idle before grabbing each frame |
| HUD is the wrong size at a different export resolution | GUI-scale rule: metadata stores the recording's window size and GUI scale, export picks the scale that keeps the HUD's relative size |
| Exported motion feels subtly wrong | `frames.bin` at render-frame rate plus `RenderTickCounter.Constant`; validated against a side-by-side OBS capture at the Export gate |
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
- A forward seek needs no snapshot; only a backward seek rebuilds.
- Playback never needed a world, only a way in: the title-screen button via `ScreenEvents.AFTER_INIT`.
- "Is playback over" asks the connection, which closes first, not `client.world`, which goes second;
  `stop()` skips its own disconnect when the channel is already closed.
- A pause is two clocks: stop the tick and stop the tick progress. Set `MinecraftClient.paused`
  immediately after `render` assigns it, because `render` passes it to `RenderTickCounter.tick` two
  lines later; set at the head of `tick` it is overwritten and everything shakes. `PovDriver` holds
  the last frame's progress as well.
- Borrowing vanilla's pause flag freezes entities, block entities, particles, the HUD tick and the
  interaction manager in one move; fixing one animation at a time would not.

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
- `isSneaking()` on the client player reads `player.input`; feed a real `Input` with movement false.
- `noClip` is overwritten first thing in `PlayerEntity.tick`; use `setNoGravity(true)` and zero
  velocity.
- The camera is not the player: third-person front records `(yaw + 180, -pitch)`, and the perspective
  has three values, not two.
