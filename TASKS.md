# Momenton - Tasks

The living state file. Read it first, update it last. Design, reasoning and lessons live in
`IMPLEMENTATION_PLAN.md`.

## Start here

1. Read **Current state**, **Not yet verified** and **Known issues** below.
2. Read the `IMPLEMENTATION_PLAN.md` section for the current phase, and its "three things" section.
3. Run `gradlew.bat build` to confirm the tree is green before changing anything.
4. Work the next unchecked box. Work is done by parallel subagents in waves, each in its own git
   worktree; see "Parallel development" in the plan for the waves and file ownership.

A box is ticked only when the behaviour has been observed in game (or, for pure data, in JUnit).
Written but unobserved work goes in **Not yet verified**.

## Before ending a session

1. Tick only what was observed.
2. Update **Current state**, **Not yet verified** and **Known issues**.
3. Add anything surprising to **Lessons** in `IMPLEMENTATION_PLAN.md`.
4. Run `gradlew.bat build` and record the result.

---

## Current state

- **Second in-game run (2026-10-10, user):** block breaking and replayed inventories observed
  working. Observed broken, root-caused and fixed (written, unverified):
  - Export froze at the end (Not Responding, no MP4): `FrameGrabber.flush()` waited on GPU fences
    whose commands were never flushed (`GlGpuFence.awaitCompletion` passes flags 0); the game rendered
    all 7938 frames at 24 fps and ffmpeg kept pace, then waited 224 s for EOF. Now readback is
    non-blocking through vanilla's fenced callbacks, the session drains the last frames over extra
    passes, and `ffmpeg.finish()` plus the mux run on a background thread behind a "Finishing video"
    screen; ffmpeg is killed only after 60 s without progress (`5525484`).
  - Export showed the raw replay in slow motion: replaced by a progress overlay drawn after the grab
    (bar, frames, elapsed, ETA, fps, toggleable preview).
  - Export speed: 24 fps was CPU-bound (game plus x264 medium on an i5-1235U). Intel Quick Sync
    (`h264_qsv`, gq17) is probed per session and is the default when it works; software default is
    x264 superfast crf18 (measured SSIM 0.986 vs 0.983 for the old default, at a quarter of the CPU).
    Every 5% log line now carries a timing breakdown.
  - First-person sword had no dip after an attack: the replay reset only the attack cooldown, never
    `ticksSinceHandEquipping`. Attack-counter resets are now recorded as a `UI_STATE` event and
    replayed with `resetTicksSince` (`d396467`); old recordings fall back to it on cooldown drops.
- **Third in-game run (2026-10-10, user):** export finished and saved (7938 of 7938 frames, 32 fps,
  ffmpeg done 2.9 s after the last frame); sword dip observed correct. Timing log showed `copy`
  (map + flip) at 26 ms of a 31 ms frame: readback buffers lacked `USAGE_HINT_CLIENT_STORAGE`
  (`GL_CLIENT_STORAGE_BIT`); added (`1a7241d`) and measured on the fourth run: no change (`copy` 24.9 ms).
  On the ARB path readable buffers are persistently mapped, so the cost is the CPU reading slow
  memory; next: parallel copy off the render thread. Dying mobs vibrated in exports (user: not seen at 1x): the replay
  world had simulation distance 0 (the join's value; singleplayer sends the real one right after
  join, before F9), so corpses outside the viewer's chunk stopped advancing `deathTime` and their tilt
  snapped back every tick. Playback now applies the simulation distance a singleplayer client gets
  (`c0abdad`) and new snapshots record the live value (`ecace64`); `tools/Deaths` counts affected
  deaths (12 of 38 in `2026-10-10_09-42-39`). The agent's analysis says 1x replay should show it too;
  unconfirmed.
- **Fourth run (2026-10-10, user):** Esc during export left the game Not Responding after "Export
  cancelled after 1017 of 7800 frames"; temporaries left behind. User reports the death jitter is not
  visible in 1x replay, only in exports (fix `c0abdad` not yet checked in an export).
  Fixed (`107cc5b`, unverified): cancel waited without limit on `ffmpeg` exiting on the render thread
  (`destroyForcibly().onExit().join()`); now it never waits, and a cleanup thread deletes the
  temporaries with retries (Quick Sync holds the output file ~60 ms after exit). The frame copy now
  runs on 4 worker threads off the render thread with 4 readback buffers; the log splits `map`,
  `submit` and `copy ... MB/s`. If MB/s stays near 320 the split does not scale and GPU-side NV12
  conversion is next.
- **First in-game run (2026-10-10, user):** `runClient` launches with every mixin; on the new
  build playback, seek and pause work. Observed broken and fixed since: ffmpeg "not found" after
  `winget install ffmpeg` (a process started before the install keeps the old PATH; discovery now
  also looks in winget's `Packages` folder, `60ef0d7`); replayed GUIs invisible in Edit mode (the
  editor took the single screen slot; it now hosts the replayed screen under its panels, `e9de303`,
  unverified). Found offline: a mic recording's `audio/mic.wav` was a STORED entry with a data
  descriptor (zip filesystem rewrite), which `ZipInputStream` and the offline tools reject; it is now
  written in the same zip pass with size and CRC (`4d582d2`). `2026-10-10_09-02-53.momenton` (made
  before the fix) plays in game; a re-zipped copy passes every `checkReplay` check. The older two
  recordings were deleted by the user.
- Phases 0-2 (foundations, recording, playback with seeking) are complete and observed.
- Waves 0-5 are merged on `main`. **Nothing from Waves 1-5 has been observed in game**; only JUnit.
  - Waves 1-3: tools on Gradle and the Wave 1 playback/recorder fixes; Phase 3 fidelity (local
    prediction, replayed screens, cursor format version 2, warp, fire, vehicles); video export and
    the `edit/` data model.
  - Wave 4: game audio in export through an OpenAL loopback device (`audio/LoopbackRenderer`,
    `mixin/client/audio/*`), swapped in by `reloadSounds()`; `round(k*48000/fps)` cumulative samples
    per grabbed frame; sound loads blocked on and device-change polling off while exporting; muxed
    afterwards. Editor overlay (`ui/editor/EditorScreen`, `EditSession`, Edit button in the browser,
    key O) with HUD toggles (`edit/HudToggles`, `InGameHudMixin`) saved in the project. Seeks reuse
    the `ClientWorld`: the first load joins, later snapshots drop the join and clear the world in
    place, or respawn when the snapshot is in another dimension. Seeks whose snapshot-to-target span
    crosses a dimension still show the loading screen. Fixed a latent bug where seeking back from the
    Nether loaded Nether chunks into the Overworld world.
  - Wave 5, ffmpeg like ReplayMod: `EncodingPreset` (MP4 default/custom bitrate/potato, WebM custom
    bitrate, MKV lossless), placeholder templates, quote-aware splitter, discovery order,
    `<gameDir>/momenton/export.log`, startup failure detected by the missing `Output #0` line, 30 s
    close; mux AAC for mp4/mkv, Opus for webm; `ExportScreen` with format, bitrate, editable command
    and arguments; `ExportFailedScreen`, shown by `ExportSession` on any ffmpeg failure.
  - Wave 5, edited export: the clip list exports as one video with a settle at each cut (seek to
    target-1 plus one real tick, at least 3 client ticks, one-shot sounds stopped, particles
    cleared). `currentTick()` means the tick on screen everywhere: a seek to T leaves T, each tick
    adds one, playback ends at D-1. `ReplaySession.file()`; `EditSession` ticked from
    `MomentonClient`; the editor reopens after a loading screen. Export waits for
    `ClientWorld.chunkUpdaters` and caps the chunk wait at 600 render passes.
  - Wave 5, hardening: recorded cursor only on replay screens; replays from another Minecraft version
    greyed out in the browser; codec, `FrameTrack` and `HudToggles` tests; `FrameTrack` samples in
    double.
  - Wave 5, mic commentary: `audio/mic.wav` stored in the zip, metadata `micStartTick` and
    `micSampleRate`, live sync during replay, recordings without a mic still work, mic toggle key
    unbound by default. **The mic is not yet in exports.**
- 29 client mixins; the first launch is itself a check (`defaultRequire = 1`).
- Decisions: vanilla Screens, no ImGui; export before the editor; new code adds no comments.
- Recordings in `run/momenton/recordings/`:
  - `2026-09-01_22-14-15.momenton`: 1752 ticks, the combat case. 46 swing starts (9 restarts), hits at
    89, 311, 411, 432, 1624, sweeps at 412 and 433, crits at 91 and 1625, Nether at 1055, back at 1265.
  - `2026-09-01_21-53-17.momenton`: 1782 ticks, the `UI_STATE` recording.
  - Neither has `framebufferWidth/Height`, `guiScale` or a mic track, so an export of them keeps the
    viewer's own GUI scale.
- Last build (2026-10-10): `gradlew build --offline` green. `gradlew test --offline`: 57 tests, 54
  pass, 3 `FfmpegProcess` encode tests skip until ffmpeg is installed, 0 fail. `checkReplay` last ran
  ALL PASS on both recordings (Wave 4). Downloads need `-Djavax.net.ssl.trustStoreType=Windows-ROOT`
  in `org.gradle.jvmargs` in the user-level `~/.gradle/gradle.properties`, never the project file.
- **ffmpeg is not installed on this machine.** `winget install ffmpeg` (then a new terminal), or unpack
  it to `<gameDir>/ffmpeg/bin/`, or type its path in the export screen; `-Dmomenton.ffmpeg=<path>`
  also works.

## Not yet verified

Phase 1-2 leftovers:

- **Swing restarts.** `tools/Swing` measured 9 of 46 starts lost by the old rising-edge rule. Fixed in
  `UiStateDriver`, not yet watched in game.
- **A paused replay freezes the world**: crosshair cooldown, arm, mobs and particles stop, no shake.
- **Removing the attack replay costs nothing visible.** It is a deletion; check it first.
- **Leaving playback no longer crashes** (pause menu Disconnect, closing the window).
- **Third person and crouch** have no regression recording. **Inventory snapshot**: seen once.
- **Browser** row (`world   m:ss   date`), title and empty message.
- **Custom payload filter** is unexercised; needs a server sending on a channel no mod claims.
- **Recording HUD indicator** disappears on stop and survives an open screen.

Wave 1: seek state merge (a `]` across a slot or hotbar change keeps it, no swing); perspective
restore; snapshot-boundary race fix (no recording has a chunk on a boundary tick yet); snapshot timing
log; finalise on write failure (needs a forced failure).

Wave 2: local prediction (break/place particles and sounds, mining crack and hit sound, eating,
footsteps, doors, nothing twice); screens open and close on time with recorded contents and draft, ignore clicks and
typing, `P` `[` `]` F10 work over them; cursor at any window size, version 1 recordings unchanged;
portal warp, fire overlay, boat and horse with remount.

Wave 3: export end to end (MP4 at the chosen size, fps and duration, toast); no terrain holes; motion
matches playback; export resolution and GUI scale, window restored, resize ignored; cursor sprite and
its toggle; Esc cancels, no toasts or pause menu in frames, replay left paused; readback under
VulkanMod 0.6.3; recording metadata carries `framebufferWidth`, `framebufferHeight`, `guiScale`.

Wave 4: export audio (AAC 48 kHz, length matches, in sync at start and end, also at 144 fps; speakers
silent during export and working after); editor overlay (split, reorder, trim, delete, undo/redo, save,
reload, play follows the edit, HUD toggles hide immediately and persist, O toggles); seeks without the
loading screen inside a dimension; seeking back from the Nether lands in the right world.

Wave 5: presets produce playable mp4/webm/mkv with the right audio codec; `export.log` written; the
failure screen on a bad argument and on a missing ffmpeg; edited export of several clips as one video
with clean cuts; editor `<` `>` step exactly one tick; O on a replay opened with Play; editor reopens
after a loading screen; chunk-updater wait (no holes right after a seek or cut); recorded cursor only
on replay screens; other-version replays greyed out; mic records to `audio/mic.wav` and plays in sync,
following seeks and pause.

## Known issues

- Mic recordings made before `4d582d2` fail the offline tools (`only DEFLATED entries can have EXT
  descriptor`); the mod reads them. Re-zip to check them offline.
- Other mods' HUDs export only if installed at record and export time and rebuildable from the
  replayed world; Iris shader animation is driven by wall-clock time, so it runs at real-time speed
  during a slow export (needs a compat hook, as ReplayMod has). Resource packs come from whatever is
  enabled at export; neither the pack list nor the mod list is stored in the recording yet.
- `defaultRequire = 1`: a mixin whose target moved is a startup crash after any Minecraft or Yarn bump.
- Yarn names drift; verify against `genSources` or `javap` (`PlayerInventory.getSelectedSlot()` moved).
- The first build on a clean machine (or clean Gradle cache) needs network for the `1.17-SNAPSHOT` Loom.
- A seek inside a dimension crossing shows entities before terrain. That is faithful; only a bug if
  terrain stays missing while playing (check `latest.log` for `Ignoring chunk`).
- Backward seeks no longer flash the loading screen, except a seek whose snapshot-to-target span
  crosses a dimension (every new `ClientWorld` resets the renderer). Removing it needs an extra
  snapshot right after each dimension change, recorded.
- Export: after 600 render passes the chunk wait grabs anyway with a warning, so a stuck chunk can
  leave a hole in that frame.
- Export: a frame re-rendered while waiting for chunks gets extra screen-animation delta, and
  wall-clock effects (enchant glint, text caret blink) are not deterministic between exports.
- Export audio: sounds that started before the export's first tick or before a cut are not heard
  (one-shots are stopped at each cut, loops restart); a sound can start up to a frame late.
- Export of old recordings (no `guiScale` in metadata) keeps the viewer's GUI scale.
- The mic is not in exports yet; it records from the system default input (no device picker) and is
  enabled by default (with no mic it logs once and records without). If attaching fails, the WAV is
  left beside the recording as `.<name>.mic.wav`.
- While the editor is open, replayed inventory and chat screens are held back behind it.
- A recording with no `minecraftVersion` in metadata counts as the running version in the browser.
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
  replayed again. Closing both needs the stamp and the queuing to be atomic in `ClientConnectionMixin`.
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
- [ ] 9. Cursor normalised (format version 2) and fed to replay screens only (Waves 2, 5; unverified)
- [ ] 10. Portal warp and fire ticks applied at TAIL of the tick (Wave 2; written, unverified)
- [ ] 11. `ENTITY_MOVE` for the player and its vehicle, vehicle pose applied (Wave 2; unverified)
- [ ] 12. **Gate:** a recording with block breaking and placing, a chest, item moves, chat typing and
      a portal plays back right. Also record once on a local `runServer`

## Phase 4 - Export (video)

- [ ] `ExportSession`: one vanilla `render` pass per output frame, replay ticks instead of the wall
      clock, fixed tick progress through a `RenderTickCounter` record, grab only once chunk building
      and `chunkUpdaters` have settled (written, unverified)
- [ ] Export resolution, window resize cancelled, GUI scale from metadata (written, unverified)
- [ ] `FrameGrabber`: 3 rotating buffers, a fence per grab, rows flipped (written, unverified)
- [ ] `FfmpegProcess` and `EncodingPreset` like ReplayMod: presets, templates, discovery, `export.log`,
      `Output #0` startup check (written; command line, splitter and presets pass in JUnit, the 3
      encode tests skip without ffmpeg)
- [ ] `ExportScreen` (format, bitrate, command, arguments) and `ExportFailedScreen` (written, unverified)
- [ ] Cursor sprite while a screen is open, with a toggle (written, unverified)
- [ ] **Gate:** 30 s exports to 1080p60, plays in VLC, and matches an OBS side-by-side. Run once
      under VulkanMod 0.6.3 to prove the readback

## Phase 5 - Game audio

- [ ] `SoundEngineMixin`: loopback device (48 kHz stereo s16) swapped in by `reloadSounds()` while
      exporting, restored after, also on cancel and error (written, unverified)
- [ ] `LoopbackRenderer`: `alcRenderSamplesSOFT` for `round(k*48000/fps)` cumulative samples per
      grabbed frame; sound loads blocked on and device-change polling off (written, unverified)
- [ ] Mux: `-c:v copy`, AAC 192k for mp4/mkv, libopus for webm, `-shortest` (written, unverified)
- [ ] **Gate:** audio in sync at the end of a 30 s export

## Phase 6 - Editor on vanilla Screens

- [x] `edit/Clip`, `Timeline`, `TimelineResolver`, `UndoStack`, `Project`, `HudToggles` as pure data.
      Observed in JUnit
- [ ] Timeline overlay: trim, split, reorder, delete, undo/redo, save, one-tick stepping, preview
      follows the edit, O and the Edit button (Waves 4-5; written, unverified)
- [ ] Backward seeks without the loading screen: world reused in place; dimension-crossing seeks
      still show it (Wave 4; written, unverified)
- [ ] HUD toggles via `InGameHudMixin`, ten elements, saved in the project (Wave 4; written,
      unverified)
- [ ] Export the edited clip list as one video, settled at each cut (Wave 5; written, unverified)
- [ ] **Gate:** split into three clips, reorder, trim two, undo all, reload from disk, play back as
      continuous video in the edited order, HUD toggles taking effect immediately

## Phase 7 - Mic commentary

- [ ] `WavWriter` (JUnit passes), `MicRecorder` (`TargetDataLine`, 48 kHz mono, own thread), staged
      beside the recording and stored in the zip as `audio/mic.wav` (Wave 5; written, unverified)
- [ ] First-sample tick in metadata (`micStartTick`, `micSampleRate`) (Wave 5; written, unverified)
- [ ] Mic WAV played in sync during playback, following seeks and pause (Wave 5; written, unverified)
- [ ] Mic as a second audio track in export; per-track gain and mute
- [ ] Mic device picker in settings
- [ ] **Gate:** an export carries game audio and commentary on separate tracks, both in sync

## Phase 8 - Hardening

- [ ] Full pipeline under VulkanMod 0.6.3: capture, playback, export, readback
- [ ] Recording overhead under 1 ms per tick; negligible per-frame cost for `FrameTrackWriter`
- [ ] Disk full, dimension change and server kick mid-recording
- [ ] Replay from a mismatched Minecraft version greyed out in the browser with its version (Wave 5;
      written, unverified)
- [x] JUnit for `ActionCodec` and `EntityMoveCodec` round-trip and fractional `frames.bin` sampling
- [ ] FFmpeg auto-download (pinned SHA-256) and hardware encoders (`h264_nvenc`, `h264_amf`,
      `h264_qsv`) with `libx264` fallback
- [ ] A cap on the export chunk wait (600 render passes; Wave 5, written, unverified)
- [ ] The rest of the multiplayer matrix
- [ ] Export at 4K60 from a 1080p session
- [ ] `README.md` final pass

---

## Next session

1. **Install ffmpeg** (`winget install ffmpeg` and a new terminal, or unpack it to
   `<gameDir>/ffmpeg/bin/`), check `ffmpeg -version`, then `gradlew.bat test`: the 3 skipped
   `FfmpegProcess` encode tests now run (57 tests, all should pass).
2. **One consolidated in-game pass**, below. Report each step as pass or fail with what was seen.
3. **Then Wave 6:** the mic into export (second audio track, per-track gain and mute, device
   picker), then Phase 8 hardening.

The in-game pass (Waves 1-5):

1. `gradlew.bat runClient`: no mixin apply error (29 client mixins).
2. Browser: title and empty message draw; rows read `world   m:ss   date`. Copy a recording, change
   `minecraftVersion` in its `metadata.json`: the copy is greyed out with that version and cannot play.
3. On `2026-09-01_22-14-15.momenton`: sweeps at 411-433, crits at 89 and 1624; swing bursts at 16-28,
   148-159, 168-178; `P` mid-swing freezes everything without shaking; `]` from near the start keeps
   the slot and hotbar with no swing on landing. Seeks: `[` around 700 shows no loading screen and no
   holes; `[` around 1200 shows a brief screen then the Nether with Nether sky; from 1350 back before
   1055 and playing through both crossings keeps terrain intact; no `Ignoring chunk` in `latest.log`.
   Leave via Disconnect and by closing the window (no `ClosedChannelException`, own perspective back).
4. Make one new recording with a microphone connected, talking throughout: breaking and placing
   blocks, slow mining with one cancel, eating, walking, a door; inventory with item moves and a
   cursor stack; a chest with moves and a shift-click; a crafting table; a chat draft left unsent,
   then a sent message; a Nether portal warp; lava in and out; a boat and a horse with a remount;
   third person and crouching; fast flying across chunk borders around 25 s, 50 s and 75 s. Stop with
   the recording HUD visible over an open screen first.
5. Offline: `checkReplay` on it; `metadata.json` has `framebufferWidth/Height`, `guiScale`,
   `micStartTick` and `micSampleRate`; the zip holds `audio/mic.wav`; no `.mic.wav` is left in the
   recordings folder; `latest.log` has the `Snapshot N took X ms` lines.
6. Replay it and check the Wave 1 and Wave 2 items: nothing plays twice, screens open and close on
   time and ignore clicks and typing, `P` `[` `]` F10 work over them, the cursor follows at another
   window size, warp, fire and vehicles look right. The commentary plays in sync with the picture,
   stays in sync after `[`, `]` and `P`, and stops when playback ends.
7. Editor: Edit opens the replay with the timeline over an undimmed view; `<` and `>` move exactly one
   tick (the clock moves 0.05 s); split twice, reorder, trim two, undo all, redo; Ctrl+S writes
   `<replay>.momenton.json`, reopening restores it; play follows the edited order across cuts; each HUD
   toggle hides its element immediately and survives reopening; O toggles the editor; the real mouse
   (not the recorded cursor) works in the editor; the editor comes back after a seek that showed the
   loading screen. Open a replay with Play, press O: the editor opens.
8. Export 10 s at 1280x720 30 fps, MP4 default, with the window at another size (say 1600x900).
   Check the duration, no terrain holes, the HUD at its recorded relative size, the cursor sprite over
   the inventory, no toasts or pause menu, the window restored and the replay paused on the last
   frame afterwards; resize the window mid-export. Audio: AAC 48 kHz, same length as the video, in
   sync at start and end; speakers silent during export and working after. `momenton/export.log`
   starts with `> ` and the command.
9. Export 10 s at 1920x1080 60 fps of a stretch with a screen open, cursor toggle off. Compare motion
   with playback; check view bobbing and crouch height. Export once more at 144 fps: audio in sync.
10. From the editor, export three non-adjacent clips: one video whose length is the sum of the clips;
    each cut is clean (no entities sliding across it, no sounds or particles carried over, no holes).
11. Presets: WebM custom bitrate (Opus audio), MKV lossless and MP4 potato each give a playable file
    with the right extension. Add a bogus argument (say `-c:v nosuchcodec`): the failure screen shows
    the log and Back returns to the settings. Type a wrong ffmpeg path: the failure screen says so.
12. Start one more export and press Esc: it stops with a toast, sound comes back, and no
    `momenton-video-*` or `momenton-audio-*.pcm` is left in the exports folder.
13. Then the gates: Phase 3 (task 12), Phase 4 (including VulkanMod 0.6.3), Phase 5, Phase 6.
