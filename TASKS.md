# Momenton - Tasks

The living state file. Read it first, update it last. Design, reasoning and lessons live in
`IMPLEMENTATION_PLAN.md`.

## Start here

1. Read **Current state**, **Not yet verified** and **Known issues** below.
2. Read the `IMPLEMENTATION_PLAN.md` section for the current phase, and its "three things" section.
3. Run `gradlew.bat build` to confirm the tree is green before changing anything.
4. Work the next unchecked box. Work is done by parallel subagents in waves, each in its own git
   worktree; see "Parallel development" in the plan for the waves and file ownership.

A box is ticked only when the behaviour has been observed in game. Written but unobserved work goes
in **Not yet verified**.

## Before ending a session

1. Tick only what was observed.
2. Update **Current state**, **Not yet verified** and **Known issues**.
3. Add anything surprising to **Lessons** in `IMPLEMENTATION_PLAN.md`.
4. Run `gradlew.bat build` and record the result.

---

## Current state

- Phases 0-2 (foundations, recording, playback with seeking) are complete and observed.
- Phase 3 (replay fidelity) is in progress: the replayed player walks its path, carries its items,
  and follows hotbar and chat scroll. Tasks 1-12 below are the rest.
- Git repository on `main`. Wave 0 and Wave 1 are merged: imgui removed, tools on Gradle, seek
  state merge, perspective restore, snapshot-boundary race fix, snapshot timing log, finalise on
  write failure, `Recorder.synthesise`. All of Wave 1 is written and unobserved in game.
- Decisions: the editor uses vanilla Screens and ImGui is dropped; export comes before the editor;
  existing comments stay, new code adds none.
- Recordings in `run/momenton/recordings/`:
  - `2026-09-01_22-14-15.momenton`: 1752 ticks, the combat case. 46 swing starts (9 restarts), hits at
    89, 311, 411, 432, 1624, sweeps at 412 and 433, crits at 91 and 1625, Nether at 1055, back at 1265.
  - `2026-09-01_21-53-17.momenton`: 1782 ticks, the `UI_STATE` recording.
- Last build: `gradlew build --offline` green with the JUnit format test, and
  `gradlew checkReplay -Preplay=<abs path>` ALL PASS (Inspect, Setup, Motion, Swing, Boundary) on both
  recordings. This machine's JVM needs `-Djavax.net.ssl.trustStoreType=Windows-ROOT` in `org.gradle.jvmargs`
  to download dependencies; keep it in the user-level `~/.gradle/gradle.properties`, not the project
  file, because CI runs on Ubuntu.

## Not yet verified

- **Swing restarts.** `tools/Swing` measured 9 of 46 starts lost by the old rising-edge rule. Fixed in
  `UiStateDriver`, not yet watched in game.
- **A paused replay freezes the world.** `MinecraftClientMixin` sets `MinecraftClient.paused` right
  after `render` assigns it. Crosshair cooldown, arm, mobs and particles should stop, with no shake.
- **Removing the attack replay costs nothing visible.** Proven from source and `tools/Effects` shows
  every effect on the wire, but no hit has been watched since. Check this first; it is a deletion.
- **Leaving playback no longer crashes.** `ReplaySession` now checks `connection.isOpen()`; not yet
  tried through the pause menu's Disconnect or by closing the window.
- **Third person and crouch have no regression case.** Both observed once (third-person front only
  after `FLAG_FRONT_VIEW`); no recording on disk carries either.
- **Browser row without the dimension column** (`world   m:ss   date`, "Unknown world" for old files)
  has not been seen since the change.
- **The custom payload filter is unexercised.** No recording contains `CUSTOM_PAYLOAD_S2C`, but nothing
  could have produced one. Needs a server sending on a channel no installed mod claims.
- **Recording HUD indicator:** seen on screen, but not checked that it disappears on stop or survives
  an open screen.
- **Inventory snapshot fix** (`WorldSnapshot` sends `InventoryS2CPacket` on sync id 0): the filled
  hotbar was observed with the `LOCAL_PLAYER` box; confirm on the next playthrough and close.
- **Sneak fix** (sneak as a frame flag fed through a real `Input`): crouch observed once, no recording
  re-checks it.
- **Seek state merge** (task 2): a `]` seek across a slot or hotbar change keeps it, and the seek fires
  no swing. 1x playback should be unchanged.
- **Perspective restore** (task 4): after a replay ends or is left, the viewer's own perspective is back.
- **Snapshot-boundary race fix** (task 3): `Boundary` passes on both recordings but neither has a chunk
  or spawn packet on a boundary tick, so it proves nothing yet. Needs a recording that flies across
  chunk borders around ticks 500, 1000 and 1500.
- **Snapshot timing** (task 4): read the `Snapshot N took X ms` lines in `run/logs/latest.log`.
- **Finalise on write failure** (task 4): untested; needs a forced write failure.

## Known issues

- `defaultRequire = 1`: a mixin whose target moved is a startup crash after any Minecraft or Yarn bump.
- Yarn names drift; verify against `genSources` or `javap` (`PlayerInventory.getSelectedSlot()` moved).
- The first build on a clean machine (or clean Gradle cache) needs network for the `1.17-SNAPSHOT` Loom.
- A seek inside a dimension crossing shows entities before terrain. That is faithful; only a bug if
  terrain stays missing while playing (check `latest.log` for `Ignoring chunk`).
- A backward seek flashes the terrain-loading screen (it rejoins). Must be fixed before the editor.
- Render distance is a capture-time decision; a replay never holds more world than was sent.
- View bobbing on a driven player is unchecked (its velocity is zeroed each tick). Check at export.
- Sprinting is recorded (`FLAG_SPRINTING`) but not applied; FOV is already in `frames.bin`.
- The driven player never calls `Entity.move`: no footsteps, no sprint particles, never `onGround`.
- The tab list is not in the snapshot (`Ignoring player info update for unknown player`).
- Snapshot capture blocks the client thread every 500 ticks, not only at F9. Now logged; read the numbers.
- `ClientWorld.shouldTickTimeOfDay` is private, so a snapshot always says time advances (up to 1 s drift).
- The rebuilt join keeps the original `seed` and `isFlat` (not readable from `ClientWorld`).
- `local = false` registry flag for `OfflineConnection` is unresolved: non-synced tags may differ.
- `RenderTickCounter.Constant` has a package-private constructor; export needs an access widener.
- Pause menu frames are recorded as ordinary frames (tick advances while the world is frozen).
- **Snapshot-boundary race, residue:** two narrow windows remain after task 3. A packet whose stamp is
  read just before the boundary drain but queued just after is lost; one stamped `at+1` but drained by
  `apply()` is in the snapshot and replayed again (harmless for chunks and blocks, a relative entity
  move applies twice until the next position sync). Closing both needs the stamp and the queuing to be
  atomic in `ClientConnectionMixin`.
- **Container inventory moves are not recorded;** clicks are client-predicted and the server only
  corrects disagreements (task 6).
- **Containers never close in a replay;** closing is client-initiated and nothing applies it (task 7).
- **Chat draft text is not recorded** (task 7).
- **Replayed screens take the viewer's input:** clicks move items, Esc closes, `P` `[` `]` stop working
  (task 8).
- **Mouse is stored in scaled GUI pixels** at the recording's resolution (task 9).
- **No drawn cursor:** the OS draws it, so an export needs a sprite (Phase 4).

---

## Phase 0 - Foundations

Complete, observed. See `IMPLEMENTATION_PLAN.md`.

## Phase 1 - Recording

Complete, observed. See `IMPLEMENTATION_PLAN.md`. One box stays open:

- [ ] Packet filter: drop custom payloads for absent mods (`UnknownCustomPayload`). Written; no
      session has exercised it

## Phase 2 - Playback

Complete, observed. See `IMPLEMENTATION_PLAN.md`.

## Phase 3 - Replay fidelity

Goal: playback of blocks, a chest, item moves, chat typing and a portal looks right.

- [x] Move the replayed local player along its recorded path (position from `ENTITY_MOVE`, rotation
      from `frames.bin`)
- [x] Apply `LOCAL_PLAYER`: health, hunger, hotbar, held item, plus the snapshot inventory
- [x] Apply `UI_STATE`: selected slot and chat scroll
- [x] The attack needs no replay on 1.21.11; every effect is on the wire (removal itself unwatched)
- [x] 1a. `git init` and baseline commit (Wave 0, coordinator)
- [x] 1b. Remove the imgui dependencies from `build.gradle` (Wave 0, coordinator)
- [x] 1c. `tools` source set, `checkReplay -Preplay=<file>` and `runTool` tasks, `Verify` as a JUnit
      test, `.class` files gone (Wave 1, Tools). Observed: build runs the test, checkReplay ALL PASS
- [ ] 2. Seek state merge: merge partial `UI_STATE` / `LOCAL_PLAYER` field by field; during a seek keep
      only the latest swing and cooldown and drop the events (Wave 1, Playback fixes; written, unverified)
- [ ] 3. Snapshot-boundary race: stamp `at+1`, drain `client.getPacketApplyBatcher().apply()`, then
      snapshot; `submitBatch(at)` leaves `at+1` entries queued (Wave 1, Recorder fixes). Its offline
      check `Boundary` (Wave 1, Tools). Written, unverified in game
- [ ] 4. Restore the viewer's perspective on `stop` (Wave 1, Playback fixes). Log snapshot capture
      time, finalise on write failure, add `Recorder.synthesise(Packet<?>)` (Wave 1, Recorder fixes).
      Written, unverified
- [ ] 5. Local prediction as synthesised packets: one `ClientWorld` mixin on `syncWorldEvent`,
      `playSound`, `setBlockBreakingInfo` for the local player, skipping calls from inside network
      handlers (Wave 2, Local prediction)
- [ ] 6. Screen contents: `InventoryS2CPacket` for `currentScreenHandler` when stacks or cursor change
      (Wave 2, Screens)
- [ ] 7. Screen state: split `SCREEN_OTHER` into `CONTAINER` and `OTHER`, record the chat draft;
      playback closes on NONE, opens `InventoryScreen` and `ChatScreen` (Wave 2, Screens)
- [ ] 8. Replayed screens ignore the viewer via `ScreenMouseEvents` / `ScreenKeyboardEvents`, with `P`,
      `[`, `]` and Esc handled there (Wave 2, Screens)
- [ ] 9. Cursor stored normalised (format version bump) and fed to screen rendering through a
      `GameRenderer.render` mouse-argument mixin (Wave 2, Cursor)
- [ ] 10. Portal warp: `nauseaIntensity` in `UI_STATE`. Fire ticks in `LOCAL_PLAYER` (Wave 2, Body state)
- [ ] 11. `ENTITY_MOVE` records only the local player and its root vehicle; apply the vehicle pose
      (Wave 2, Body state)
- [ ] 12. **Gate:** a recording with block breaking and placing, a chest, item moves, chat typing and
      a portal plays back right. Also record once on a local `runServer` (coordinator and user)

## Phase 4 - Export (video)

- [ ] `export/ExportSession`: tick loop via `client.tick()`, fixed tick progress via an access widener
      on `RenderTickCounter.Constant`, chunk-build wait, main-framebuffer resize through a Window size
      mixin, GUI-scale rule (Wave 3, Export loop)
- [ ] Recording window size and GUI scale in metadata (Wave 3, Export loop)
- [ ] `FrameGrabber`: `ScreenshotRecorder` first, then `copyTextureToBuffer` + `mapBuffer` with 2-3
      rotating buffers (Wave 3, Pipeline)
- [ ] `FfmpegProcess`: PATH or configured binary, `libx264`, stdin writer thread, stderr drained
      (Wave 3, Pipeline)
- [ ] Drawn cursor sprite while a screen is open, with a toggle (Wave 3, Export loop)
- [ ] Export button and options screen in the replay browser: whole recording, resolution, fps,
      output (Wave 3, Export loop)
- [ ] **Gate:** 30 s exports to 1080p60, plays in VLC, and matches an OBS side-by-side. Run once
      under VulkanMod 0.6.3 to prove the readback

## Phase 5 - Game audio (Wave 4)

- [ ] `mixin/client/SoundEngineMixin`: open an OpenAL loopback device while exporting
- [ ] `audio/LoopbackRenderer`: `alcRenderSamplesSOFT` for exactly the samples of each frame
- [ ] Mux game audio with ffmpeg
- [ ] **Gate:** audio in sync at the end of a 30 s export

## Phase 6 - Editor on vanilla Screens

- [ ] `edit/Project`, `Clip`, `Timeline`, `TimelineResolver`, `UndoStack` as pure data with JUnit
      tests (Wave 3, Edit model)
- [ ] Timeline overlay screen on playback: trim, split, reorder, undo (Wave 4)
- [ ] Backward seeks without the loading screen (reuse the `ClientWorld`) (Wave 4)
- [ ] HUD toggles via `InGameHudMixin` (Wave 4)
- [ ] Export the edit through the same `ExportSession` (Wave 4)
- [ ] **Gate:** split into three clips, reorder, trim two, undo all, reload from disk, play back as
      continuous video in the edited order, HUD toggles taking effect immediately

## Phase 7 - Mic commentary (Wave 5)

- [ ] `audio/WavWriter`, `audio/MicRecorder` (`TargetDataLine`, 48 kHz, own thread)
- [ ] Tick-stamped first sample in metadata; mic device picker in settings
- [ ] Mic as a second audio track in export; per-track gain and mute
- [ ] Mic WAV played in sync during playback, seeking with the playhead
- [ ] **Gate:** an export carries game audio and commentary on separate tracks, both in sync

## Phase 8 - Hardening (Wave 5)

- [ ] Full pipeline under VulkanMod 0.6.3: capture, playback, export, readback
- [ ] Recording overhead under 1 ms per tick; negligible per-frame cost for `FrameTrackWriter`
- [ ] Disk full, dimension change and server kick mid-recording
- [ ] Replay from a mismatched Minecraft version rejected with a clear message
- [ ] JUnit for `ActionCodec` round-trip and fractional `frames.bin` sampling
- [ ] FFmpeg auto-download (pinned SHA-256) and hardware encoders (`h264_nvenc`, `h264_amf`,
      `h264_qsv`) with `libx264` fallback
- [ ] The rest of the multiplayer matrix
- [ ] Export at 4K60 from a 1080p session
- [ ] `README.md` final pass

---

## Next session

1. In-game batch on `2026-09-01_22-14-15.momenton` (existing items plus Wave 1). Nothing needs
   recording unless a step fails:
   1. Around ticks 411-433 (two sweeps back to back): sweep arc, mob flash and knockback, sweep sound.
      Around ticks 89 and 1624: crit particles. If missing, `tools/Effects` proves the packets are in
      the file, so check `run/logs/latest.log` for which end lost them.
   2. Around ticks 16-28, 148-159 and 168-178: the arm keeps swinging through each burst.
   3. Press `P` mid-swing: arm, cooldown ring, mobs, particles and items stop dead. Nothing shakes.
      Unpause: it resumes without a jump.
   4. Pause near the start, then `]` repeatedly: the selected hotbar slot and hotbar contents always
      match where it lands, and no swing fires on landing. Then play at 1x: swings look as before.
   5. Seek across the Nether crossings at 1055 and 1265; each lands in the right dimension.
   6. Set your view to first person, then leave playback via the pause menu's Disconnect: no
      `ClosedChannelException`, and you are still in first person. Repeat by closing the window.
2. Make one new recording, over 75 s, flying fast across chunk borders around 25 s, 50 s and 75 s.
   Run `checkReplay` on it (Boundary now has something to test) and read the `Snapshot N took X ms`
   lines in `run/logs/latest.log`.
3. Start Wave 2 (Local prediction, Screens, Cursor, Body state). After it, the coordinator merges,
   runs `build` and `checkReplay`, and the user does one in-game pass.
