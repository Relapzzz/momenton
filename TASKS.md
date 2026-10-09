# Momenton - Tasks

**This is the living state file. Read it first, update it last.**

`IMPLEMENTATION_PLAN.md` holds the design and the reasoning; it changes rarely. This file holds what
is done, what is next, and what is broken; it changes every session.

---

## Start here (new session)

1. Read **Current state** and **Known issues** below.
2. Read the section of `IMPLEMENTATION_PLAN.md` for the phase you are on. Do not skim the "three
   things that make this work" section - most design mistakes on this project come from missing one
   of those three.
3. Run `gradlew.bat build` to confirm the tree is green before changing anything.
4. Work the next unchecked box in the current phase, in order.

**Right now that means judging the swing, the pause and the disconnect fix on a fresh playthrough,
then block interaction.** Three boxes are done and observed: the replayed player walks its path,
carries its items, and its hotbar and chat scroll follow. The attack box is closed - not by the fix
written for it, which turned out to be answering a question 1.21.11 does not ask. Phase 2 is closed.
See **Next session** at the end of this file.

## Before ending a session

1. Tick what you actually finished. Do not tick anything you have not observed working.
2. Update **Current state**, **What works**, **Not yet verified** and **Known issues**.
3. Add anything surprising to **Decisions made during build** so it is not re-litigated later.
4. Run `gradlew.bat build` and record the result.

**Rules for this file:** a box is ticked only when the behaviour has been *observed*, not when the
code has been written. Anything written but unverified goes in **Not yet verified** instead - that
distinction is what makes this file trustworthy after a session switch.

---

## Current state

**Phase 0 (Foundations): complete and verified.**

**Phase 1 (Recording): complete and verified.** See the Phase 1 checklist. One of the four packet
filter rules turned out to be wrong and was removed; one remains unprovable without a session that
exercises it.

**Phase 2 (Playback): complete, gate observed.**

**Phase 3 (HUD and GUI reproduction): four boxes done, the last one written and awaiting a
playthrough.** The replayed player now walks its recorded path, carries the items it carried, changes
hotbar slots, and scrolls chat.

**The attack replay was a wrong answer to a real question, and measuring the recording is what showed
it.** A playthrough reported the swing still looking wrong and the crosshair cooldown still filling
while the replay was paused, so both were traced rather than patched, and the trace went somewhere
else entirely. `ActionCodec.ATTACK`, `ClientPlayerInteractionManagerMixin` and the `player.attack`
call in `UiStateDriver` are **gone**: on 1.21.11 that local call produces nothing at all against a mob.
`attack` gates its whole effects block on `target.sidedDamage(...)`, which on a `ClientWorld` is
`Entity.clientDamage` - overridden by eight classes, none of them `LivingEntity` - so it returns false
and the knockback, sweep, particles and sounds are all skipped. The one line that survives,
`playAttackSound`, reaches `ClientWorld.playSound(source, ...)`, which plays only when
`source == client.player`, and the caller passes `null`. `tools/Effects` then settled it from the
recording itself: **every impact effect is already on the wire** - `sweep_attack` particles at ticks
412 and 433, `ANIMATE` crit at 91 and 1625, a `damage_indicator` and a `DAMAGE_EVENT` on every hit,
and the attack sounds as ordinary `SOUND` packets. Nothing needed reconstructing.

**What was actually wrong with the swing was the edge test, and it was measurable.**
`LivingEntity.swingHand` restarts a running swing in place - `handSwingTicks = -1` while
`handSwinging` stays set - whenever the running one is at least half done, so a burst of clicks is one
rising edge followed by several counter drops. `UiStateDriver` only watched the rising edge.
`tools/Swing` counts **9 of 46 swing starts** in `2026-09-01_22-14-15.momenton` lost that way: the
replayed arm played the first swing of each burst and then held still. Both kinds of start are
replayed now, and `swingHand`'s own half-done guard accepts or rejects each exactly as it did live.

**And the pause froze the replay without freezing the world.** `MinecraftClient.paused` is only ever
set for an integrated server and a replay has none, so `world.tickEntities()` kept running under a
frozen picture: `PlayerEntity.tick` went on doing `ticksSinceLastAttack++` and `tickHandSwing`, which
is the crosshair cooldown filling and the arm finishing its swing while paused. `MinecraftClientMixin`
now sets vanilla's own flag, so a paused replay freezes everything a singleplayer pause freezes.

**Setting that flag at the head of `tick` was wrong, and the playthrough said so immediately: entities
and the held item shook violently.** Two lines below where `render` computes `paused`, it passes it to
`RenderTickCounter.tick`, and *that* is what pins tick progress - `Dynamic.tickPaused` saves
`tickProgressBeforePause` and `getTickProgress` returns it unchanged. Setting the flag at the head of
`tick` is overwritten before that call, so the world stopped ticking while tick progress kept sweeping
0 to 1, and every interpolated value oscillated between its last and current tick twenty times a
second. The injection moved to immediately after `render`'s own assignment, which is the only point
where it reaches both readers. **A pause is two things - stop the tick, and stop the clock that
interpolates between ticks - and doing one without the other is worse than doing neither.**

**A crash on leaving playback, and the guard for it was one step too late.**
`ClosedChannelException` out of `OfflineConnection.apply`, on the render thread. The pause menu's
Disconnect tears the session down in two steps: `ClientWorld.disconnect` closes the connection, and
only then does `MinecraftClient` null the world - and in between it renders, and rendering ticks. So a
tick landed in `tickPlayback` with a world still present and a closed channel behind it.
`ReplaySession.onClientTick` guarded on `client.world == null`, which is the half that happens second.
It now checks `connection.isOpen()` as well, and `stop()` skips its own disconnect when the connection
is already closed rather than re-entering vanilla's teardown.

Moving the
replayed player turned out to begin on the recording side: the path did not exist. The plan and this
file both said it was already in `LOCAL_PLAYER` / `ENTITY_MOVE`; it was not.
`EntityPositionTracker` skipped `entity.getId() == localPlayerId` and `LocalPlayerWriter` only ever
wrote vitals, hotbar and offhand. It is recorded now, as an ordinary entity in the `ENTITY_MOVE` track,
and **observed working**: the player walks its path, head and body turn, legs animate, and it crouches.

**Four defects surfaced getting there, and three were the same mistake: a value the live client
updated at one point in the frame or tick, rebuilt at another.** Head yaw written before
`baseTick` captured its previous value; look direction rebuilt per tick when it was per frame; sneak
read off an `Input` that playback had emptied; and the camera rotation put back on the player without
undoing third-person front's inversion. The lesson is in the decisions section and it is the one to
carry through the rest of Phase 3 - *where* a value is applied is as load-bearing as its value.

**Then a superflat world exposed a chunk bug that four earlier recordings had hidden.** The replay had
a permanent hole at the edge of view: `WorldSnapshot` scanned a radius of the render distance while the
server streams one chunk further and the client's map holds three further still. Diagnosed by measuring
the recording rather than trusting a count that matched the assumption - see the decisions section,
because the reasoning error there is more reusable than the fix.

A replay reconstructs its world, plays at 1x with the recorded camera, seeks, and pauses. Observed
over six runs on real recordings: terrain and mobs render, entities move, the camera walks the
recorded path (121 blocks of travel, 328 degrees of turning) smoothly, local input is dead, and it
returns to the title screen at the end. Zero chunk rejections, no errors in the log. On the last run,
`[` and `]` both landed correctly and instantly, `P` froze playback, and the browser listed the
recording with the right duration and date.

**Confirmed since:** `]` no longer flashes the loading screen, `P` freezes the camera cleanly, and
the recording HUD behaves. `[` still flashes, which is expected - see Known issues.

**Then a dimension-change recording broke playback, and the cause was a Phase 1 filter rule.**
Entering the Nether left the replay on the terrain screen until the run ended. Diagnosed offline, not
guessed: `PacketFilter` dropped every `PlayerPositionLookS2CPacket`, which is exactly the packet a
server uses to place the player in the new world, and `ClientChunkLoadProgress.LoadChunks.isReady`
waits on `worldRenderer.isRenderingReady(player.getBlockPos())`. With the player left at its
Overworld coordinates that never becomes true, so the screen held for its full 30-second timeout
while the replay ran on behind it and then ended into the title screen. The drop rule is gone, and
`tools/Setup` now checks every dimension change for a respawn, a player position after it and an
`INITIAL_CHUNKS_COMING` after it - it fails on the old recording and is why the diagnosis is certain.

**Both crossings then replayed correctly**, and the high-altitude terrain problem went with them - it
was the same missing packet. What the crossings exposed instead is Phase 3's actual subject: the
portal warp never plays and the fire overlay arrives late. Both are the local client's own state,
which the wire either never carried or carried a tick behind what the player saw.

Getting here took four failed runs, and each failure was a different piece of state that existed
before F9 and was therefore missing from the recording. The dimension-change bug is the same shape
with a different cause - state the client needs that the recording does not carry - so the pattern is
worth remembering because Phase 3 will hit it again:

1. **The registry manager** - recorded packets could not even be decoded. Fixed by `setup.bin`.
2. **The world itself** - the join builds an *empty* world. Fixed by `record/WorldSnapshot`.
3. **The chunk-map centre** - all 121 snapshot chunks were discarded on arrival with `Ignoring chunk
   since it's not in the view range`. Fixed by sending the recentre first.
4. **The camera** - recorded actions played at their recorded positions while the viewer drove a
   separate player, so it looked like an invisible player at work. Fixed by `PovDriver`.

Each is now checked offline by `tools/Setup`, so none can regress silently.

The fifth was the player's own **inventory**, found the same way and fixed the same way - a snapshot
packet, now checked by `tools/Setup`. That closes the pattern for everything Phase 3 has needed so far.

`run/momenton/recordings/` holds two, both ALL PASS on `Verify`, `Setup`, `Inspect`, `Motion` and
`Swing`:

- `2026-09-01_22-14-15.momenton` - 1752 ticks, 4 chunks. **The one that matters.** It is the combat
  recording: 46 swing starts of which 9 are mid-swing restarts, 46 cooldown resets, 5 hits on mobs at
  ticks 89, 311, 411, 432 and 1624, with two sweeps (412, 433) and two crits (91, 1625) in the packet
  stream. **It also crosses to the Nether at tick 1055 and back at 1265**, so it is the dimension-change
  regression case that had been missing since `2026-08-31_19-20-19.momenton` was deleted. It was made
  by the build that still wrote `ATTACK` actions, so it carries type-5 actions that playback now steps
  over - which is the compatibility case, and it works because `applyState` ignores types it does not
  know.
- `2026-09-01_21-53-17.momenton` - 1782 ticks, 20 078 frames. The `UI_STATE` recording: combat in it
  (17 `DAMAGE_EVENT`, 22 `LEVEL_PARTICLES`, 538 `SOUND`), 26 swing starts and no restarts among them,
  which is why it never showed the swing bug.

Neither carries a third-person or crouching stretch. Those were verified in game once and no recording
on disk can re-verify them.

`2026-08-31_23-04-39.momenton` is gone from the folder.

Last build: `gradlew.bat build` **succeeded**, producing `build/libs/momenton-1.0.0.jar`. All five
offline checks - `Verify`, `Setup`, `Inspect`, `Motion`, `Swing` - are ALL PASS on both recordings.

`tools/` holds the offline checks for a `.momenton` file - they run against the mod's compiled classes
without launching the game, and they are how everything in **What works** below was observed. Read
`tools/README.md` before writing a new one; the one you want probably exists.

## What works

- The project builds and remaps cleanly.
- **`gradlew.bat runClient` launches the game with every client mixin present and no errors**
  (observed). `GameRenderer` loads during startup, so `GameRendererMixin` and `GameRendererInvoker`
  are proven to apply - `defaultRequire = 1` would have crashed otherwise. The three
  `mixin.client.playback` ones are proven by playback behaving: the camera follows the recording and
  WASD does nothing. **Eight now** - `ClientPlayerInteractionManagerMixin` went with the attack
  action. `ClientPlayerEntityMixin` is proven too: the replayed player walked its recorded path,
  which is the injection doing its job.
- **A recording plays back and seeks** (observed, `2026-08-31_17-58-19.momenton`, 1244 ticks). The
  camera walks the recorded path smoothly, terrain and mobs render, `[` and `]` land where they
  should and do so instantly, `P` freezes it, and it returns to the title screen at the end. The
  browser lists it with the right duration and date. This is the Phase 2 gate.
- **A replay crosses dimensions** (observed, `2026-08-31_19-20-19.momenton`). Overworld to Nether at
  tick 96 and back at 876, both played through without stopping, and a seek across a crossing lands
  in the right dimension.
- **`Recorder.rejoin` provably rewrites the join** (observed, `2026-08-31_18-49-10.momenton`). The
  session joined the Overworld and crossed into the Nether at tick 326; `snapshot_0000.bin`'s join
  names `minecraft:overworld` and `snapshot_0001.bin`'s, taken at tick 500, names
  `minecraft:the_nether`. This is the first recording that separated `rejoin` from a no-op, so a seek
  into the Nether half rebuilds the Nether.
- **Playback is reachable from the title screen** (observed). The **Replays** button opens the
  browser with no world loaded, and Play from there goes straight into the replay.
- **The four Phase 1 mixin targets remap to real intermediary names** (observed in the built jar):
  `class_2535.method_10770` (`ClientConnection.channelRead0`), `class_338.field_2066`
  (`ChatHud.scrolledLines`), `class_757.method_3196` (`GameRenderer.getFov`). Loom fails the build if
  a target does not resolve, so these names are current for 1.21.11+build.6.
- **The container format round-trips** (observed, standalone harness against the compiled classes,
  no game involved): varint framing; action write/read preserving type, absolute tick and payload;
  chunk rolling every 500 ticks producing `chunk_NNNN.bin` / `ui_NNNN.bin` pairs with a matching
  index in `metadata.json`; `frames.bin` exactly `56 * framesRendered` bytes; metadata surviving JSON;
  staging directory removed after zipping; a stale out-of-order action clamped rather than written as
  a negative delta.
- **Two real singleplayer sessions recorded and parsed back** (observed). F9 started and stopped
  each, the writer thread finalised the zip, and reading every track back gives, for the second
  (fixed-build) recording:

  | Track | Contents |
  |---|---|
  | `chunk_0000` | 13 590 `PACKET`, 496 `ENTITY_MOVE`, 1 `LOCAL_PLAYER`, ticks 0-499 |
  | `chunk_0001` | 4 739 `PACKET`, 193 `ENTITY_MOVE`, 1 `LOCAL_PLAYER`, ticks 500-692 |
  | `ui_0000` / `ui_0001` | 58 `UI_STATE` actions |
  | `frames.bin` | 6886 records over 688 distinct ticks - ~10 frames per tick, i.e. ~200 fps |

  Ticks are non-decreasing in every track, tick progress stays in `[0,1)`, camera poses are finite,
  and FOV ranges 70-80.5. Packet re-encoding works: ~18 000 packets round-tripped through the
  captured `DynamicRegistryManager` with no failures, and the first recording did the same for
  ~35 000.
- **The UI track reads back as a coherent story** (observed, second recording): hotbar slot 2 at
  tick 0, attack cooldown ramping 0.2 to 1.0 while swinging around ticks 94-105, chat screen open
  111-149, another screen 435-534, slot change to 4 at 559. Change-gating works - 58 actions over
  693 ticks.
- **Bundle expansion fires** (observed). Vanilla `EntityTrackerEntry` wraps entity pairing data in a
  `BundleS2CPacket`, and the recording contains 37 `ADD_ENTITY` and 66 `SET_ENTITY_DATA` actions
  while packet id 0 (`BUNDLE`, i.e. the delimiter) never appears. Bundles arrived and were unpacked.
- **The signed-chat rewrite fires** (observed). `PLAYER_CHAT` (id 63) is absent and a single
  `SYSTEM_CHAT` (id 119) sits at tick 149 - exactly the tick the UI track shows the chat screen
  closing. Its payload is `{translate: "chat.type.text", with: [<sender name with suggest_command
  and show_entity>, "hello"]}`, which is `serializedParameters().applyChatDecoration(content)`
  written out as a `GameMessageS2CPacket`.
- **Packet ids in a recording can be named offline**, which is what made the two rules above
  checkable. `NetworkStateBuilder` assigns S2C play ids by registration order, so
  `javap -p -c net.minecraft.network.state.PlayStateFactories` and numbering every
  `getstatic <X>Packets.NAME:Lnet/minecraft/network/packet/PacketType;` in the S2C lambda from 0
  gives the map. It must include the `CommonPackets` / `CookiePackets` / `PingPackets` entries
  interleaved among the `PlayPackets` ones - 139 ids in 1.21.11+build.6; skipping them shifts every
  id above 23 and produces plausible-looking nonsense.
- imgui-java 1.92.7.1 is nested as jar-in-jar; both nested jars carry valid generated
  `fabric.mod.json` entries.
- `checkNoDirectOpenGl` runs as part of `check` and passes (the GLFW import in `MomentonKeybinds` is
  fine - the rule is about `org.lwjgl.opengl`).

## Not yet verified

- **Swing restarts.** Written and not yet seen in game. The defect is measured, not guessed -
  `tools/Swing` counts 9 of 46 starts in `2026-09-01_22-14-15.momenton` that the old rising-edge rule
  dropped - but nobody has watched a burst of clicks replay since the fix.
- **A paused replay freezing the world.** Written and not yet seen. `MinecraftClientMixin` sets
  `MinecraftClient.paused`, and Loom remapped the shadow field to `field_1734`, which is `paused` in
  the mappings - so the target resolves. Whether the crosshair cooldown actually stops, and whether
  anything playback needs turns out to be gated on that flag, is the playthrough's job.
- **That removing the attack replay costs nothing visible.** The reasoning is proven from the 1.21.11
  sources and the recording carries every effect on the wire, but nobody has watched a hit replay
  since the removal. This is the one to look at first, because it is a deletion.
- **That leaving playback no longer crashes.** `ClosedChannelException` on Disconnect is fixed by
  checking the connection rather than the world, and the reasoning is read straight off the stack
  trace, but the fix has not been through the pause menu since.
- **Third person and crouch have no regression case.** Both were observed working, and no recording on
  disk carries either any more. Dimension changes do have one again -
  `2026-09-01_22-14-15.momenton` crosses twice.
- **The browser row lost its dimension column after it was last seen.** It now reads
  `world   m:ss   date`; the dimension list was noise. "Unknown world" for recordings made before the
  name was stored.
- **One packet filter rule is still unexercised, and absence is not evidence.**
  `CUSTOM_PAYLOAD_S2C` (id 24) is absent from every recording, but no mod claimed a channel and
  recording started mid-session, so nothing would have produced one. Proving it needs a server
  sending a payload on a channel no installed mod claims. (The `PLAYER_POSITION` rule is gone - it
  was wrong, see Current state.)
- **The recording HUD indicator was seen, but nothing else about it is checked.** No one has
  confirmed it disappears on stop or that it survives an open screen.
- **No ImGui class has ever been loaded.** The launch proves Fabric *accepted* the nested jars, not
  that the binding and its Windows natives actually initialise. That is a different failure mode
  (JNI native extraction and loading) and Phase 4's spike is the real gate.

## Known issues

- **Non-semver nested jars - downgraded, likely benign.** The build warns `(1.92.7.1) is not valid
  semver` for both imgui artifacts, because imgui-java uses a four-component version. A clean
  `runClient` shows Fabric Loader tolerates it. Keep in mind only if ImGui misbehaves in Phase 4; the
  fix would be pinning a three-component imgui release.
- `momenton.mixins.json` sets `injectors.defaultRequire = 1` and `overwrites.requireAnnotations =
  true`. A mixin whose target has moved is a hard startup crash, not a silent no-op. Expect this after
  any Minecraft or Yarn bump; re-run `genSources` and re-verify targets.
- Yarn names in `IMPLEMENTATION_PLAN.md` were verified against the 1.21.11 javadocs, but mappings
  drift. Verify against `genSources` output before writing any mixin. The selected-slot accessor on
  `PlayerInventory` is the known mover - it is `PlayerInventory.getSelectedSlot()` in +build.6.
- The first build on a clean machine needs network access: the Loom plugin is `1.17-SNAPSHOT` and
  resolves from maven.fabricmc.net at configuration time.
- **A seek that lands inside a dimension crossing shows entities before terrain, and that is
  faithful.** In `2026-08-31_19-20-19.momenton` the return to the Overworld respawns at tick 876 and
  the 170 chunks arrive over ticks 878-892 - about 0.7 seconds. A seek to 876 rebuilds the world only
  as far as the recording had built it by 876, which is what the player saw live. It fills in as soon
  as playback advances. Only treat it as a bug if terrain stays missing while playback runs; then get
  `run/logs/latest.log` and check for `Ignoring chunk` lines.
- **A backward seek still flashes the terrain-loading screen.** It has to load a snapshot, the
  snapshot leads with a join, and a join means `MinecraftClient.joinWorld`. Removing it needs
  playback to reuse the existing `ClientWorld` rather than rejoin - a much larger change, and one
  worth doing before the Phase 5 scrubber, which will seek constantly.
- ~~**"Terrain is only visible where I walked" is faithful, not a bug.**~~ **That verdict was wrong.**
  It was a real bug and it is fixed. The reasoning that produced the wrong answer is worth keeping,
  because the mistake is easy to repeat: `snapshot_0000.bin` held **121** chunks, which is exactly
  `(2*5+1)^2` at `renderDistance:5`, and that looked like proof the snapshot captured everything.
  It proved nothing - `WorldSnapshot.addChunks` scanned a radius of 5, so 121 was the *most* it could
  ever have returned. The count was a measurement of our own loop, not of the world.
  **Measuring the recording instead settled it:** decoding the chunk x/z out of every streamed
  `LEVEL_CHUNK_WITH_LIGHT` and comparing it to the chunk-map centre at that tick showed the server
  sends at Chebyshev distance **6** (59 of 69 packets), while the snapshot reached only 5. The outer
  ring - 48 chunks of the 169 the client held - was in no snapshot, and nothing re-sends it, so the
  replay had a permanent hole at the edge of view that showed whenever the camera turned towards it.
  Fixed by scanning `max(2, viewDistance) + 3`, which is `ClientChunkManager.getChunkMapRadius`, so
  the snapshot now takes whatever the client's map is actually holding. `tools/Setup` checks that a
  snapshot reaches at least as far as the stream does, and fails every recording made before the fix.
- **Render distance is still a capture-time decision.** Separate from the bug above: a recording can
  never contain more world than the client was sent, so at `renderDistance:5` the replay shows a small
  disc however correct the snapshot is. Raise it before F9 for anything meant for export. The cost is
  quadratic - radius 12 is ~625 chunks per snapshot, and there is one snapshot per 500 ticks.
- **View bobbing may not survive a driven player, and nobody has looked.** `GameRenderer.bobView`
  reads `AbstractClientPlayerEntity.getState()`, a `ClientPlayerLikeState` fed once per tick from the
  player's position *and velocity*. The position half is now correct; the velocity half is zeroed
  every tick by the driver. Bobbing is not in `frames.bin` either - it is a matrix transform applied
  after the camera - so if it is dead, the replay is missing it entirely. This belongs to the Phase 3
  OBS comparison; check it there before inventing a fix.
- ~~**The player's inventory is not in `WorldSnapshot`.**~~ **Fixed, unverified.** It was the fifth
  instance of the "state that existed before F9" pattern - the inventory arrives as
  `CONTAINER_SET_CONTENT` before recording starts and is never resent - and the reason a replay drew
  an empty hotbar and empty hands. `WorldSnapshot` now sends an `InventoryS2CPacket` on sync id 0, and
  `tools/Setup` checks for it.
- **Sprinting is recorded and not applied.** `FLAG_SPRINTING` has been in the frame flags since Phase
  1. It would go in through the same `PlayerInput` the sneak fix uses, but sprint's visible effects
  (FOV, sprint particles) are respectively already in `frames.bin` and gated on `onGround`, which a
  driven player never has. Left alone deliberately.
- **The driven player never calls `Entity.move`, so it has no footsteps and is never on the ground.**
  Zeroing the velocity is what stops physics fighting the recorded path, and the cost is that
  `move` - which is what plays step sounds, spawns sprint particles and sets `onGround` - never runs.
  Invisible until Phase 6, where game audio is re-rendered from the replayed world and a silent walk
  would be obvious. `onGround` is not recorded; recording it is the cheap fix if it turns out to
  matter before then.
- ~~**A recorded sneak does not replay.**~~ **Fixed, unverified.** The cause was more specific than
  the note said: `ClientPlayerEntity.isSneaking()` is an override that returns
  `input.playerInput.sneak()` and reads no flag at all, so silencing the input made the replayed
  player permanently un-crouched. Sneak is now a frame flag, fed back through a real `Input`.
- **The tab list is not in the snapshot.** Playback logs `Ignoring player info update for unknown
  player <uuid> ([UPDATE_LATENCY])` because `PlayerListS2CPacket` entries arrived at join, before F9.
  Harmless for a singleplayer replay - it only costs the tab list and player name tags - but it is
  the same class of gap as the chunks were, and multiplayer will make it visible. Add the player list
  to `WorldSnapshot`.
- **The snapshot is captured on the client thread during F9.** A full view distance is up to ~600
  chunk packets built and encoded in one go, so expect a hitch when recording starts and several MB
  of tick-0 actions. Measure it in Phase 8 against the "under 1 ms per tick" target, which this
  deliberately does not meet on its first tick.
- **`ClientWorld.shouldTickTimeOfDay` is private with no getter**, so the snapshot's
  `WorldTimeUpdateS2CPacket` always says time advances. Recorded `SET_TIME` packets arrive about once
  a second and correct it, so the worst case is a second of drift on a `doDaylightCycle false` world.
- **The rebuilt game join keeps the original `seed` and `isFlat`.** Neither is readable back from
  `ClientWorld` - `flatWorld` is a private field on `ClientWorld.Properties` with no getter. Both only
  affect biome blend and sky rendering, and both are wrong only if recording starts in a dimension
  whose flatness differs from the join dimension's. Revisit if the Phase 3 OBS comparison shows a
  wrong sky.
- **Playback will pass `local = false` to `ClientRegistries.createRegistryManager`.** The offline
  connection is not a `LocalChannel`, and that flag decides whether non-synced tags get applied. A
  singleplayer recording applied them and its replay would not, unless `OfflineConnection` reports
  itself as local. Decide this when writing that class, not after.
- **`RenderTickCounter.Constant` has a package-private constructor.** Phase 3 and Phase 7 both need
  to build one per output frame. That needs an access widener or another accessor mixin; nothing in
  Phase 1 depends on it.
- **Pause menu frames are recorded as ordinary frames.** `Recorder.onClientTick` hangs off
  `ClientTickEvents.END_CLIENT_TICK`, which keeps firing while the game is paused, so the recorded
  tick advances with the world frozen and the frame track gets repeated poses. Still untested: the
  one non-inventory screen in the second recording (ticks 435-534) did **not** pause anything -
  packet density held at ~23/tick across it - so no recording has yet covered a real pause. Revisit
  if it looks wrong in the Phase 3 OBS comparison.

## Decisions made during build

Design decisions live in `IMPLEMENTATION_PLAN.md`. This section is only for things discovered while
building that changed the plan.

- **Phase 0:** moved `checkNoDirectOpenGl` forward from Phase 8. A guard that runs from the first
  build prevents the violation; one added at the end only reports it after the code exists.
- **Phase 0:** dropped the `contact` block from `fabric.mod.json` entirely rather than invent URLs.
  The template's pointed at fabric-example-mod and there is no repository for this project.
- **Phase 1: the zip is written at stop, not incrementally.** `ZipOutputStream` holds one entry open
  at a time, but the chunks, the UI tracks and `frames.bin` all grow together, so a live zip is not
  possible. `ReplayWriter` streams loose files into a `.<name>.staging/` directory and zips them on
  stop. This serves the original requirement better than a live zip would: a crash leaves every
  completed part on disk rather than a truncated archive.
- **Phase 1: chunk tick deltas are relative to the chunk start, not to tick zero.**
  `ActionCodec.Reader` therefore takes a `startTick`, seeded from the chunk index in `metadata.json`.
  Found by the format harness; it would otherwise have surfaced as every chunk after the first
  replaying at the wrong time in Phase 2.
- **Phase 1: packets are drained on the client thread, not submitted from netty.** The netty thread
  filters, encodes and queues; the client thread drains that queue at end of tick and submits one
  ordered batch to the writer. Letting netty submit directly would interleave ticks and produce
  negative deltas. `ReplayWriter.write` also clamps deltas at zero, because a packet can still be
  queued a moment after the client thread has moved on.
- **Phase 1: two private members needed accessors.** `GameRenderer.getFov` and `ChatHud.scrolledLines`
  are both private in 1.21.11, so `GameRendererInvoker` and `ChatHudAccessor` exist. FOV is recorded
  because sprinting and speed effects change it, and a replay that ignores it does not match - the
  first recording shows it moving 70 to 80.5, so this was worth doing.
- **Phase 1: `ClientConnection.channelRead0` is not client-only.** In singleplayer the integrated
  server has its own `ClientConnection` on the same local channel, so the hook fires for serverbound
  packets too. Filter on `packet.getPacketType().side()`, not on the connection. Anything else
  hooking this class later needs the same guard.
- **Phase 2: the recorder now buffers setup packets on every connection, not only while recording.**
  The alternative was re-deriving the registry sync from the client's built registry manager, which
  does not round-trip - the client keeps the result, not the input. See `IMPLEMENTATION_PLAN.md`. The
  cost is a few MB retained per session; that is the price of F9 being retroactive at all.
- **Phase 2: the snapshot is packets, not a format.** Chunks, entities, time and position are stored
  as the clientbound packets a server would have sent, written as ordinary tick-0 actions. The
  playback side needed nothing: same codec, same handlers, same everything. A bespoke snapshot format
  would have needed a reader, a writer and its own apply path.
- **Phase 2: a snapshot is an ordered sequence, not a set.** `ChunkRenderDistanceCenterS2CPacket`
  must precede the chunk data or `ClientChunkManager` drops every chunk as out of view range, and the
  file looks perfectly correct either way. When adding to `WorldSnapshot`, ask what the client needs
  to have been told first - and add the ordering check to `tools/Setup`, since only that catches it
  without launching the game.
- **Phase 2: `INITIAL_CHUNKS_COMING` is load-bearing and easy to miss.** It is a
  `GameStateChangeS2CPacket` reason, and it is the only thing that moves `ClientChunkLoadProgress`
  out of `Start` - a state with no timeout, unlike `LoadChunks`. Miss it and the loading screen never
  lifts, no matter how much world data arrives.
- **Phase 2: never call `MinecraftClient.disconnect(Screen, boolean)` directly.** It waits on
  `integratedServer.isStopping()` but never asks the server to stop, so in singleplayer it hangs
  forever on "Saving game". Only `disconnect(Text)` is safe: it calls `world.disconnect(reason)`
  first, which closes the connection and is what actually triggers the shutdown. Cost us a killed
  game on the first playback attempt.
- **Phase 2: a client tick handler can re-enter itself.** The disconnect wait above pumps
  `render(false)`, and `onGameJoin` calls `setScreenAndRender`; both tick the client from inside a
  packet Momenton is applying. Guards belong on anything a tick handler drives - `ReplaySession` has
  `disconnecting` and `advancing` for exactly this.
- **Phase 2: the client already ticks a playback connection, so `OfflineConnection` must not.**
  `ClientPlayerInteractionManager.tick` calls `networkHandler.getConnection().tick()`, and
  `onGameJoin` builds an interaction manager bound to the offline handler. Ticking it again from the
  session would tick every packet listener twice per client tick. `OfflineConnection` only drains the
  outbound queue.
- **Phase 2: playback packets come back through the recording tap.** Feeding through a real
  `ClientConnection` means `channelRead0` fires, so `ClientConnectionMixin` would hand a replay's own
  setup packets to `SessionSetup` as though a new session had started. The playback channel carries a
  `Momenton.PLAYBACK` netty attribute and the mixin checks it. The attribute lives on `Momenton`
  because `record` must not depend on `playback`.
- **Phase 2: `FeaturesS2CPacket` arrives *before* `SelectKnownPacksS2CPacket`.**
  `ServerConfigurationNetworkHandler.sendConfigurations` sends it before it even constructs the
  registry sync task, so any of Features / SelectKnownPacks / DynamicRegistries can open a
  configuration sequence. Gating on SelectKnownPacks alone silently drops the feature set.
  `SynchronizeTagsS2CPacket` is the opposite case: it is a common packet that servers also resend
  during play, so it counts as setup only inside a sequence.
- **Phase 2: a filter rule is a claim about playback, and it has to be checked against playback.**
  Dropping `PlayerPositionLookS2CPacket` was justified by "the camera comes from `frames.bin`, so a
  replayed teleport would fight it". The camera is written at TAIL of `Camera.update` and cannot be
  fought; what actually needed the packet was `ClientChunkLoadProgress`, which waits on
  `isRenderingReady(player.getBlockPos())`. The rule survived two phases because no recording had
  ever teleported. Before dropping a packet, ask what else the client does with it, and add the check
  to `tools/Setup` in the same change.
- **Phase 2: a forward seek does not need a snapshot.** Snapshots exist because packets cannot be
  un-applied, which is a statement about seeking *backwards*. Loading one costs a join, a join costs
  `joinWorld`, and that is the terrain-loading screen the viewer sees on every seek. `jump` now
  rebuilds only when the target is behind the playhead or more than one chunk's worth of ticks ahead;
  otherwise it runs the ordinary tick loop without waiting for ticks, over the world already loaded.
- **Phase 2: pausing has to stop tick progress, not just the tick.** Freezing the replay tick while
  the client keeps ticking leaves tick progress sweeping 0 to 1 forever, so the camera interpolates
  towards a tick that never arrives and snaps back once a tick. `PovDriver` holds the progress of the
  last frame drawn. Anything else Phase 3 drives off tick progress - HUD animation, hand swing, item
  cooldowns - will need the same treatment when it is paused. **It did, and one flag covered all of
  them:** see the Phase 3 entry below.
- **Phase 2: playback never needed a world, only a way in.** `ReplaySession.open` builds its own from
  the snapshot; the reason F10 seemed to require being in a world is that `KeyBinding.wasPressed`
  never fires while a screen has focus, and the title screen always has focus. `ui/TitleScreenEntry`
  adds the button through `ScreenEvents.AFTER_INIT` - Fabric API, not a mixin, so a vanilla title
  screen layout change cannot turn it into a startup crash.
- **Phase 3: the replayed player's pose is applied at the HEAD of `ClientPlayerEntity.tick`, and that
  point is load-bearing.** `ClientWorld.tickEntity` calls `resetPosition()` immediately before
  `tick()`, which copies the current position into `lastX`. So at HEAD, `lastX` is the previous
  tick's recorded position, and setting the new one there makes `getX() - lastX` - which
  `LivingEntity.tick` reads for body yaw and `LivingEntity.tickMovement` reads for the limb animator -
  exactly the recorded movement. Vanilla then produces the walk animation and the body turning itself,
  which is why neither is recorded. Applying at end of tick instead renders in the right place and
  animates a player sliding with its legs still, because by then vanilla has already read a delta of
  zero. Anything else Phase 3 drives off the player entity wants the same injection point.
- **Phase 3: the local player's rotation is frame-rate, and rebuilding it at tick rate is visible.**
  Vanilla turns the local player from `Mouse`, once per *frame*, and the camera is only ever a reading
  of `player.getYaw(tickProgress)` - so `frames.bin` already holds that exact value, and everything
  vanilla derives from it inherits its smoothness. The first-person hand sway in
  `HeldItemRenderer.renderItem` is computed straight from `getYaw(tickProgress)` against the
  tick-smoothed `renderYaw`; drive the player at 20 Hz and the hand steps against a camera that moves
  smoothly, once per tick, which is what "the hand shakes when turning" was. `PovDriver.driveLook`
  sets it per frame from the head of `Camera.update`, and sets `lastYaw` with it so
  `getYaw(tickProgress)` *is* the recorded angle rather than a lerp towards it - that angle was read
  out of a live camera and is already interpolated, so interpolating it again smears it twice.
  The upshot: **position comes from `ENTITY_MOVE`, rotation comes from `frames.bin`.**
- **Phase 3: never write `headYaw` on a replayed player.** `LivingEntity.baseTick` does
  `lastHeadYaw = headYaw`, and it runs *inside* `tick()` - after a HEAD injection. So setting
  `headYaw` at the head of the tick makes `lastHeadYaw == headYaw`, the renderer's
  `lerpAngleDegrees(tickDelta, lastHeadYaw, headYaw)` collapses to a constant, and the head snaps to
  its new angle once per tick while the body turns smoothly. That is what "the head shakes in third
  person" was. It is also unnecessary: `PlayerEntity.tickMovement` does `headYaw = getYaw()`, so a
  player's head follows the look direction on its own. The general trap: any `last*` field assigned
  during the tick rather than by `resetPosition` will collapse if its partner is written at HEAD.
  `lastX/lastY/lastZ` and `lastYaw/lastPitch` are safe; `lastHeadYaw` and `lastBodyYaw` are not.
- ~~**Phase 3: the attacker's own hit effects are prediction, and the server deliberately withholds
  them.**~~ **That was wrong, and the way it was wrong is the reusable part.** The reasoning ran:
  `ClientPlayerInteractionManager.attackEntity` runs `PlayerEntity.attack` locally, `World.playSound`
  takes the attacker as `except`, therefore your own sweep arc, crit particles, attack sound and
  knockback exist only on your client and no packet carries them - so record an `ATTACK` event and
  replay the call. Every step of that is a true statement about an older Minecraft and none of it
  holds on 1.21.11. `attack` reaches its effects only through `bl5 = target.sidedDamage(...)`, which on
  a `ClientWorld` is `Entity.clientDamage`; eight classes override it and `LivingEntity` is not one, so
  against a mob it is false and knockback, sweep, particles and sounds are *all* skipped. And the
  `except` argument has inverted meaning on the client: `ClientWorld.playSound(source, ...)` plays only
  when `source == client.player`, while `playAttackSound` passes `null` - so the local call is silent
  even when it is reached. **The error was reasoning about the API instead of measuring the file.**
  `tools/Effects` took twenty minutes to write and answered it outright: the recording already carries
  `sweep_attack` particles, `ANIMATE` crit, `damage_indicator`, `DAMAGE_EVENT` and every attack sound.
  The rule that opens the phase still stands - when something is missing, ask whether the live client
  predicted it - but ask the recording, not the source. And the test for an event action is not "does
  the client run this code", it is "does the client run it *and* produce something the wire lacks".
- **Phase 3: a replay's pause has to borrow vanilla's own pause flag.** Freezing the replay tick stops
  new packets; it does not stop `MinecraftClient.tick` from ticking the world it already built. So
  `PlayerEntity.tick` went on running `ticksSinceLastAttack++` and `tickHandSwing` under a frozen
  camera - the crosshair cooldown filled and the arm finished its swing while the picture was still.
  `MinecraftClient.paused` is exactly the flag for this and it is set only for an integrated server, so
  a replay never gets it; setting it at HEAD of `tick` freezes entities, block entities, particles, the
  HUD tick and the interaction manager in one move, all of which the pause menu already freezes in
  singleplayer. Fixing hand swing alone would have left every other animation running, and Phase 5's
  scrubber would have found them one at a time.
- **Phase 3: a pause is two clocks, not one.** Stopping the tick without stopping the clock that
  interpolates *between* ticks leaves every `lerp(last, current, tickProgress)` oscillating between two
  frozen endpoints once per tick - a violent shake on every entity and on the held item, and visibly
  worse than not pausing. `RenderTickCounter.Dynamic` already solves it: `tick(true)` saves
  `tickProgressBeforePause` and `getTickProgress` returns it unchanged. The catch is *where*
  `MinecraftClient.paused` is set. `render` computes it and two lines later hands it to
  `RenderTickCounter.tick`, so an injection at the head of `tick` is overwritten before the only reader
  that matters. It goes immediately after `render`'s own assignment. `PovDriver.frozen` was solving the
  camera's half of this by hand since Phase 2 and is now the belt to that pair of braces.
- **Phase 3: `client.world == null` is the wrong guard for "playback is over".** The pause menu's
  Disconnect closes the connection first and nulls the world second, and between them
  `MinecraftClient.disconnect` renders - which ticks - so `tickPlayback` ran with a live world and a
  dead channel and threw `ClosedChannelException` on the render thread. Anything that asks "is this
  session still usable" has to ask the connection, which goes first, not the world, which goes second.
  The same ordering makes `stop()` skip its own disconnect when the channel is already closed: calling
  vanilla's teardown from inside vanilla's teardown is the next crash along.
- **Phase 3: `tools/Setup` was checking the dimension change on one tick and it needed a window.** The
  respawn, the player position and `INITIAL_CHUNKS_COMING` do not all land on the same tick - in
  `2026-09-01_22-14-15.momenton` the respawn is at 1055 and the other two at 1056 - so the check failed
  a recording that is fine. It now looks at the respawn and the second after it. A check that is
  stricter than the thing it checks is worse than no check: it would have sent the next session hunting
  a bug in the recorder.
- **Phase 3: an edge is not the same as a start, and `swingHand` is the case that proves it.**
  `LivingEntity.swingHand` restarts a running swing - `handSwingTicks = -1`, `handSwinging` left set -
  whenever the running one is at least half done, so a burst of clicks produces one rising edge and
  several counter drops. Watching `handSwinging` alone therefore replays the first swing of a burst and
  nothing after it. `tools/Swing` measured it: 9 of 46 starts lost. When an animation has a restart
  path, look for what the restart changes, not for the flag that stays put.
- **Phase 3: an animation is driven by its start, not by its frame number.** Hand swing and attack
  cooldown are both counters vanilla advances itself - `tickHandSwing` increments `handSwingTicks`
  every tick, `getAttackCooldownProgress` ramps off `ticksSinceLastAttack`. Writing the recorded value
  in at HEAD makes vanilla advance it again on the same tick, so the swing runs at double speed and
  the cooldown sits a tick ahead. Feeding the *event* instead - `swingHand` on a start,
  `resetTicksSinceLastAttack` when the recorded progress drops, which only happens on an attack -
  makes vanilla reproduce the animation from the same input it had live. The cooldown ramp even comes
  out right for free, because its rate depends on the held item and `LOCAL_PLAYER` has restored it.
  This is the same rule as the limb animator and body yaw, and it is now three for three.
- **Phase 3: a count that your own loop bounded is not a measurement.** The snapshot held 121 chunks,
  which is exactly the full 11x11 at render distance 5, and that looked like proof it captured
  everything. It was not: `addChunks` scanned a radius of 5, so 121 was the most it could ever return.
  The real answer came from measuring the *recording* - decoding chunk coordinates out of the streamed
  packets and comparing them to the chunk-map centre - which showed the server sends at distance 6 and
  the snapshot reached 5. Whenever a number matches the assumption exactly, check whether the code
  that produced it could have returned anything else.
- **Phase 3: the client holds more chunks than it renders, and a snapshot has to take all of them.**
  Servers send out to `viewDistance + 1` and `ClientChunkManager.getChunkMapRadius` keeps
  `max(2, viewDistance) + 3`. A snapshot scanned at the render distance leaves the outer ring in no
  snapshot at all, and nothing re-sends it, so the replay keeps a permanent hole at the edge of view.
  Unloaded chunks come back null and cost nothing, so scanning the map radius is free where the world
  is smaller and correct where it is not.
- **Phase 3: the recorded camera rotation is the player's rotation only in two of the three
  perspectives.** `Camera.update` sets the camera to `(yaw + 180, -pitch)` in third-person **front**,
  so `frames.bin` holds an inverted rotation for those frames. Driving the player from it unchanged
  spins the body 180 degrees - the character walks backwards - which is a defect that only appears in
  a recording that used F5 twice, and so is easy to ship. `FLAG_FRONT_VIEW` marks those frames and
  `PovDriver.driveLook` undoes the inversion. The same flag lets `applyPerspective` restore all three
  perspectives instead of collapsing both third-person modes onto BACK, which it did before.
  The general point: `frames.bin` holds a *camera* pose, and the camera is not the player.
- **Phase 3: `isSneaking()` on the client player is an override that reads `player.input`.** It is not
  the shared `Entity` flag, so `setSneaking` does nothing useful for a replay and the flag never
  crosses the wire back to its owner either. The fix is to hand vanilla the input it would have had -
  a real `Input` whose `playerInput` carries the recorded sneak - and let `PlayerEntity.updatePose`
  reach `CROUCHING` by itself. Movement stays false in that input, or `travel` would walk the player
  off its own path.
- **Phase 3: `noClip` cannot be used to stop the replayed player being simulated.**
  `PlayerEntity.tick` assigns `this.noClip = this.isSpectator()` as its first instruction, so it is
  overwritten immediately after any HEAD injection. `setNoGravity(true)` plus a zeroed velocity does
  the job instead: `travel` then moves the player by nothing, so collision and entity pushing never
  get a chance to act.
- **Phase 3: every change-gated tracker resets at a chunk boundary.** Deltas and change gates are both
  relative to the writer's running state, which spans the whole recording, while a seek loads exactly
  one chunk. Without the reset, a seek into chunk 3 cannot resolve a single `ENTITY_MOVE` delta and
  never learns a hotbar slot last written in chunk 1. `tools/Motion` checks the `ENTITY_MOVE` half of
  this and fails every recording made before it. The cost is one absolute pose per moving entity per
  500 ticks.
- **Phase 3: the frame track and the entity track cross-check each other offline.** In first person
  the recorded camera is the player's own eye, so the horizontal distance between `frames.bin` and the
  interpolated `ENTITY_MOVE` path should be quantisation noise. `tools/Motion` measures it at three
  tick offsets, which turns "is playback showing the camera and the world at the same instant" from an
  argument into a number. A frame stamped tick T was rendered after tick T-1 was recorded, so it
  should match `lerp(pose[T-2], pose[T-1])` - offset 0 in the tool's output.
- **Phase 1: a batch caps the ticks of the actions in it.** Netty keeps queueing packets after the
  last recorded tick, so the closing flush was stamping them with a tick that never happened and
  pushing an action outside its chunk's advertised range. `submitBatch(at)` now caps every action at
  `at`. The chunk index means `[startTick, endTick)` and that has to stay true.

---

## Phase 0 - Foundations (complete)

- [x] Delete `ExampleMixin` and remove it from `momenton.mixins.json`
- [x] Add `MomentonClient` and register it under a `client` entrypoint
- [x] Add a `client` block to `momenton.mixins.json`
- [x] Fill the `fabric.mod.json` placeholders; drop the template `contact` URLs
- [x] Add imgui-java (binding + Windows natives) via Loom `include`, with `mavenCentral()`
- [x] Confirm `imgui-java-lwjgl3` is absent, with a comment in `build.gradle` saying why
- [x] Add `checkNoDirectOpenGl` and wire it into `check`
- [x] Write `README.md`, `IMPLEMENTATION_PLAN.md`, `TASKS.md`
- [x] `gradlew.bat build` succeeds and nests the imgui jars

## Phase 1 - Recording

Goal: a `.momenton` file on disk that contains everything a replay needs.

Unticked boxes below are **written and compiling, but not yet observed working**.

- [x] `gradlew.bat runClient` launches cleanly and the client entrypoint runs
- [x] `format/ActionCodec.java` - varint-framed action read/write for `PACKET`, `ENTITY_MOVE`,
      `LOCAL_PLAYER`, `DIMENSION_CHANGE` (also `UI_STATE`, which shares the framing). Round-trip
      observed; `Reader` takes the chunk's `startTick`
- [x] `format/ReplayMetadata.java` - JSON metadata: versions, duration, chunk index, dimension changes
- [x] `format/ReplayWriter.java` - rolls a chunk every 500 ticks, streams parts to a staging
      directory and zips on stop so a crash loses at most one chunk
- [x] `mixin/client/ClientConnectionMixin.java` - `channelRead0` HEAD, single static call out.
      Now also drops serverbound traffic, which the integrated server feeds through the same hook
- [x] `record/Recorder.java` - IDLE/RECORDING/FINALISING state machine, single-threaded writer
      executor, no IO on the netty thread
- [x] Capture the `DynamicRegistryManager` from `ClientPlayNetworkHandler` at recording start.
      ~35 000 packets re-encoded through it with no failures
- [x] Packet filter: expand `BundleS2CPacket` into sub-packets. Id 0 never appears while the
      `ADD_ENTITY` packets vanilla bundles do
- [x] Packet filter: rewrite signed player-chat as system chat. One decorated `SYSTEM_CHAT` at tick
      149, no `PLAYER_CHAT` anywhere
- [ ] Packet filter: drop custom payloads for absent mods (`UnknownCustomPayload`, which keeps the id
      and discards the body, so re-encoding it would produce an unreadable packet) *(written; the
      session had no absent mods, so its absence from the file proves nothing)*
- [x] ~~Packet filter: skip local-player position packets~~ **Removed - the rule was wrong.** The
      first session that exercised it (a dimension change is a teleport) showed it breaks the
      terrain-loading screen. Position packets are recorded. See Current state
- [x] `record/FrameTrackWriter.java` - one 56-byte record per rendered frame, allocation-free, two
      reused `ByteBuffer`s flushed in 256-frame blocks. 3024 records, exact size, ~12 block flushes
- [x] `record/UiStateTracker.java` - selected slot, own-inventory-open, hand swing, attack cooldown,
      chat open/scroll. 226 change-gated actions over 1012 ticks
- [x] `record/EntityPositionTracker.java` - `WeakHashMap<Entity, LastPos>`, delta-compressed.
      One action per tick, every tick
- [x] `record/LocalPlayerWriter.java` - health, hunger, hotbar, held item per tick. 9 actions,
      correctly change-gated
- [x] Keybind for start/stop recording (F9, category `key.category.momenton.main`)
- [x] Recording HUD indicator (red dot + elapsed), vanilla `DrawContext`. Seen on screen during the
      second recording
- [x] **Gate:** a minute of survival play produces a zip whose `metadata.json` has a plausible tick
      count, whose chunks are non-empty, and whose `frames.bin` size equals `56 * framesRendered`.
      Re-run on the fixed build: 693 ticks, `frames.bin` exactly `56 * 6886 = 385 616` bytes, every
      action inside its chunk's advertised range, no packets dropped

## Phase 2 - Playback

Goal: load a `.momenton` file and watch it back, matching what you saw.

Phase 2 opens on the **recording** side: a Phase 1 recording has no registries and no game join, so
there is nothing for playback to build a world from. See "`setup.bin`, and why recording mid-session
needs it" in `IMPLEMENTATION_PLAN.md`.

- [x] `record/SessionSetup.java` - buffers the configuration-phase packets and the game join from
      connect, not from F9, since F9 is retroactive and they arrived long before it
- [x] `Recorder.begin` writes `setup.bin` and queues the game join as the first action of tick 0
- [x] `Recorder.rejoin` rewrites the join's dimension, dimension type, sea level and game mode from
      the live world, so recording in the Nether after joining the Overworld replays the Nether
- [x] **Observed:** `2026-08-31_16-08-44.momenton` carries a 27-packet, 53 KB `setup.bin` reading
      `FEATURES, SELECT_KNOWN_PACKS, REGISTRY_DATA x23, UPDATE_TAGS, READY` in that order, and the
      first action of `chunk_0000` is `LOGIN` at tick 0 naming `minecraft:overworld`
- [x] `playback/OfflineConnection.java` - `ClientConnection` over a netty `EmbeddedChannel` with a
      real `ClientPlayNetworkHandler`. `setup.bin` goes through a `ClientConfigurationNetworkHandler`
      and `ReadyS2CPacket` does the transition into play, exactly as vanilla does
- [x] `format/ReplayReader.java` - random access to the parts of a replay, `ZipFile` not
      `ZipInputStream` so seeking can load one chunk out of the middle
- [x] `playback/ReplaySession.java` - open, replay setup, feed a tick of packets per client tick.
      Only `PACKET` actions are applied; the packet stream already moves entities, and the
      `ENTITY_MOVE` / `LOCAL_PLAYER` / `UI_STATE` tracks are the higher-fidelity client-side view,
      which is Phase 3
- [x] F10 plays the newest recording, a stand-in for the replay browser
- [x] `record/WorldSnapshot.java` - the world at recording start as the packets that would have
      produced it: `INITIAL_CHUNKS_COMING`, time, player position, **the chunk map recentre**, loaded
      chunks, entity spawns. Written as tick-0 actions after the join. Playback needed no new code
- [x] Snapshots at every chunk boundary, not just recording start. `snapshot_NNNN.bin` leads with a
      join packet, so each is a standalone rebuild and a seek never has to undo anything
- [x] Snapshot threading: chunks serialised on the common `ForkJoinPool`, nearest first. The client
      thread **blocks** on it rather than handing the work off - the world cannot change while the
      thread that owns it waits, so parallel readers need no synchronisation and the snapshot stays
      consistent
- [x] Seeking - folded into `ReplaySession.jump` rather than a `Seeker` class, because loading a
      snapshot is already what starting playback does. See `IMPLEMENTATION_PLAN.md`
- [x] `playback/ReplaySession.java` - open/close, play/pause, tick advance, seek
- [x] Playback mixins: suppress outbound packets. Satisfied by the design rather than a mixin: the
      `EmbeddedChannel` has no socket behind it and `drainOutbound` empties the queue each tick, so
      nothing a replayed handler sends can go anywhere
- [x] Playback mixins: local input must not affect the world. `MinecraftClientMixin` cancels
      `handleInputEvents` and `handleBlockBreaking`, `MouseMixin` cancels `updateMouse`, and
      `player.input` is replaced every tick. Without the first two, clicking during playback mines
      the replay's blocks
- [x] Playback mixins: neutralise disconnect/timeout. Also satisfied by the design, and verified
      rather than assumed: an `EmbeddedChannel` has no read-timeout handler, and `onDisconnected` is
      only reachable through `handleDisconnection()`, which needs the connection ticked after being
      closed - a seek replaces it synchronously, so it never is
- [x] Playback mixins: pause menu must not kill the session. Esc opens the menu and playback
      continues behind it (`isPaused()` needs an integrated server, and a replay has none); "Save and
      Quit" tears the world down, which `onClientTick` notices and stops cleanly
- [x] Playback mixins: perspective follows the recording. Better than the planned "lock to first
      person": the frame flags carry what the camera actually was, so recorded third-person spans
      replay in third person
- [x] Replay browser `Screen` (vanilla widgets): list with duration, date and dimension, play,
      delete. No thumbnail - `thumb.png` is not written yet
- [x] **Gate:** plays at 1x with entities moving correctly and the camera matching the original; seek
      to an arbitrary tick lands in under two seconds. **Observed** on
      `2026-08-31_17-58-19.momenton` (1244 ticks, 3 snapshots): `[` and `]` both land where they
      should, instantly, with the world intact afterwards; `P` freezes it; the browser lists the
      recording with the right duration and date
- [x] `ReplaySession.jump` replays a short forward seek in place instead of rebuilding. **Observed:**
      `]` no longer flashes the terrain-loading screen. `[` still does, and always will until playback
      can reuse a `ClientWorld`
- [x] `PovDriver` holds tick progress while paused. **Observed:** `P` freezes the camera dead
- [x] `ui/TitleScreenEntry` puts the replay browser on the title screen, so playback needs no world.
      **Observed:** the button is there and Play from it goes straight into the replay
- [x] Playing through a dimension change. `PacketFilter` no longer drops `PlayerPositionLookS2CPacket`,
      without which the terrain screen holds for its 30-second timeout after every respawn.
      **Observed** on `2026-08-31_19-20-19.momenton`: Overworld to Nether at tick 96 and back at 876,
      both crossings played through, and seeking across one lands in the right dimension
- [x] `metadata.json` carries `worldName` and the browser row shows it. **Observed:**
      `New World   0:57   31 Aug, 19:20`. The dimension column was dropped again as noise

## Phase 3 - HUD and GUI reproduction

Goal: the thing that makes exports look like OBS captures.

Phase 2 leaves three tracks recorded but unapplied - `LOCAL_PLAYER`, `UI_STATE` and `ENTITY_MOVE`.
That is by design (the packet stream alone already moves the world) but it is exactly what the first
Phase 2 playthroughs showed missing, so the first six boxes are the concrete version of it:

- [x] Move the replayed local player along its recorded path. **Observed:** the player walks its
      recorded path, the head and body turn smoothly, the legs animate, the first-person hand no longer
      jitters, and it crouches. **Position is not from `frames.bin`** - that is the camera pose, which
      is the player's eye only in first person - but **rotation is**, see the decisions below. Took
      two playthroughs and four fixes; the one still unverified is third-person *front*, which faced
      the character backwards until `FLAG_FRONT_VIEW`
- [x] Apply `LOCAL_PLAYER`: health, hunger, hotbar contents, held item. **Observed.** Two halves:
      `WorldSnapshot` sends an `InventoryS2CPacket` for what the player already had at F9 - the fifth
      instance of the pre-F9 gap, and why the hotbar rendered empty - and `format/LocalPlayerCodec`
      plus `LocalPlayerDriver.acceptState` apply the track for everything after it, including the
      inventory-screen move that crosses no wire. `tools/Setup` checks the snapshot half
- [x] Apply `UI_STATE`: **selected slot and chat scroll are observed working.** `ReplaySession` reads
      `ui_NNNN.bin` in step with the chunk track, which nothing did before. The note that this box was
      "assignment, not a mixin" was wrong: `handSwingTicks` and the attack cooldown are animations
      vanilla advances itself during the tick, so writing the recorded numbers at HEAD makes it advance
      them twice. `UiStateDriver` feeds it the **starts** instead - `swingHand` on a start,
      `resetTicksSinceLastAttack` when the recorded cooldown drops. *(Swing restarts and the pause fix
      are written and unobserved - see **Not yet verified**.)*
- [x] **The attack itself needs no replay on 1.21.11 - it is all on the wire.** *(Written, not yet
      watched.)* The `ATTACK` action, `ClientPlayerInteractionManagerMixin` and the `player.attack`
      call are removed. `PlayerEntity.attack` gates its whole effects block on
      `target.sidedDamage(...)`, which on a `ClientWorld` is `Entity.clientDamage`; only eight classes
      override that and `LivingEntity` is not one, so against a mob it returns false and knockback,
      sweep, particles and sounds are all skipped. The surviving `playAttackSound` reaches
      `ClientWorld.playSound(source, ...)`, which plays only when `source == client.player`, and the
      caller passes `null`. `tools/Effects` confirms the recording carries all of it as packets:
      `sweep_attack` at 412 and 433, `ANIMATE` crit at 91 and 1625, `damage_indicator` and
      `DAMAGE_EVENT` on every hit, attack sounds as `SOUND`
- [ ] **Record the block interaction the server sends to everyone but you.** **Observed missing:** no
      block break particles and no place sound on replay. Root cause proven:
      `ServerWorld.syncWorldEvent` ends in `PlayerManager.sendToAround`, whose loop is
      `if (serverPlayerEntity != player)`, so the breaker is the one player who never receives
      `BLOCK_BROKEN` - they get it locally from `ClientPlayerInteractionManager.breakBlock` ->
      `Block.onBreak` -> `spawnBreakParticles`. Measured: `2026-09-01_22-14-15.momenton` has 27
      `BLOCK_CHANGED_ACK` and only 4 `LEVEL_EVENT 2001`, and those four are not the player's. Same
      asymmetry for the crack overlay (`setBlockBreakingInfo` on your own id), the mining hit sound
      (straight to `SoundManager` every four ticks) and the place sound (`BlockItem.place` calls
      `world.playSound(player, ...)`, which the client plays *because* the source is the local player).
      **Do it by synthesising the packets the server sent to everyone else** -
      `WorldEventS2CPacket(2001, pos, rawStateId, false)`, `BlockDestructionS2CPacket`,
      `PlaySoundS2CPacket` - written as ordinary `PACKET` actions. That needs no action type, no codec
      and no playback code at all, the same argument that made the snapshot a sequence of packets
- [ ] Apply `ENTITY_MOVE` over the packet stream - the same movement at client precision rather than
      the wire's 1/4096 block
- [ ] Record and apply the portal warp. **Observed missing:** a Nether crossing cuts instantly
      instead of playing the screen-warping build-up. `ClientPlayerEntity.nauseaIntensity` and
      `lastNauseaIntensity` are public fields, driven by `tickNausea` from
      `portalManager.isInPortal()` - which is never true for a replayed player, so the value has to
      be recorded rather than recomputed. Belongs in `UI_STATE`
- [ ] Record and apply the local player's fire ticks. **Observed wrong:** entering lava plays the
      fire overlay a beat late, because the live client set it locally and the replay waits for the
      server's `SET_ENTITY_DATA`. `Entity.setFireTicks` is public. Belongs in `LOCAL_PLAYER`, and it
      is the general case: anything the live client predicted locally replays late or not at all
- [ ] `hud/HudReproducer.java` - drive `InGameHud.render` with the synthetic `RenderTickCounter`,
      applying `UI_STATE` first
- [ ] `hud/ScreenReproducer.java` - open container screens from replayed `OpenScreenS2CPacket`
- [ ] Synthesise `InventoryScreen` when `UI_STATE` says the player's own inventory was open
- [ ] Feed recorded cursor position into `screen.render(context, mouseX, mouseY, deltaTicks)`
- [ ] `hud/HudToggles.java` + `mixin/client/hud/InGameHudMixin.java` - gate per-element render methods
- [ ] **`render/FrameRenderer.java`** - the single composition path
      `render(replayTick, tickProgress, cameraPose, uiState, target)`. Get the signature right now,
      while it has one caller
- [ ] **Gate:** record a session with OBS running simultaneously; playback of a span that included
      opening a chest, moving items and typing in chat matches the OBS capture

## Phase 4 - ImGui on Blaze3D (highest risk)

Goal: prove the editor UI is possible before building an editor on it.

- [ ] **Spike (throwaway):** one ImGui demo window rendering over the game
- [ ] **Spike:** the same window renders correctly with VulkanMod 0.6.3 installed
- [ ] **Spike:** `CommandEncoder.copyTextureToBuffer` readback works under VulkanMod (pulled forward
      from Phase 7 deliberately - finding this out during export would be expensive)
- [ ] **Decision point:** if the spike fails, switch to a custom widget toolkit on `GuiGraphics` and
      record that here before continuing
- [ ] `ui/imgui/ImGuiBlaze3DRenderer.java` - draw data to `GpuBuffer`s, `RenderPipeline` with
      pos/UV/colour + alpha blend, render pass on the main framebuffer view, one draw per `ImDrawCmd`
      with clip rect as scissor
- [ ] Font atlas uploaded once via `CommandEncoder.writeToTexture`
- [ ] `ui/imgui/ImGuiInputBridge.java` - bridge from `Screen` callbacks, **not** raw GLFW callbacks
- [ ] `ui/EditorScreen.java` - a normal `Screen` owning the ImGui frame
- [ ] Editor shell: docked Preview, Timeline, Inspector panels; dark theme, no Minecraft textures
- [ ] **Gate:** editor opens over a paused replay, panels dock and resize, renders identically with
      and without VulkanMod

## Phase 5 - Timeline, editing and live preview

Goal: cut a video and watch the result live.

- [ ] `edit/Project.java` - JSON beside the replay, references it by name, never embeds footage
- [ ] `edit/Clip.java` + `edit/Timeline.java` - ordered `[startTick, endTick)` slices
- [ ] `edit/TimelineResolver.java` - timeline position to `(clip, sourceReplayTick, tickProgress)`.
      One implementation, used by preview and export
- [ ] `edit/UndoStack.java` - command objects
- [ ] Operations: trim handles, razor/split, drag to reorder, ripple delete
- [ ] `ui/panel/TimelinePanel.java` - scrubber, zoomable ruler, drag-and-drop, snapping
- [ ] `ui/panel/PreviewPanel.java` - resolve playhead, seek only when the clip changes, call
      `FrameRenderer` into a panel-sized framebuffer
- [ ] Preview transport: play/pause, frame step, loop, 0.25x-2x review speed
- [ ] Preview framing: letterbox to export aspect ratio, show target resolution and fps
- [ ] Preview performance: render-scale setting; "render this frame at full export resolution" button
- [ ] `ui/panel/InspectorPanel.java` - selection properties + HUD toggles, applied live
- [ ] **Gate:** split into three clips, reorder, trim two, undo all, reload from disk, play back as
      continuous video honouring the edited order, HUD toggles taking effect immediately

## Phase 6 - Audio

- [ ] `audio/WavWriter.java`
- [ ] `audio/MicRecorder.java` - `TargetDataLine` at 48 kHz on its own thread, first-sample tick in
      metadata
- [ ] Mic device picker in settings
- [ ] `mixin/client/SoundEngineMixin.java` - substitute a loopback device while exporting
- [ ] `audio/LoopbackRenderer.java` - `alcRenderSamplesSOFT` for exactly the samples belonging to each
      rendered frame
- [ ] Per-track gain and mute in the Inspector
- [ ] Preview audio: play `mic.wav` in sync with the playhead, seeking when it jumps
- [ ] **Gate:** an exported clip has clean game audio in sync plus commentary on a separate track

## Phase 7 - Export

- [ ] `export/FfmpegProvisioner.java` - download static build to `.minecraft/momenton/bin/`, verify
      SHA-256 against a pinned manifest, cache, fail loudly
- [ ] Encoder probe: cache which of `h264_nvenc` / `h264_amf` / `h264_qsv` exist, fall back to
      `libx264`
- [ ] `export/FrameGrabber.java` - prove the path with `ScreenshotRecorder.takeScreenshot` first
- [ ] `FrameGrabber` optimised: `GpuBuffer` with `USAGE_COPY_DST | USAGE_MAP_READ`, 2-3 buffers
      rotated with `createFence()`
- [ ] `export/FfmpegProcess.java` - subprocess, stdin pumped on a dedicated thread, stderr always
      drained
- [ ] `export/ExportJob.java` - offline render loop calling the **same `FrameRenderer` as the
      preview**; no composition logic in this file
- [ ] `ui/panel/ExportDialog.java` - resolution, fps, codec, encoder, quality, path, progress, cancel
- [ ] **Gate:** a 30-second edit exports to 1080p60 MP4, plays in VLC, both audio tracks in sync, HUD
      present
- [ ] **Gate:** export one frame at full resolution and diff against the preview at the same timeline
      position; they should differ only by render scale

## Phase 8 - Hardening

- [ ] Full pipeline under VulkanMod 0.6.3: capture, ImGui, offline render, readback
- [ ] Recording overhead measured; under 1 ms per tick, negligible per-frame cost for
      `FrameTrackWriter`
- [ ] Disk full mid-recording
- [ ] Dimension change mid-recording
- [ ] Server kick mid-recording
- [ ] Replay from a mismatched Minecraft version rejected with a clear message
- [ ] JUnit coverage for the pure-data pieces: `ActionCodec` round-trip, `frames.bin` fractional
      sampling, `UndoStack`
- [ ] Export at 4K60 from a 1080p session, confirming resolution is a free parameter
- [ ] `README.md` final pass

---

## Next session - watch a fight replay, then finish Phase 3

**Phase 3 is roughly 30% done: 4 of 15 boxes, and the whole HUD and screen half is untouched.**

**Start here, and start with the deletion.** Five things changed across the last two sessions and none
has been confirmed in game: swing restarts are replayed, a paused replay freezes the world *and* the
clock that interpolates between ticks, the `ATTACK` action is gone, and playback survives the pause
menu's Disconnect. The removal is the one worth trying hardest to disprove.

`2026-09-01_22-14-15.momenton` is on disk and is the right recording for all of it - 46 swing starts
with 9 restarts among them, 5 hits on mobs, two sweeps, two crits, and a Nether crossing at 1055 and
1265. Nothing needs recording again unless something below fails.

1. `gradlew.bat build`, then play `2026-09-01_22-14-15.momenton` back.
2. **Around ticks 411-433**, where two sweeps land back to back: the sweep arc should appear, the mob
   should flash and be knocked back, and the attack sound should be the sweep one. **Around tick 89
   and tick 1624**, crit particles. If any of that is missing, the removal was wrong *or* something in
   playback is eating `LEVEL_PARTICLES` / `ANIMATE` / `SOUND` - `tools/Effects` proves the packets are
   in the file, so the question is only which end. Check `run/logs/latest.log` before assuming.
3. **Around ticks 16-28, 148-159 and 168-178**, where the recorded swing restarts mid-animation: the
   arm should keep swinging through the burst instead of playing once and stopping.
4. **Press P mid-swing.** Everything should stop dead and stay still - the arm, the crosshair cooldown
   ring, mobs, particles, items on the ground. **Nothing should shake**; a shake means the render tick
   counter is not being told about the pause and the injection has moved. Unpause and check it resumes
   rather than jumping.
5. **Leave playback by the pause menu's Disconnect, and separately by closing the window.** Both used
   to crash with `ClosedChannelException`.
6. **Seek across the Nether crossings at 1055 and 1265** while you are there. That regression case only
   came back with this recording.

Then the rest of Phase 3, in order. The next box is **block interaction**, which is diagnosed and
sized and needs no new mechanism - synthesise the packets the server sent to everyone but the breaker
and write them as ordinary `PACKET` actions. It needs a fresh recording that breaks and places blocks.
After that: `ENTITY_MOVE` for other entities (the last unapplied track), the portal warp and fire
ticks, and then the screens - `HudReproducer`, `ScreenReproducer`, the synthesised `InventoryScreen`,
the recorded cursor, `HudToggles`, and `FrameRenderer`. Think about `FrameRenderer` before writing it:
it is born with one caller and gets a second in Phase 7, and the plan is explicit that retrofitting it
later is how preview and export drift apart. The phase gate is the OBS side-by-side comparison.

**Still true and still unproven:** no recording exercises third person or crouch. Both were observed
working once; neither has a regression case.
