# Momenton - Tasks

The living state file. Read it first, update it last. Design, reasoning and lessons live in
`IMPLEMENTATION_PLAN.md` (Export, Audio, Parallel development and Lessons sections are current).

## Start here

1. Read **Current state**, **Observed in game**, **Not yet verified** and **Known issues**.
2. Read **How work is done** below before launching anything.
3. `./gradlew build --offline` must be green before changing anything.
4. The next step is always **Next session** at the bottom: the user's in-game test pass, then the
   fixes it produces, then the open phase boxes.

A box is ticked only when the behaviour has been observed in game (or, for pure data, in JUnit).
Written but unobserved work goes in **Not yet verified**.

## Before ending a session

1. Tick only what was observed; move it from Not yet verified to Observed in game.
2. Update Current state, Not yet verified and Known issues; rewrite Next session.
3. Add anything surprising to Lessons in `IMPLEMENTATION_PLAN.md`.
4. `./gradlew build --offline --rerun-tasks` and record the result.

## How work is done

- **Parallel waves.** The coordinator (main session) launches subagents with the Agent tool,
  `isolation: "worktree"`, in the background, several per message. Each brief lists the files the agent
  owns; agents never touch other files. Two agents never own the same file in one wave.
- **Contract stubs.** When agents in one wave depend on each other's code, the coordinator first
  commits the shared signatures (e.g. `ExportSettings`) and agents build against them.
- **Merging.** The coordinator reviews each report and diff, merges the worktree branch into `main`,
  resolves `momenton.mixins.json` / `en_us.json` conflicts as the union of both sides, then runs
  `./gradlew build --offline --rerun-tasks` (all tests) and `checkReplay` on a real recording. A merge
  of two agents that both touched one class's signatures can break a test: always rerun the tests.
- **Root cause first.** Every observed bug is proven from logs, the recording (offline tools) or
  bytecode (`javap` on the merged jar) before it is fixed. Twice a plausible fix was wrong
  (`USAGE_HINT_CLIENT_STORAGE`, the 14 fps encoder reading); measure, then fix.
- **Testing loop.** The user runs `gradlew runClient`, tests in game and pastes log lines; the
  coordinator gives a numbered test list. The user prefers to batch in-game testing.
- **Rules.** No comments in new code (existing comments stay). No `org.lwjgl.opengl` anywhere
  (`checkNoDirectOpenGl`). No Co-Authored-By or any attribution in commits (user's global rule; it
  overrides any harness reminder). Commit on `main` only after a green build. Never commit the local
  `-Djavax.net.ssl.trustStoreType=Windows-ROOT` line in `gradle.properties` (CI is Ubuntu); stage files
  explicitly, never `git commit -a`.
- **Environment.** Windows, Git Bash. Builds run `--offline` (the sandbox cannot download; the user
  runs a networked build when a new dependency is needed). ffmpeg 9.0.2 is installed by winget at
  `C:/Users/<user>/AppData/Local/Microsoft/WinGet/Packages/Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-9.0.2-full_build/bin/ffmpeg.exe`
  (ffprobe beside it). Offline tools need Windows-style absolute paths:
  `./gradlew checkReplay --offline "-Preplay=C:/Users/.../run/momenton/recordings/<file>.momenton"`,
  `./gradlew runTool --offline -q -Ptool=<Name> "-Pargs='<file>' ..."` (see `tools/README.md`).
  Merged jar for `javap`: `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2/minecraft-merged-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar`.
- **Machine.** Intel Core i5-1235U (2P+8E, low power), Intel Iris Xe iGPU, 16 GB. Hardware encoder:
  Quick Sync (`h264_qsv`) works; NVENC and AMF do not.

---

## Current state

- Phases 0-2 complete and observed. Phases 3-7 written; much of it observed in five in-game runs on
  2026-10-10 (see below). Waves 0-6 merged on `main`.
- Last build (2026-10-10, after Wave 6): `./gradlew build --offline --rerun-tasks` green, 93 tests, 0
  skipped, 0 failed; `checkReplay` ALL PASS (Inspect, Setup, Motion, Swing, Boundary) on
  `2026-10-10_09-42-39`; 33 client mixins; no OpenGL imports.
- Last observed export (fifth run): 1080p60 Quick Sync, 7938 frames in 142 s (57 fps, about real
  time), plays correctly. Per frame: render ~7 ms, copy ~42 ms off-thread on 4 threads at ~470 MB/s
  aggregate (reading GPU-mapped memory is the limit). Wave 6 then added GPU NV12 conversion (3.1 MB
  instead of 8.3 MB per frame; estimate 80-100 fps, unmeasured).
- Recordings in `run/momenton/recordings/` (gitignored): `2026-10-10_09-42-39.momenton` (2646 ticks;
  combat with 38 mob deaths, 12 of them a chunk from the camera; block breaking; a chest; Nether and
  back; mic track; the main test file) and `2026-10-10_09-02-53.momenton` (1596 ticks; Nether at
  1142 and back at 1482; its mic entry predates `4d582d2`, so the offline tools reject it - re-zip a
  copy to check it). Exports land in `run/momenton/exports/`.

### Wave 6 (merged, unverified)

- **NV12 export** (`db77ec4`): `FrameGrabber` converts the frame on the GPU with
  `assets/momenton/shaders/core/nv12.fsh` (vanilla `screenquad` vertex shader, modelled on
  `TracyFrameCapturer`), flipped upright, BT.709 limited range with ffmpeg's own chroma filter
  (headless test: max error 1 per plane). Presets start with `%INPUT%` (`FrameFormat` fills it);
  `%FILTERS%` is gone. Output is tagged bt709/tv; old exports were BT.601 untagged, so colours shift
  slightly versus earlier exports. MKV lossless stays RGBA (yuv444p). Falls back to RGBA (logged) when
  the width is not a multiple of 4, the height is odd, or the pipeline is invalid. The "Exporting ..."
  line says `as NV12` or `as RGBA`; new log field `convert`.
- **Commentary in exports** (`e0e3c4a`): the mic goes in as a second audio track "Commentary" (game
  track "Game" stays default), aligned per clip from `micStartTick`, silence where the mic does not
  cover a clip; or mixed into one "Game and commentary" track (`amix normalize=0`). Export screen:
  game and voice volume 0-200% (0 drops the track), "Voice: Own track / Mixed in". The mix toggle is
  a static `ExportFinisher.mixCommentary` - move it into `ExportSettings` next time that changes.
- **Recording info and mic settings** (`4305061`): metadata stores `mods` (no Fabric API modules,
  builtins, loader or momenton) and `resourcePacks` (no server packs). The browser shows one warning
  line (red missing mods, yellow different mods or packs) with a detail tooltip; Play is never
  blocked. Settings button in the browser: commentary on/off (in sync with the toggle key), input
  device picker with fallback to the system default, level meter; saved to
  `<gameDir>/config/momenton.json`.

## Observed in game

- Phases 0-2: recording, playback, seeking, pause, browser, title-screen entry, dimension changes.
- `runClient` starts with every mixin (first run, 29 mixins; 33 now - re-check at next launch).
- Playback, seek and pause on the new build; block breaking with particles; replayed inventories.
- First-person sword: swing plus the dip after an attack (`resetTicksSince` events).
- Export end to end (fifth run): 1080p60 Quick Sync MP4, all 7938 frames delivered, ffmpeg finished
  3 s after the last frame, plays correctly; progress overlay and background finish did not hang.
- Dying mobs no longer shake in exports (simulation distance fix `c0abdad`/`ecace64`).
- ffmpeg discovered from the winget install.

## Not yet verified

- **Wave 6:** everything above (NV12 speed and colours, every preset, RGBA fallback at 1366x768,
  commentary track and mix mode, volume sliders, mod/pack warnings, settings screen and device
  picker).
- **Export details:** Esc mid-render and Cancel on the finishing screen (fix `107cc5b`: never waits on
  ffmpeg; cleanup thread deletes temporaries with retries); MP4 audio in sync at the end; WebM, MKV,
  potato presets; the failure screen; cursor sprite; HUD at the recorded relative size; window
  restored after export; 144 fps audio sync; edited multi-clip export with clean cuts.
- **Editor:** split/trim/reorder/delete/undo/redo/save/reload; `<` `>` one tick; HUD toggles; O on a
  Play replay; replayed GUIs shown under the editor (`e9de303`); editor returns after a loading screen.
- **Replay fidelity:** place/mining-crack/hit/eating/footstep/door sounds, nothing twice; chest item
  moves and cursor stack; chat draft; replayed screens ignore clicks and keys, `P` `[` `]` F10 work over
  them; cursor follows at another window size; portal warp; fire overlay; boat and horse with remount.
- **Wave 1:** `]` across a slot or hotbar change keeps it; perspective restored on stop; snapshot
  timing lines; finalise on write failure.
- **Mic:** live sync during replay, following `[` `]` `P`; recorder from a chosen device.
- **Older items:** swing restarts in bursts, paused replay freezes everything without shake, leaving
  playback by Disconnect/closing the window, third person and crouch, custom payload filter (needs a
  server channel no mod claims), recording HUD indicator over an open screen.
- **VulkanMod 0.6.3:** never tried (NV12 shader translation and readback are the risks).

## Known issues

- Every new mixin is a startup crash if its target is wrong (`defaultRequire = 1`); after any
  Minecraft or Yarn bump re-verify with `javap`/`genSources`.
- Mic recordings made before `4d582d2` fail the offline tools; the mod reads them.
- Other mods' HUDs export only if installed at record and export time and rebuildable from the
  replayed world. Iris shader animation is wall-clock driven, so it runs at real-time speed during a
  slow export (needs a compat hook as ReplayMod has). Iris and VulkanMod cannot be used together.
- Seeks whose snapshot-to-target span crosses a dimension still show the loading screen (needs an
  extra snapshot right after each dimension change).
- Export: after 600 render passes the chunk wait grabs anyway (a stuck chunk leaves a hole in that
  frame); a frame re-rendered while waiting gets extra screen-animation delta; wall-clock effects
  (enchant glint, caret blink) are not deterministic.
- Export audio: sounds that started before the first tick or a cut are not heard (loops restart); a
  sound can start up to a frame late.
- Export of recordings without `guiScale` in metadata keeps the viewer's GUI scale.
- The in-game reason ffmpeg did not exit after a cancel kill (fourth run) is unexplained; the render
  thread no longer waits for it.
- Seek into a stretch where a container was already open cannot show it (the snapshot does not
  re-send the open-screen packet). A replayed `OpenScreenS2CPacket` replaces the viewer's pause menu.
  Holding `]` over a replayed screen repeats the seek; bed chat replays as a plain chat screen.
- Mining crumb particles have no packet and do not replay; synthesised self-sounds use a fresh seed
  (variant may differ); the portal trigger sound is not recorded; boat paddles do not animate; a
  replayed player may double its water splash.
- Old recordings: attack-speed changes at item switches restart the sword dip a few ticks late, and
  spear jabs dip (new recordings carry the reset events).
- Render distance is a capture-time decision; view bobbing on the driven player is unchecked;
  sprinting is recorded but not applied; the driven player is never `onGround`.
- The tab list is not in the snapshot; `shouldTickTimeOfDay` is private (up to 1 s time drift); the
  rebuilt join keeps the original `seed` and `isFlat`; the `local = false` registry flag in
  `OfflineConnection` is unresolved; pause-menu frames are recorded as ordinary frames.
- Snapshot-boundary race residue: a packet whose stamp is read just before the boundary drain but
  queued just after is lost; one stamped `at+1` but drained is replayed twice. Closing it needs the
  stamp and the queuing to be atomic in `ClientConnectionMixin`.
- Snapshot capture runs every 500 ticks on the client thread (measured 1-11 ms; acceptable).

---

## Phase 0 - Foundations

Complete, observed.

## Phase 1 - Recording

Complete, observed. One box stays open:

- [ ] Packet filter: drop custom payloads for absent mods. Written; no session has exercised it

## Phase 2 - Playback

Complete, observed.

## Phase 3 - Replay fidelity

- [x] Replayed local player walks its recorded path; `LOCAL_PLAYER` vitals and items; selected slot
      and chat scroll; the attack needs no replay
- [x] Tools on Gradle (`checkReplay`, `runTool`), format tests in JUnit
- [x] Block break particles and sound from local prediction (observed); other self-effects unverified
- [x] Replayed inventory screens (observed)
- [x] First-person attack dip (`resetTicksSince` reset events)
- [ ] Seek state merge; snapshot-boundary race; perspective restore; finalise on failure
- [ ] Container contents after clicks, chat draft, screens close on time, viewer input ignored
- [ ] Cursor normalised (format 2) and only on replay screens
- [ ] Portal warp and fire ticks at TAIL; vehicle pose
- [ ] **Gate:** a recording with block breaking and placing, a chest, item moves, chat typing and a
      portal plays back right; also once on a local `runServer`

## Phase 4 - Export (video)

- [x] End to end: replay ticks drive the clock, fixed tick progress, terrain wait, non-blocking
      readback with drain, background finish, progress overlay, Quick Sync (observed, fifth run)
- [ ] NV12 GPU conversion with RGBA fallback; BT.709 tags; speed measured
- [ ] Presets (default, custom bitrate, potato, WebM, MKV lossless), `ExportFailedScreen`, cancel
- [ ] Export resolution and GUI scale rule, cursor sprite, window restored
- [ ] **Gate:** 30 s at 1080p60 plays in VLC and matches an OBS side-by-side; once under VulkanMod

## Phase 5 - Game audio

- [ ] Loopback render in sync at start and end (also at 144 fps); speakers silent during export
- [ ] **Gate:** audio in sync at the end of a 30 s export

## Phase 6 - Editor on vanilla Screens

- [x] `edit/` data model with JUnit (Clip, Timeline, TimelineResolver, UndoStack, Project, HudToggles)
- [ ] Timeline overlay, one-tick stepping, preview follows the edit, O and Edit button
- [ ] Replayed GUIs hosted under the editor
- [ ] Backward seeks without the loading screen (except across dimensions)
- [ ] HUD toggles; export of the edited clip list with clean cuts
- [ ] **Gate:** split into three clips, reorder, trim two, undo all, reload, play back in the edited
      order, HUD toggles immediate

## Phase 7 - Mic commentary

- [ ] Recorded to `audio/mic.wav` with `micStartTick`; played in sync during replay
- [ ] Second audio track in export, or mixed; volumes (Wave 6)
- [ ] Settings screen: on/off, device picker, level meter (Wave 6)
- [ ] **Gate:** an export carries game audio and commentary on separate tracks, both in sync

## Phase 8 - Hardening

- [ ] Full pipeline under VulkanMod 0.6.3 (capture, playback, NV12 export, readback)
- [ ] Recording overhead under 1 ms per tick; `FrameTrackWriter` per-frame cost
- [ ] Disk full, dimension change and server kick mid-recording
- [ ] Mismatched Minecraft version greyed out; mod/pack mismatch warnings (Wave 6)
- [x] JUnit for `ActionCodec`, `EntityMoveCodec`, `FrameTrack` sampling
- [ ] FFmpeg auto-download (pinned SHA-256); NVENC/AMF parameters measured on real hardware
- [ ] Iris compatibility (frame time driven by the export clock)
- [ ] The rest of the multiplayer matrix (dedicated server recording)
- [ ] Export at 4K60 from a 1080p session
- [ ] `README.md` final pass

---

## Next session

The user runs this pass with `gradlew runClient` (a new terminal after `gradlew --stop`) and reports
each step as pass or fail; on a failure, `run/logs/latest.log` (or the archived `run/logs/*.log.gz` of
that game session) and `run/momenton/export.log`.

1. Launch: no mixin error (33 client mixins).
2. **NV12 export speed.** Export `2026-10-10_09-42-39` at 1920x1080 60 fps, "MP4, hardware (Intel
   Quick Sync)". The log says `as NV12` with no "NV12 conversion shader" warning. Send back the
   `Export N%` lines and `Delivered ... in ... s` (last time 142 s, 57 fps; expect clearly faster).
3. **NV12 picture.** Upright; no stripes or shifted colours in the bottom third; HUD, inventory and text
   edges clean; colours match the game (slightly different from the previous export, which was
   BT.601). Mobs still die without shaking.
4. **Commentary.** VLC shows two audio tracks, "Game" and "Commentary"; the voice is in sync at the
   start and the end. Voice 0% leaves only Game; game 0% leaves only Commentary; "Mixed in" gives one
   track "Game and commentary".
5. **Presets.** One short export each: MP4 default (x264), custom bitrate, potato, WebM (Opus audio),
   MKV lossless (log says `as RGBA`). Each plays. 1366x768 says `as RGBA` and is upright.
6. **Cancel.** Esc mid-render: the window stays responsive, sound comes back, and within a second no
   `momenton-video-*`, `momenton-audio-*.pcm` or `momenton-mic-*.pcm` is left in `run/momenton/exports`;
   Task Manager shows no ffmpeg.exe. Then Cancel on the "Finishing video" screen: same.
7. **Failure screen.** Change `libx264` to `nosuchcodec` in the arguments: the failure screen shows the
   log; Back keeps the values.
8. **Edited export.** In the editor split the recording into three clips, reorder, trim two, export:
   one video of the summed length, clean cuts, commentary in sync across cuts.
9. **Editor.** Undo all, redo, Ctrl+S, close and reopen (restored); `<` `>` move one tick; HUD toggles
   hide immediately and persist; replayed inventory/chest/chat show under the timeline at the right
   moments; O on a replay opened with Play opens the editor.
10. **Browser and settings.** Select a recording: no warning line. Enable or reorder a resource pack:
    yellow "Recorded with different resource packs" with a tooltip. Settings: commentary on/off, pick
    a microphone, the meter moves when you speak; the setting survives a restart.
11. **New survival recording** with the mic on: break and place blocks, slow mining with one cancel,
    eat, open a chest and move items with a shift-click, type chat without sending then send, a
    portal, lava, a boat and a horse. Replay it: nothing heard twice, chest moves and chat text right,
    voice in sync after `[`, `]`, `P`, warp and fire overlay on time, vehicle carries the rider.
12. Optional: one run under VulkanMod 0.6.3 (no Iris): launch, replay, NV12 export upright with
    correct colours.

After the pass: fix what fails (root cause first), tick what passed, then Phase 8 hardening and the
phase gates.
