# Momenton - Implementation Plan

**This is the durable design document.** It explains what Momenton is, why it is built this way, and
what each phase must deliver. It changes only when a design decision changes.

For what is done and what to do next, see `TASKS.md`. Read that first if you are picking this up
mid-build; come back here for the reasoning behind whatever you are about to write.

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
| Camera | **Exact recorded POV only.** No free camera, no keyframes, no third person. |
| GUI fidelity | HUD **and** container screens (inventory, chests, crafting), re-rendered at export. |
| Overlays | **None in v1.** No text, no images, no PiP. |
| Editing | Trim, split, reorder, undo/redo. Nothing else. |
| Preview | WYSIWYG. Preview and export share one render path, so they cannot diverge. |
| Editor UI | ImGui, rendered through a **custom Blaze3D backend** we write. |
| Audio | Mic recorded live to WAV; game audio rendered at export via OpenAL loopback. |
| Encoder | Auto-downloaded FFmpeg binary, raw frames piped to stdin. |
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
tick progress. Entity motion, item swing, view bobbing and hurt tilt then interpolate exactly as they
do live. Rendering 60 fps out of 20 TPS data is precisely what the game does every frame anyway.

### 2. Fidelity: the local camera must be recorded at frame rate

This is the one genuine gap in a pure packet recorder. Mouse-look happens at frame rate, but the
network only carries player rotation at 20 Hz. Interpolating from packets produces motion that is
smooth but *not yours* - subtly floaty, and instantly recognisable as wrong.

So Momenton records the local camera pose **every rendered frame** into a fixed-stride binary track.
At 56 bytes per frame, 200 fps for ten minutes is under 7 MB before compression - negligible beside
the packet stream, and fixed-stride records make sampling at any output frame rate an O(1) index
lookup rather than a scan.

The same track carries mouse cursor position, for the same reason: cursor movement inside an
inventory screen is a frame-rate signal.

### 3. WYSIWYG preview: one render path, two consumers

A single `FrameRenderer` that, given `(replayTick, tickProgress, cameraPose, uiState, width, height)`,
composes one finished frame - world, then HUD, then any open screen - into a target `Framebuffer`.

- **Preview** calls it once per display frame, into a panel-sized framebuffer.
- **Export** calls it once per output frame, into an offscreen framebuffer at export resolution, and
  reads the pixels back.

Nothing else composes frames. The preview cannot drift from the export because it is not an
approximation of the export - it is the same function with different arguments. There is therefore no
class of bug where the render looked fine but exported wrong.

Two deliberate differences, safe because they are parameters rather than separate code paths: the
preview renders at panel resolution (with a render-scale setting so scrubbing stays responsive) and is
letterboxed to the export aspect ratio so framing is honest.

---

## Architecture

Packages under `com.relapzz.momenton`. Dependencies run strictly downward - `format` knows nothing
about `ui`, `record` knows nothing about `export`.

```
record/    packet capture + frame track + UI state track   (live gameplay only)
format/    ReplayFile container: chunks, snapshots, tracks, metadata
playback/  ReplayFile -> re-simulated ClientWorld          (drives preview and export)
hud/       HUD + container screen reproduction
render/    FrameRenderer: composes world + HUD + screens into a Framebuffer
           THE single frame-composition path, shared by preview and export
edit/      Project / Timeline / Clip / UndoStack           (pure data, no rendering)
audio/     mic capture (live) + OpenAL loopback render (export)
export/    offline render loop -> frame readback -> FFmpeg subprocess
ui/        ImGui-on-Blaze3D backend + editor panels; vanilla Screens everywhere else
config/    settings + keybinds
```

`Momenton.MOD_ID` and `Momenton.id(path)` are the intended `Identifier` helpers - use them rather than
`Identifier.of("momenton", ...)` literals.

### Replay file format

A zip, so each part stays independently seekable and inspectable:

```
recording.momenton (zip)
  metadata.json      version, MC/mod version, duration, world name, chunk index, dimension changes
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
float  mouseX, mouseY  8
int    flags           4   (screen open, chat open, hand, sprinting, ...)
                    = 56 bytes
```

Chunk action types: `PACKET`, `ENTITY_MOVE` (delta-compressed), `LOCAL_PLAYER` (health, hunger,
hotbar, held item), `DIMENSION_CHANGE`, plus `UI_STATE` in the `ui_NNNN.bin` entries - one framing
serves both tracks. Compression comes from the zip entries themselves; do not hand-roll it.

**`ENTITY_MOVE` includes the local player**, and is the only record of where the recorded player went:
a server tells a client about every entity except that client's own, so the packet stream carries
everyone else's movement and none of the player's. See `format/EntityMoveCodec` for the layout, which
both the writer and playback read from.

**Tick deltas are relative to the chunk's first tick, not to tick zero.** That is what makes a chunk
independently readable, and it means a reader has to be seeded with `startTick` from the chunk index.

**A chunk boundary resets every tracker**, for the same reason. Delta compression and change gating
are both relative to the writer's running state, which spans the whole recording, while a seek loads
exactly one chunk: without the reset, a seek into the middle can resolve no delta and never learns a
value last written in an earlier chunk. So the first action of a chunk is always absolute and always
complete.

**The zip is assembled at stop, not written live.** `ZipOutputStream` holds one entry open at a time
while the chunks, the UI tracks and `frames.bin` all grow together, so `ReplayWriter` streams loose
files into a `.<name>.staging/` directory and zips them when recording ends. This is a better fit for
the requirement it replaces - a crash leaves every completed part on disk rather than a truncated
archive.

Snapshots exist so seeking is "load nearest snapshot, replay forward" rather than "replay from tick
zero". One chunk per ~500 ticks (25 s), and one snapshot per chunk - including chunk zero, because
recording begins mid-session and without it there is no initial world to replay forward from.

**A snapshot is a list of packets, not a bespoke format:** a `GameJoinS2CPacket` rebuilt from the
live world, then `INITIAL_CHUNKS_COMING`, the world time, the player's position, the chunk-map
recentre, every loaded chunk nearest-first, and a spawn plus tracked data per entity. Framing is
`[varint length][packet]`, the same as `setup.bin`. Leading with the join is what makes a snapshot a
complete rebuild rather than a patch, so seeking never has to undo anything: drop the connection,
build a new one, apply setup and the snapshot, replay forward.

The playback side needed no code for this at all - same codec, same vanilla handlers. Two orderings
inside it are load-bearing and invisible in the file:

- **The chunk-map recentre must precede the chunks.** `ClientChunkManager` keeps a fixed-size map
  centred wherever `ChunkRenderDistanceCenterS2CPacket` last put it, and silently discards anything
  outside it. At join that centre is 0,0.
- **`INITIAL_CHUNKS_COMING` must be present at all.** It is the only thing that moves
  `ClientChunkLoadProgress` out of `Start`, a state with no timeout, so without it the loading screen
  never lifts however much world data arrives.

A snapshot describes the world *after* its own tick, so replaying forward from it starts at the next
tick.

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
but vanilla singleplayer).

The cost is a few MB of packets retained per connection for the life of the session. That is the
price of F9 being retroactive at all.

`setup.bin` is a header, not a track: a plain length-prefixed packet list with no ticks, since none of
it belongs to a tick. Note that it does **not** replace `snapshot_0000.bin`. Setup builds an *empty*
world of the right shape; the snapshot fills it with the chunks and entities that were already loaded
when F9 was pressed and are therefore never re-sent.

### Packet capture: decode, filter, re-encode

Capture the already-decoded `Packet<?>` in
`ClientConnection.channelRead0(ChannelHandlerContext, Packet<?>)` rather than the raw `ByteBuf`
earlier in the netty pipeline, because we need to *inspect* traffic to filter it. Re-encoding needs a
`RegistryByteBuf` carrying the right `DynamicRegistryManager`, captured from the live
`ClientPlayNetworkHandler` when recording starts. Filter rules, learned from Flashback:

- Expand `BundleS2CPacket` into sub-packets so tick boundaries stay meaningful.
- Rewrite signed player-chat as system chat - chat signatures are session-bound and will not validate
  on replay.
- Drop custom-payload packets for mods that are not present, so a replay does not hard-fail.
- Skip local-player position packets; the local player is reconstructed from `LOCAL_PLAYER` actions
  and `frames.bin`.

### HUD and GUI: what is free, and what must be recorded

`InGameHud.render(DrawContext, RenderTickCounter)` is public, so export calls the real vanilla
renderer. Most HUD state reconstructs for free from the replayed player entity and clientbound
packets:

**Free** - health, hunger, armour, air, XP, status effects, chat history (`GameMessageS2CPacket`),
scoreboard, boss bars, titles and action bar, inventory and container contents (`InventoryS2CPacket`,
`ScreenHandlerSlotUpdateS2CPacket`), the carried cursor stack (slot -1), vignette, portal and nausea
overlays.

**Must be recorded** - client-side, never crosses the wire, so it needs a `UI_STATE` track:

- selected hotbar slot (`UpdateSelectedSlotC2SPacket` is client to server, absent from a clientbound
  recording)
- whether the player's own inventory is open - pressing E produces no packet at all
- hand swing progress and active hand for the local player
- attack-cooldown timing, which drives the crosshair attack indicator
- chat open/closed and scroll offset
- mouse cursor position - carried in `frames.bin`, because it is a frame-rate signal

**Container screens reproduce almost for free**, which is the payoff of feeding packets to real
vanilla handlers: replaying `OpenScreenS2CPacket` through `ClientPlayNetworkHandler` constructs the
screen and its `ScreenHandler` exactly as it did live, and slot-update packets fill it. Only the
player's own inventory needs synthesising, via `client.setScreen(new InventoryScreen(player))` when
the `UI_STATE` track says it was open.

Export renders in vanilla order per frame: world, then `InGameHud.render(...)`, then if a screen is
open `screen.render(DrawContext, mouseX, mouseY, deltaTicks)` with the recorded cursor position.

Because this is re-rendered rather than baked, the editor gets HUD element toggles essentially for
free. That is the main creative advantage over a real screen recorder and is worth exposing.

*Scope boundary:* pause menu, settings screens, advancements, death screen and the F3 debug overlay
are **not** reproduced.

*Known limitation:* other mods' custom HUD elements render only if those mods are installed at export
time and their state is reconstructible from packets. Client-only HUD mods holding private state will
not reproduce. Lunar Client Rewind has the same limitation.

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
task, which `check` depends on.

Where this bites hardest is ImGui: imgui-java ships `ImGuiImplGl3`, built on LWJGL GL33. It is
excluded from the build; we render ImGui draw lists through Blaze3D instead. That is the
highest-risk component in this plan and is scheduled as a standalone spike with an explicit fallback
(Phase 4).

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
  `GameRenderer.tick()`, so a crouch slides 1.62 → 1.445 → 1.357 → … over about five ticks rather
  than snapping to 1.27. It is not on the entity and it is not per frame. **Phase 7 has to account for
  this:** an offline render loop that drives frames without also driving `GameRenderer.tick` will
  freeze `cameraY`, and every crouch in the export will be at the wrong height. Momenton's playback
  does not care only because `CameraMixin` overwrites the camera position outright from `frames.bin`,
  which already has the eased value baked in.

Three members needed accessors because they are private or package-private:

- `GameRenderer.getFov(Camera, float, boolean)` is **private** - `GameRendererInvoker`.
- `ChatHud.scrolledLines` is **private** - `ChatHudAccessor`.
- `RenderTickCounter.Constant(float)` has a **package-private constructor**. Phases 3 and 7 both build
  one per output frame, so they will need an access widener or another accessor. Nothing in Phase 1
  touches it.

Also note `WorldRenderContext` in fabric-rendering-v1 16.2.10 no longer exposes `camera()` or
`tickCounter()`; the frame track hooks `GameRenderer.render` at TAIL and reads
`gameRenderer.getCamera()` instead.

Yarn names drift between mapping builds. Run `./gradlew genSources` and verify against the decompiled
output before writing any mixin. Do not trust this list after a mappings bump.

---

## Source reuse: what we borrow, and from where

This mod is private and will not be distributed, which widens what is usable - but not without limit.
Do not reinvent anything on this list.

| Source | Licence | What to take |
|---|---|---|
| **ReplayMod** (github.com/ReplayMod/ReplayMod) | GPLv3 | The primary reference to lift from. Packet recording and filtering, replay playback over a fake connection, seeking, and especially its OpenAL loopback audio render. GPL obligations attach to distribution, so a private mod can borrow freely. |
| **imgui-java** | Apache 2.0 | The binding itself. Read its `ImGuiImplGl3` as a *reference* for what a backend must do, then write the Blaze3D equivalent - do not use that class. |
| **VulkanMod** | LGPL-3.0 | Read its `GpuDevice` implementation to learn which Blaze3D paths it actually supports before relying on one. |
| **Minecraft** (via `./gradlew genSources`) | - | The authority for HUD and screen rendering order, and for every Yarn name above. |
| **Flashback** (github.com/Moulberry/Flashback) | **All rights reserved** | Read for understanding only. Its licence is `Do not redistribute. All rights reserved.`, granting no copying or modification rights; private use does not change that. Its architecture is already documented in this file; reimplement from that, and use ReplayMod when actual code is wanted. |

When code is taken from ReplayMod, note the source file in a comment. Not for compliance - so the
next session knows which parts are borrowed and which are ours when something breaks.

---

## Phases

Each phase ends in something runnable. Do not start a phase before the previous one demonstrably
works. Granular checkboxes live in `TASKS.md`.

### Phase 0 - Foundations

Strip the Fabric template, add the client entrypoint and mixin block, wire imgui-java as jar-in-jar,
and put the no-OpenGL guard in place.

**Done when:** `./gradlew build` produces a jar with the imgui natives nested.

### Phase 1 - Recording

`record/Recorder.java`, `record/FrameTrackWriter.java`, `record/UiStateTracker.java`,
`record/EntityPositionTracker.java`, `record/LocalPlayerWriter.java`, `format/ReplayWriter.java`,
`format/ReplayMetadata.java`, `format/ActionCodec.java`, `mixin/client/ClientConnectionMixin.java`

Mixin `channelRead0` at HEAD, forwarding to `Recorder.onPacket`. Keep the mixin body to a single
static call. `Recorder` runs IDLE to RECORDING to FINALISING and hands packets to a single-threaded
writer executor - never do IO on the netty thread. `FrameTrackWriter` appends one 56-byte record per
rendered frame; keep it allocation-free, writing into a reused `ByteBuffer` flushed in blocks.
`ReplayWriter` rolls a chunk every 500 ticks and writes zip entries incrementally so a crash loses at
most one chunk.

**Done when:** a minute of survival play produces a `.momenton` zip whose `metadata.json` reports a
plausible tick count, whose chunks are non-empty, and whose `frames.bin` size equals
`56 * framesRendered`.

### Phase 2 - Playback

`playback/ReplaySession.java`, `playback/OfflineConnection.java`, `playback/PovDriver.java`,
`record/WorldSnapshot.java`, `mixin/client/playback/*.java`

Built without the planned `Seeker` and `SnapshotIO` classes. Seeking turned out to be four lines
inside `ReplaySession` - load the snapshot covering the target, replay forward without waiting for
ticks - because loading a snapshot is already what starting playback does. A separate class would
have needed a copy of the session's cursor state to say the same thing. `SnapshotIO` likewise
dissolved: writing is `record/WorldSnapshot` producing packets, and reading is the packet path that
already existed.

**A snapshot is not free, so a seek only loads one when it has to.** Loading one means a join packet,
which means `MinecraftClient.joinWorld`, which means the viewer watches the terrain-loading screen
for a moment - visible on every seek, and the first thing anyone notices. But only a *backward* seek
actually needs a snapshot, because packets cannot be un-applied. A forward seek is playback run
without waiting for ticks, over the world that is already loaded, and it costs nothing to look at.
`ReplaySession.jump` therefore rebuilds only when the target is behind the playhead, or more than one
chunk's worth of ticks ahead - past that, replaying costs more than rebuilding, since a rebuild never
replays more than one chunk. Removing the screen from a backward seek needs playback to reuse the
existing `ClientWorld` instead of rejoining, which is a much larger change and is not Phase 2's.

**A dimension change is a second join in the middle of the stream, and it needs the same three
things.** `PlayerRespawnS2CPacket` builds a new `ClientWorld`; after it the client needs a player
position, a chunk-map recentre and `INITIAL_CHUNKS_COMING`, exactly as a snapshot does, and it gets
them from the recorded stream rather than from a snapshot. The player position is the one that is
easy to lose - `ClientChunkLoadProgress.LoadChunks.isReady` waits on
`worldRenderer.isRenderingReady(player.getBlockPos())`, so a player left at its old coordinates holds
the terrain screen for its full 30-second timeout while playback runs on invisibly behind it.
`tools/Setup` checks all three per dimension change.

**Playback needs no world, so the title screen is a valid entry point.** `ReplaySession.open` builds
its own world from the snapshot and only disconnects first if one already exists. The keybind cannot
reach that from the menus - `KeyBinding.wasPressed` never fires while a screen has focus - so
`ui/TitleScreenEntry` adds a button through `ScreenEvents.AFTER_INIT` rather than a mixin. The editor
in Phase 4 hangs off the same browser, which is what makes "open the game, open a recording, export"
a path that never joins a world.

`OfflineConnection` builds a `ClientConnection` over a netty `EmbeddedChannel` with a real
`ClientPlayNetworkHandler` attached, so vanilla handlers do all world reconstruction. Snapshot capture
follows Flashback's threading: generate chunk packets on a `ForkJoinPool` sorted by distance, but
compute light **on the main thread** to avoid synchronisation bugs.

Phase 2 starts with a **recording-side** change, because Phase 1 recordings are not replayable on
their own: the recorder has to buffer the session-setup packets from connect and write `setup.bin`,
and `SnapshotIO` has to write a snapshot at recording start. See "`setup.bin`, and why recording
mid-session needs it" above. Recordings made before that change can be inspected but not played.

**Done when:** a replay plays back at 1x with entities moving correctly and the camera matching what
you saw, and seeking to an arbitrary tick lands in under two seconds.

### Phase 3 - HUD and GUI reproduction

`hud/HudReproducer.java`, `hud/ScreenReproducer.java`, `hud/HudToggles.java`,
`render/FrameRenderer.java`, `mixin/client/hud/InGameHudMixin.java`

`FrameRenderer` is born here - get its signature right while it has exactly one caller. Retrofitting a
shared path later is how preview and export drift apart.

**The subject of this phase is client-side prediction, not just the HUD.** A packet recorder replays
what the *server* said, and the live client had already drawn a great deal the server never sent, or
sent a tick late. Two observed cases set the shape: the Nether portal warp, which the client computes
from `portalManager.isInPortal()` and which is therefore never true for a replayed player standing
still; and the fire overlay on entering lava, which the live client set locally and the replay waits
for `SET_ENTITY_DATA` to deliver. Neither is a HUD element. When something looks a beat late or does
not happen at all, ask whether the live client predicted it, and if so record the value rather than
trying to recompute it - the inputs to the prediction are exactly what a replay does not have.

Phase 2 leaves the replayed player standing where the snapshot put it while the camera walks away
from it, because `PovDriver` moves only the camera. Terrain proved not to care - `BuiltChunkStorage`
is recentred from `camera.getCameraPos()` and the chunk map from the recorded
`SET_CHUNK_CACHE_CENTER` packets, neither of which reads the player - but everything drawn *about*
the player does, and so does anything vanilla measures from `client.player`. Moving it is the first
Phase 3 task, and `frames.bin` is not the source: it holds the **camera** pose, which is the player's
eye only in first person. The player's own position belongs to the `ENTITY_MOVE` track, where it had
to be added - the recorder had always skipped the local player.

**Where a driven value is applied is part of the design, not an implementation detail.** The first
playthrough of this phase produced three separate visual defects and all three were the same mistake:
a value the live client updated at one point in the frame or tick, rebuilt at another. So:

- **Position** goes on at the head of `ClientPlayerEntity.tick`, because `ClientWorld.tickEntity` has
  just called `resetPosition()` and vanilla is about to derive body yaw and the limb animation from
  `getX() - lastX`. Setting it there makes that difference the recorded movement; setting it at end of
  tick renders the player in the right place with its legs still.
- **Rotation** goes on once per *frame*, from `frames.bin`, because vanilla turns the local player
  from `Mouse` once per frame and the recorded camera *is* `player.getYaw(tickProgress)`. Rebuild it
  at 20 Hz and everything derived from it - the first-person hand sway most visibly - steps against a
  camera that moves smoothly.
- **Head yaw** goes on nowhere at all. `PlayerEntity.tickMovement` derives it from the look direction,
  and `LivingEntity.baseTick` captures `lastHeadYaw` *during* the tick, so writing it at the head
  collapses the renderer's interpolation and the head snaps once per tick.
- **Sneak** goes back through `player.input`, because `ClientPlayerEntity.isSneaking()` is an override
  that reads nothing else.

**A hit is entirely the server's, and 1.21.11 is where that became true.** It is tempting to assume
the attacker's own sweep arc, crit particles, attack sound and knockback come from
`PlayerEntity.attack` being run *on the client* by `ClientPlayerInteractionManager.attackEntity`, and
that a packet recording of your own session therefore misses all of it. On this version it does not.
`attack` runs `bl5 = target.sidedDamage(...)`, which on a `ClientWorld` is `Entity.clientDamage` -
overridden by eight classes, none of them `LivingEntity` - so against any mob it returns false and the
whole effects block is skipped: no knockback, no sweep, no particles, no sounds. What is left,
`playAttackSound`, calls `ClientWorld.playSound(source, ...)`, which plays *only* when
`source == client.player`, and the caller passes `null`. So the local call is silent and invisible,
and every visible part of a hit arrives as a packet: `LEVEL_PARTICLES` for the sweep arc and the damage
indicator, `ANIMATE` with animation id 4 for crit particles, `DAMAGE_EVENT` for the flash,
`SET_ENTITY_MOTION` for the knockback, `SOUND` for all four attack sounds. All of it is already in the
recording. An `ATTACK` action naming the target was built on the older reading and removed once the
recording was measured; do not rebuild it without first checking `clientDamage` on the target class.

The general shape it was meant to solve is still real, though, and **block interaction is where it
actually bites.** `ServerWorld.syncWorldEvent(source, ...)` ends in
`PlayerManager.sendToAround(player, ...)`, whose loop is `if (serverPlayerEntity != player)` - so the
breaker is the one player who does *not* receive `BLOCK_BROKEN`. They get it locally instead, from
`ClientPlayerInteractionManager.breakBlock` calling `Block.onBreak` calling `spawnBreakParticles`
calling `ClientWorld.syncWorldEvent`, which runs the event handler directly. The same asymmetry covers
the crack overlay (`world.setBlockBreakingInfo(player.getId(), ...)`, local for your own id), the
mining hit sound every four ticks (straight to `SoundManager`), and the block *place* sound
(`BlockItem.place` calls `world.playSound(player, ...)`, which on a `ClientWorld` plays precisely
*because* the source is the local player, and on the server excludes them).

**The cheap way to carry all of it is to synthesise the packet the server sent to everyone else.**
`BLOCK_BROKEN` is a `WorldEventS2CPacket(2001, pos, rawStateId, false)`; the crack overlay is a
`BlockDestructionS2CPacket`; both sounds are `PlaySoundS2CPacket`. Written as ordinary `PACKET`
actions they need no new action type, no codec, no driver and no playback code at all - the same
argument that made the world snapshot a sequence of packets rather than a format. Prefer that to a new
action wherever the missing thing has a packet shape.

The test for whether an action is needed at all is not "does the client run this code" but "does the
client run it *and* produce something the wire does not carry" - and then, "is what it produces
already a packet".

**`frames.bin` holds a camera pose, and the camera is not the player.** In third-person front,
`Camera.update` sets the camera to `(yaw + 180, -pitch)`, so the recorded rotation for those frames is
the player's own rotation inverted; `FLAG_FRONT_VIEW` marks them and playback undoes it. The
perspective is likewise a recorded fact with three values, not two - collapsing front onto back makes
a replay of a front-view recording show the character from behind.

**A snapshot's radius is the client's chunk map radius, not its render distance.** A server sends out
to `viewDistance + 1` and `ClientChunkManager.getChunkMapRadius` keeps `max(2, viewDistance) + 3`, so
scanning the render distance leaves an outer ring in no snapshot at all - and nothing re-sends it,
because the server sent it before F9. The replay then keeps a permanent hole at the edge of view.
Unloaded chunks come back null, so the wider scan only captures what is really there.

**A recording can never contain more world than the client was sent**, so recording at a high render
distance is a capture-time decision, and it costs snapshot size quadratically.

The general rule for this phase: recompute nothing vanilla can recompute for itself, give vanilla the
input it would have had, and apply it where vanilla would have. A `last*` field assigned during the
tick rather than by `resetPosition` will collapse if its partner is written at HEAD - that is the
specific trap, and `lastHeadYaw` and `lastBodyYaw` are the two fields it applies to.

**Done when:** playback of a session that included opening a chest, moving items and typing in chat
looks the same as an OBS capture of that session.

### Phase 4 - ImGui on Blaze3D (highest risk - spike first)

`ui/imgui/ImGuiBlaze3DRenderer.java`, `ui/imgui/ImGuiBootstrap.java`,
`ui/imgui/ImGuiInputBridge.java`, `ui/EditorScreen.java`

Do this as a **throwaway spike first**: one ImGui demo window over the game, on both the default
backend and VulkanMod. Only once that works should it become real code. If the spike fails, fall back
to a custom widget toolkit on `GuiGraphics`. That decision point belongs here, not after the editor is
half-built.

Input bridges from a `Screen` subclass's `keyPressed` / `charTyped` / `mouseClicked` / `mouseScrolled`
into ImGui's IO. Do **not** install raw GLFW callbacks - that fights Minecraft's input handling and
breaks keybind capture.

**Done when:** the editor opens over a paused replay, panels dock and resize, and it renders
identically with and without VulkanMod installed.

### Phase 5 - Timeline, editing and live preview

`edit/Project.java`, `edit/Timeline.java`, `edit/Clip.java`, `edit/TimelineResolver.java`,
`edit/UndoStack.java`, `ui/panel/TimelinePanel.java`, `ui/panel/PreviewPanel.java`,
`ui/panel/InspectorPanel.java`

`TimelineResolver` maps a timeline position to `(clip, sourceReplayTick, tickProgress)`. Like
`FrameRenderer` it has exactly one implementation used by both preview and export - the second half of
the WYSIWYG guarantee. Seeking across a cut is the expensive case: keep the `ReplaySession` warm and
only re-seek when the resolved clip actually changes.

**Done when:** you can split a replay into three clips, reorder them, trim two, undo it all, reload
from disk, and play the result back in the preview panel as continuous video that honours the edited
order, with HUD toggles taking effect immediately.

### Phase 6 - Audio

`audio/MicRecorder.java`, `audio/LoopbackRenderer.java`, `audio/WavWriter.java`,
`mixin/client/SoundEngineMixin.java`

**Done when:** an exported clip carries clean game audio in sync with the video, plus commentary on a
separate track.

### Phase 7 - Export

`export/ExportJob.java`, `export/FrameGrabber.java`, `export/FfmpegProcess.java`,
`export/FfmpegProvisioner.java`, `ui/panel/ExportDialog.java`

`FrameGrabber` allocates a `GpuBuffer` with `USAGE_COPY_DST | USAGE_MAP_READ`, calls
`copyTextureToBuffer`, then `mapBuffer` to read BGRA, rotating two or three buffers with
`createFence()` so readback does not stall the loop. Prove the path first with the existing
`ScreenshotRecorder.takeScreenshot` before optimising.

The offline render loop calls **the same `FrameRenderer` the preview uses**. No frame composition
logic belongs in the export file; if something looks wrong only in the export, the bug is in the loop
or the readback, never in the drawing.

```
ffmpeg -f rawvideo -pix_fmt bgra -s WxH -r <fps> -i pipe:0 \
       -i game.pcm -i mic.wav \
       -c:v h264_nvenc -preset p5 -cq 20 -pix_fmt yuv420p \
       -filter_complex "[1:a][2:a]amix=inputs=2" out.mp4
```

`FfmpegProcess` pumps stdin on a dedicated thread and drains stderr so the pipe never deadlocks.

**Done when:** a 30-second edit exports to a 1080p60 MP4 that plays correctly in VLC with both audio
tracks in sync and the HUD present.

### Phase 8 - Hardening

Run the whole pipeline under VulkanMod 0.6.3. Measure recording overhead (target under 1 ms per tick).
Handle disk full mid-recording, dimension change, server kick while recording, and replays from a
mismatched Minecraft version.

---

## Risks

| Risk | Mitigation |
|---|---|
| ImGui-on-Blaze3D backend does not work out | Spike standalone in Phase 4 before building panels on it; fall back to a `GuiGraphics` widget toolkit |
| VulkanMod's `GpuDevice` misses a readback path | Test `copyTextureToBuffer` under VulkanMod during Phase 4, not Phase 7 |
| Exported motion feels subtly wrong | `frames.bin` at render-frame rate plus `RenderTickCounter.Constant`; validated against a side-by-side OBS capture in Phase 3 |
| Preview and export disagree | Prevented structurally: one `FrameRenderer` and one `TimelineResolver`, both used by both consumers |
| Preview scrubbing stutters across cuts | Keep the `ReplaySession` warm, re-seek only when the resolved clip changes, expose a preview render-scale setting |
| Client-side UI state missed, HUD looks wrong | Phase 3's OBS comparison is the detector; extend `UI_STATE` as gaps surface |
| Other mods' HUDs do not reproduce | Accepted limitation, documented |
| Replay desync from server-side or modded behaviour | Accept and log; snapshots bound how far drift propagates |
| Mixin breakage on Minecraft/mappings bump | `defaultRequire = 1` makes it a loud crash; re-run `genSources` and re-verify targets |
| Packet re-encode fails on registry-dependent codecs | Capture the `DynamicRegistryManager` from the live network handler at recording start |
| FFmpeg stdin pipe deadlock | Dedicated writer thread; always drain stderr |

---

## End-to-end verification

Per phase, the "Done when" line is the gate. Before calling the mod finished:

1. `./gradlew build` - compiles and remaps. Add JUnit coverage for the pure-data pieces
   (`ActionCodec` round-trip, `frames.bin` sampling at fractional times, `UndoStack`), which are the
   parts testable without a game.
2. `./gradlew runClient` - record two minutes of singleplayer, then repeat on a multiplayer server.
   Open a chest, move items, type in chat, switch hotbar slots during both.
3. **The product test:** record a session with OBS running simultaneously, export the same span from
   Momenton, and compare frame by frame. Camera motion, HUD and inventory screens should match.
4. Split into clips, reorder, trim, undo, reload the project.
5. **Preview fidelity:** play the edit back, then export one frame at full resolution from a known
   timeline position and diff it against the preview at that position. They should differ only by
   render scale.
6. Export 1080p60 and confirm in VLC: correct duration, no A/V drift at the end, both audio tracks.
7. Export the same edit at 4K60 to confirm resolution is genuinely a free parameter.
8. Repeat 2-7 with VulkanMod 0.6.3 installed.
