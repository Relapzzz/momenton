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
  2026-10-10 (see below). Waves 0-9 merged on `main`.
- Last build (2026-10-10, after Wave 9): `./gradlew build --offline --rerun-tasks` green, 93 tests, 0
  skipped, 0 failed; `checkReplay` ALL PASS (Inspect, Setup, Motion, Swing, Boundary) on
  `2026-10-10_09-42-39`; 47 client mixins; no OpenGL imports. All agent worktrees removed.
- The user's eighth-run recordings `2026-10-10_18-28-04` and `_18-30-32` (profile below; copies in
  `%TEMP%/mrec`) pass everything except the new command-tree check, which they predate.
- The user tests in their own Modrinth profile (VulkanMod, HeroBot, PvP client mods):
  `C:/Users/<user>/AppData/Roaming/ModrinthApp/profiles/Asmeus Optimized/` (`logs/latest.log`,
  `momenton/recordings/`, `mods/momenton-1.0.0.jar`). Their first recording there,
  `2026-10-10_17-58-32.momenton` (776 ticks, mace practice map, a HeroBot), predates Wave 8 and fails
  the new Setup check, as it should.
- Last observed export (fifth run): 1080p60 Quick Sync, 7938 frames in 142 s (57 fps, about real
  time), plays correctly. Per frame: render ~7 ms, copy ~42 ms off-thread on 4 threads at ~470 MB/s
  aggregate (reading GPU-mapped memory is the limit). Wave 6 then added GPU NV12 conversion (3.1 MB
  instead of 8.3 MB per frame; estimate 80-100 fps, unmeasured).
- Recordings in `run/momenton/recordings/` (gitignored): `2026-10-10_09-42-39.momenton` (2646 ticks;
  combat with 38 mob deaths, 12 of them a chunk from the camera; block breaking; a chest; Nether and
  back; mic track; the main test file) and `2026-10-10_09-02-53.momenton` (1596 ticks; Nether at
  1142 and back at 1482; its mic entry predates `4d582d2`, so the offline tools reject it - re-zip a
  copy to check it). Exports land in `run/momenton/exports/`.

### Wave 9 (merged, unverified): mod compatibility, packs, prediction, FOV

Eighth run (user's profile, two singleplayer recordings with rain, a HeroBot): skin layers, cape,
armor bar, bot items, rain, crits and totem pops all observed correct. Reported: op commands red in
replayed chat; asked for server packs, holograms, item names and compatibility with the profile's
mods. Evidence and fixes:

- Command tree (`COMMANDS`) absent from snapshots (decoded). `SessionSetup` keeps the last
  `CommandTreeS2CPacket` per connection; snapshots carry it (`535eb95`). Setup checks it.
- Server/world resource pack unloaded before every replay (user log 18:29:00, 18:31:55). Recording
  stores active packs as `resourcepacks/<uuid>.zip` + metadata `serverPacks`; playback loads them
  without prompt; pack push/pop packets dropped; export waits for the reload overlay (`a08a63c`,
  `1c43286`). Browser no longer flags WORLD packs as different.
- Snapshot hitch: chunk packet cache with dirty marks and a 5 s settle window (`535eb95`,
  `e995679`); log line `Snapshot rebuilt N of M chunks`. First design hooked vanilla `LightStorage`;
  replaced because the profile's ScalableLux overwrites the light provider.
- Recorded FOV and sprint never applied (code: no reader). `FovMixin` (priority 1500, after
  ok_zoomer) returns the frame's FOV for the world pass; sprint set at TAIL of the player tick
  (`1c43286`). Overlay and titles cleared after a snapshot (no "Press Shift to dismount").
- Client prediction: block changes synthesised as block updates (no ping lag; fake anchors);
  entities hidden by marlows-crystal-optimizer synthesised as destroyed (`6323741`).
- Holograms: the user's map has 32 `text_display`, 17 `item_display`, 4 `interaction` entities, all in
  snapshots with their data; plugin holograms use the same entities. Item names: hotbar slot is
  driven through vanilla `setSelectedSlot`, so the held-item name and tooltips take the vanilla path
  (not yet observed).

Mod audit of the profile (read-only, 58 jars) - remaining mismatches, not fixed:
- BetterHitreg (enabled): plays hit sounds, hurt flash and crit particles at click time and cancels
  the server's copies live; the recording holds both the synthesised and the server sound, so hits
  may sound twice and hurt flashes land a ping late in replays. Needs a mod-specific rule.
- ok_zoomer hides HUD and crosshair while zoomed; the FOV is now replayed but the HUD is not hidden.
- CrosshairAddons, animatium item-use equip dip, shieldfixes shield sounds: driven by local input
  that never runs in a replay.
- VulkanMod export (never run): `VkCommandEncoder.copyTextureToBuffer` may read before the frame is
  submitted (VulkanMod's own screenshot calls `Renderer.flushCmds()` first), the main target may not
  resize, and the RGBA fallback may swap red and blue (BGRA swapchain). Needs one export to see.
- AsyncParticles: `clearParticles()` from the export render path may race its async particle tick.

### Wave 8 (merged, unverified): snapshot parity with a real join

The seventh run (user's profile): players visible but the recorded player has no skin layers; armor
bar empty until later; the HeroBot's armor and held items invisible. Root cause from the recording and
javap: the snapshot sent spawn and tracked data only, and nothing for the recorded player, while a
real join plus vanilla pairing also sends attributes, equipment, passengers, leashes, and the
scoreboard, boss bars, tab header, border, weather, abilities, cooldowns and the player's own tracked
data and effects. The recording's stream also had 1550 team and 475 score updates aimed at teams and
objectives the replay never knew (below-name scores, sidebars, team colours).

- `9275c8e` (`WorldSnapshot`): attributes and equipment per living entity; the recorded player's
  tracked data (skin layers, main arm, absorption), attributes (armor bar) and status effects;
  passengers (holograms riding players, vehicles) and leashes after every spawn.
- `949f197` (`ServerStateSnapshot`, `OfflineConnection`, four `ServerState*Accessor` mixins): teams,
  objectives, display slots, scores; boss bars; tab header/footer; world border; rain state and
  gradients; abilities; item cooldowns. A seek clears scoreboard, boss bars, tab header, cooldowns and
  the player's effects before re-applying (objective ADD on an existing name throws). `tools/Setup`
  checks border/abilities/weather and objective-before-score on recordings with `player`.

Audit for the user's ban and performance questions: the recorder only reads inbound packets in
`ClientConnection.channelRead0` and sends nothing; playback is offline. Costs: every packet is
re-encoded on the netty thread before vanilla handles it (unmeasured), and each snapshot blocks the
client thread (22 ms at F9, 9 ms every 25 s in the user's log; a visible hitch mid-fight).

### Wave 7 (merged, unverified): players and skins

The sixth run (user's own instance, VulkanMod and their mod set) found: no other players on servers,
no Carpet/HeroBot fake players in singleplayer, and the recorder shown as Steve/Alex without cape, in
game and in the inventory. One root cause, proven in bytecode and in the recording (zero `ADD_PLAYER`
in `2026-10-10_09-42-39`): the tab list arrived before F9 and was never in a snapshot.
`ClientPlayNetworkHandler.createEntity` drops a player spawn with no `PlayerListEntry`, and
`AbstractClientPlayerEntity.getSkin` falls back to `DefaultSkinHelper` without one.

- `dad5b9e`: `WorldSnapshot` sends one `PlayerListS2CPacket` with every tab entry (profile with skin
  textures, game mode, listed, latency, display name, hat, order) before any spawn; entries set via
  the new `PlayerListS2CPacketAccessor`. `tools/Setup` checks it on recordings that have `player`.
- `02cc001`: metadata `player` (server-assigned UUID and name); playback builds the replayed player
  from it instead of the viewer's profile (dev usernames change every launch, so the UUID never
  matched). Old recordings fall back to the viewer and keep the bug: record a new one to test.

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
- Runs under VulkanMod alongside the user's own mod set (user report, sixth run); export details
  under VulkanMod not yet reported.

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
- **Wave 7:** players, Carpet bots, own skin and cape in a new recording; tab list after a seek.
  Seventh run: players and bots visible with skins (layers missing, fixed in Wave 8).
- **Wave 8:** observed in the eighth run: skin layers and cape on the recorded player, armor bar,
  bot armor and held items, rain. Still to see: below-name scores, sidebar, team colours, boss bars,
  tab header/footer, border, pearl and shield cooldowns after a backward seek; holograms riding
  players on a server.
- **Wave 9:** commands not red; server pack in replay and export; `Snapshot rebuilt N of M` small after
  the first; seek after building and lighting changes shows the right world; sprint/speed/bow/zoom FOV;
  sprint particles; no dismount prompt; blocks appear with their particles on a server with ping;
  crystals vanish on hit with the crystal optimizer; held-item names.
- **VulkanMod:** launch and use reported fine; NV12 export upright with right colours unchecked.

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
  the driven player is never `onGround`.
- Server packs pushed mid-recording are not captured (only those active at F9). A recording with a
  client-only block (fake anchor) or any modded block fails to decode that chunk without the mod.
- The first snapshot at F9 still costs a full build (22-29 ms); the per-packet re-encode on the netty
  thread is unmeasured.
- A title showing at a snapshot is not restored after a seek (titles are cleared, not re-sent).
- A cooldown running at a snapshot restarts its sweep from full (the packet has no start tick); a boss
  bar mid-animation is captured up to 100 ms behind.
- Tab entries added after a snapshot survive a backward seek past it (tab list only, harmless);
  `shouldTickTimeOfDay` is private (up to 1 s time drift); the
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

Copy `build/libs/momenton-1.0.0.jar` into the user's profile `mods/` and make NEW recordings (older
ones lack the command tree, packs and prediction). The coordinator reads the profile's
`logs/latest.log` and `momenton/recordings/` directly.

1. Launch: no mixin error (47 client mixins).
2. **Commands:** type an op command in chat while recording; in the replay it is not red.
3. **Resource pack:** record on the practice map (it has a world pack) or a server with a pack; the
   replay shows the pack's textures; the log shows `Snapshot rebuilt N of M chunks` with N small after
   the first snapshot. Leave the replay: the pack unloads.
4. **Seek:** place and break blocks, place a torch in a dark spot, wait 30 s, seek back and forward
   across it: blocks and light right every time.
5. **FOV and sprint:** sprint, drink speed, draw a bow, zoom with ok_zoomer; replay first person
   matches; third person shows sprint particles.
6. **Prediction:** on a server with ping: placed blocks appear with their sound; crystals vanish on
   hit; anchor use shows the fake anchor.
7. **Riding:** start or seek a replay while riding: no "Press Shift to dismount".
8. **Server UI:** sidebar, below-name, team colours, boss bar, tab header, holograms after a seek.
9. **Export under VulkanMod:** one short MP4 export; upright, right colours, not one frame behind,
   no loading screen in the first frames. Send `momenton/export.log`.
10. **BetterHitreg:** hit a mob or bot; in the replay, does each hit sound once or twice?

Then: BetterHitreg rule, ok_zoomer HUD hiding, VulkanMod export issues from step 9.
