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
- Git repository on `main`. Waves 0-3 are merged; nothing from Waves 1-3 has been observed in game.
  - Wave 1: imgui removed, tools on Gradle, seek state merge, perspective restore, snapshot-boundary
    race fix, snapshot timing log, finalise on write failure, `Recorder.synthesise`.
  - Wave 2 (Phase 3 fidelity): local prediction as synthesised packets, screen contents/state/chat
    draft and viewer-input blocking, normalised cursor (format version 2), portal warp, fire ticks,
    vehicle pose.
  - Wave 3: video export (`export/FfmpegProcess`, `FrameGrabber`, `ExportSession`, six export
    mixins), `ui/ExportScreen` with an Export button in the browser, and the `edit/` data model.
- Wave 4 is running: game audio loopback in export, the editor timeline overlay with HUD toggles,
  backward seek without the loading screen. Wave 5: export the edited sequence (several ranges), mic
  commentary, hardening.
- 23 client mixins; the first launch is itself a check (`defaultRequire = 1`).
- Decisions: the editor uses vanilla Screens and ImGui is dropped; export comes before the editor;
  existing comments stay, new code adds none.
- Recordings in `run/momenton/recordings/`:
  - `2026-09-01_22-14-15.momenton`: 1752 ticks, the combat case. 46 swing starts (9 restarts), hits at
    89, 311, 411, 432, 1624, sweeps at 412 and 433, crits at 91 and 1625, Nether at 1055, back at 1265.
  - `2026-09-01_21-53-17.momenton`: 1782 ticks, the `UI_STATE` recording.
  - Neither has `framebufferWidth/Height` or `guiScale` in metadata, so an export of them keeps the
    viewer's own GUI scale.
- Last build: `gradlew build --offline` green. JUnit: 26 `edit/`, 3 format and 2 `FfmpegProcess` pass;
  3 `FfmpegProcess` encode tests skip until ffmpeg is installed (re-run `gradlew test` after). And `gradlew checkReplay -Preplay=<abs path>` ALL PASS (Inspect, Setup, Motion,
  Swing, Boundary) on both recordings. This machine's JVM needs
  `-Djavax.net.ssl.trustStoreType=Windows-ROOT` in `org.gradle.jvmargs` to download dependencies; keep
  it in the user-level `~/.gradle/gradle.properties`, not the project file, because CI runs on Ubuntu.
- **ffmpeg is not installed on this machine.** Install it (`winget install ffmpeg`, then a new
  terminal) or type its path in the export screen; `-Dmomenton.ffmpeg=<path>` also works.

## Not yet verified

Phase 1-2 leftovers:

- **Swing restarts.** `tools/Swing` measured 9 of 46 starts lost by the old rising-edge rule. Fixed in
  `UiStateDriver`, not yet watched in game.
- **A paused replay freezes the world.** `MinecraftClientMixin` sets `MinecraftClient.paused` right
  after `render` assigns it. Crosshair cooldown, arm, mobs and particles should stop, with no shake.
- **Removing the attack replay costs nothing visible.** Proven from source and `tools/Effects`, but no
  hit has been watched since. It is a deletion; check it first.
- **Leaving playback no longer crashes** (pause menu Disconnect, closing the window).
- **Third person and crouch** have no regression recording. **Inventory snapshot**: seen once.
- **Browser** row (`world   m:ss   date`), title and empty message (zero-alpha colours before Wave 3).
- **Custom payload filter** is unexercised; needs a server sending on a channel no mod claims.
- **Recording HUD indicator** disappears on stop and survives an open screen.

Wave 1:

- **Seek state merge**: a `]` seek across a slot or hotbar change keeps it, and fires no swing.
- **Perspective restore**: after a replay ends or is left, the viewer's own perspective is back.
- **Snapshot-boundary race fix**: `Boundary` passes, but no recording has a chunk or spawn packet on a
  boundary tick yet.
- **Snapshot timing** (`Snapshot N took X ms` in `latest.log`); **finalise on write failure** (needs
  a forced failure).

Wave 2:

- **Local prediction**: break/place particles and sounds, mining crack and hit sound, eating,
  footsteps and door events are recorded as packets; nothing plays twice.
- **Screens**: inventory, chest and chat open and close on time with recorded contents and draft;
  clicks and typing on replayed screens do nothing; `P` `[` `]` F10 work over them.
- **Cursor**: new recordings are format version 2; replayed screens follow the recorded cursor at any
  window size or GUI scale; version 1 recordings play as before.
- **Portal warp, fire overlay, vehicles**: warp builds up, fire starts on the lava tick, boat and horse
  carry the rider along the recorded path, no jump on remount.

Wave 3:

- **Export runs end to end**: Export button, the screen's fields, ffmpeg found on PATH or typed in,
  an MP4 at the chosen size and fps with the right duration, a toast at the end.
- **Frames are complete**: no terrain holes after the start seek or while chunks stream in.
- **Motion matches playback**: camera, swing, view bobbing, crouch height and hurt tilt interpolate
  as in live play (fixed tick progress per frame).
- **Resolution and GUI scale**: an export at a size other than the window gets the HUD at its
  recorded relative size; the window is restored afterwards and resizing it mid-export does nothing.
- **Cursor sprite** drawn over replayed screens, and absent with the toggle off.
- **Esc cancels**, other keys do nothing, toasts and the pause menu never appear in frames, the replay
  is left paused on the last frame.
- **Readback under VulkanMod 0.6.3** (Phase 4 gate).
- **Recording metadata** carries `framebufferWidth`, `framebufferHeight` and `guiScale`.

## Known issues

- `defaultRequire = 1`: a mixin whose target moved is a startup crash after any Minecraft or Yarn bump.
- Yarn names drift; verify against `genSources` or `javap` (`PlayerInventory.getSelectedSlot()` moved).
- The first build on a clean machine (or clean Gradle cache) needs network for the `1.17-SNAPSHOT` Loom.
- A seek inside a dimension crossing shows entities before terrain. That is faithful; only a bug if
  terrain stays missing while playing (check `latest.log` for `Ignoring chunk`).
- A backward seek flashes the terrain-loading screen (it rejoins). Wave 4 removes it.
- `jump()` leaves the tick where it was, so the first tick after a seek shows the same tick twice.
  Export compensates by aiming at `target + 1`; preview still shows it.
- Export: a frame re-rendered while waiting for chunks gets extra screen-animation delta, and
  wall-clock effects (enchant glint, text caret blink) are not deterministic between exports.
- Export: the chunk wait has no cap; a chunk that never finishes stalls the export (Esc cancels).
- Export of old recordings (no `guiScale` in metadata) keeps the viewer's GUI scale.
- Render distance is a capture-time decision; a replay never holds more world than was sent.
- View bobbing on a driven player is unchecked (its velocity is zeroed each tick). Check at export.
- Sprinting is recorded (`FLAG_SPRINTING`) but not applied; FOV is already in `frames.bin`.
- The driven player never calls `Entity.move`: no sprint particles, never `onGround` (footsteps come
  from recorded packets).
- The tab list is not in the snapshot (`Ignoring player info update for unknown player`).
- Snapshot capture blocks the client thread every 500 ticks, not only at F9. Now logged; read the numbers.
- `ClientWorld.shouldTickTimeOfDay` is private, so a snapshot always says time advances (up to 1 s drift).
- The rebuilt join keeps the original `seed` and `isFlat` (not readable from `ClientWorld`).
- `local = false` registry flag for `OfflineConnection` is unresolved: non-synced tags may differ.
- Pause menu frames are recorded as ordinary frames (tick advances while the world is frozen).
- **Snapshot-boundary race, residue:** a packet whose stamp is read just before the boundary drain but
  queued just after is lost; one stamped `at+1` but drained by `apply()` is in the snapshot and
  replayed again (a relative entity move applies twice until the next position sync). Closing both
  needs the stamp and the queuing to be atomic in `ClientConnectionMixin`.
- Mining crumb particles (`ParticleManager.addBlockBreakingParticles`) have no packet and do not replay.
- A replayed driven player may play its own water splash on top of the recorded one; listen for it.
- Synthesised `ClientPlayerEntity.playSound` and hit sounds use a fresh seed, so the variant may differ.
- The portal trigger sound (`BLOCK_PORTAL_TRIGGER`, client-only) is not recorded.
- Boat paddles do not animate on replay; they come from the rider's movement input, which is not recorded.
- A seek into a stretch where a container was already open cannot show it; the snapshot does not
  re-send the open-screen packet.
- A replayed `OpenScreenS2CPacket` replaces the viewer's pause menu if it is open.
- Holding `]` over a replayed screen repeats the seek; bed chat replays as a plain `ChatScreen`.

---

## Phase 0 - Foundations

Complete, observed. See `IMPLEMENTATION_PLAN.md`.

## Phase 1 - Recording

Complete, observed. One box stays open:

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
- [x] 1a-1b. `git init`, baseline commit, imgui removed (Wave 0)
- [x] 1c. `tools` source set, `checkReplay` and `runTool` tasks, `Verify` as JUnit (Wave 1). Observed
- [ ] 2. Seek state merge (Wave 1; written, unverified)
- [ ] 3. Snapshot-boundary race and its `Boundary` check (Wave 1; written, unverified in game)
- [ ] 4. Perspective restore, snapshot log, finalise on failure, `synthesise` (Wave 1; unverified)
- [ ] 5. Local prediction as synthesised packets (Wave 2; written, unverified)
- [ ] 6. Screen contents: `InventoryS2CPacket` when stacks or cursor change (Wave 2; written, unverified)
- [ ] 7. Screen kind, chat draft, playback opens and closes screens (Wave 2; written, unverified)
- [ ] 8. Replayed screens ignore the viewer; `P` `[` `]` Esc handled there (Wave 2; written, unverified)
- [ ] 9. Cursor normalised (format version 2) and fed to screen rendering (Wave 2; written, unverified)
- [ ] 10. Portal warp and fire ticks applied at TAIL of the tick (Wave 2; written, unverified)
- [ ] 11. `ENTITY_MOVE` for the player and its vehicle, vehicle pose applied (Wave 2; unverified)
- [ ] 12. **Gate:** a recording with block breaking and placing, a chest, item moves, chat typing and
      a portal plays back right. Also record once on a local `runServer`

## Phase 4 - Export (video)

All written in Wave 3, none observed.

- [ ] `ExportSession`: one vanilla `MinecraftClient.render` pass per output frame, ticks from the
      replay instead of the wall clock, fixed tick progress through a custom `RenderTickCounter`
      record (no access widener), grab only once chunk building has settled (written, unverified)
- [ ] Export resolution through `Window.setFramebufferWidth/Height` + `onResolutionChanged`, window
      resize events cancelled; GUI scale `round(recordedScale * min(w/recW, h/recH))`; metadata
      `framebufferWidth/Height` and `guiScale` (written, unverified)
- [ ] `FrameGrabber`: 3 rotating `MAP_READ | COPY_DST` buffers, a fence per grab, rows flipped
      top-down (written, unverified)
- [ ] `FfmpegProcess`: `-Dmomenton.ffmpeg` or PATH, `libx264` crf 18, 3-frame bounded writer thread,
      stderr tail in errors (written; command line and locate pass in JUnit, the encode tests skip
      without ffmpeg)
- [ ] Cursor sprite while a screen is open, with a toggle (written, unverified)
- [ ] Export button and screen in the browser: size, fps, range, output, ffmpeg path, cursor
      (written, unverified)
- [ ] **Gate:** 30 s exports to 1080p60, plays in VLC, and matches an OBS side-by-side. Run once
      under VulkanMod 0.6.3 to prove the readback

## Phase 5 - Game audio (Wave 4)

- [ ] `mixin/client/SoundEngineMixin`: open an OpenAL loopback device while exporting
- [ ] `audio/LoopbackRenderer`: `alcRenderSamplesSOFT` for exactly the samples of each frame
- [ ] Mux game audio with ffmpeg
- [ ] **Gate:** audio in sync at the end of a 30 s export

## Phase 6 - Editor on vanilla Screens

- [ ] `edit/Clip`, `Timeline`, `TimelineResolver`, `UndoStack`, `Project` (`<replay>.momenton.json`)
      as pure data (Wave 3). JUnit tests observed passing; no UI uses it yet
- [ ] Timeline overlay screen on playback: trim, split, reorder, undo (Wave 4)
- [ ] Backward seeks without the loading screen (reuse the `ClientWorld`) (Wave 4)
- [ ] HUD toggles via `InGameHudMixin` (Wave 4)
- [ ] Export the edited sequence (several ranges) through the same `ExportSession` (Wave 5)
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
- [ ] A cap on the export chunk wait
- [ ] The rest of the multiplayer matrix
- [ ] Export at 4K60 from a 1080p session
- [ ] `README.md` final pass

---

## Next session

One in-game pass covers everything written since Phase 2. Wave 4 items are appended here when it
merges. Report each step as pass or fail with what was seen.

1. Install ffmpeg (`winget install ffmpeg`, new terminal, `ffmpeg -version`), then `gradlew.bat test`:
   the 3 skipped `FfmpegProcess` encode tests now pass. Launch `gradlew.bat runClient`: no mixin
   apply error (23 client mixins).
2. Browser: title and empty message draw; rows read `world   m:ss   date`.
3. On `2026-09-01_22-14-15.momenton`: sweeps at 411-433, crits at 89 and 1624; swing bursts at 16-28,
   148-159, 168-178; `P` mid-swing freezes everything without shaking; `]` from near the start keeps
   the slot and hotbar with no swing on landing; seek across 1055 and 1265; leave via Disconnect and by
   closing the window (no `ClosedChannelException`, own perspective restored).
4. Make one new recording covering: breaking and placing blocks, slow mining with one cancel, eating,
   walking, a door; inventory with item moves and a cursor stack; a chest with moves and a
   shift-click; a crafting table; a chat draft left unsent, then a sent message; a Nether portal
   warp; lava in and out; a boat and a horse with a remount; third person and crouching; fast flying
   across chunk borders around 25 s, 50 s and 75 s. Stop with the recording HUD visible over an open
   screen first.
5. Offline: `checkReplay` on it, its `metadata.json` has `framebufferWidth/Height` and `guiScale`, and
   the `Snapshot N took X ms` lines.
6. Replay it and check each Wave 1 and Wave 2 item in **Not yet verified**: nothing plays twice,
   screens open and close on time and ignore clicks and typing, `P` `[` `]` F10 work over them, the
   cursor follows at a different window size, warp, fire and vehicles look right.
7. Export 10 s of it at 1280x720 30 fps with the window at another size (say 1600x900). Check the
   duration, no terrain holes, the HUD at its recorded relative size, the cursor sprite over the
   inventory, no toasts or pause menu in the video, the window restored afterwards, the replay paused
   on the last frame. Try resizing the window mid-export.
8. Export 10 s at 1920x1080 60 fps of a stretch with a screen open, with the cursor toggle off. Compare
   motion with playback; check view bobbing and crouch height.
9. Start one more export and press Esc: it stops with a toast and leaves no stuck state.
10. Then the Phase 3 gate (task 12) and the Phase 4 gate.
